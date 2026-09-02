package com.tunnelguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

enum class ServiceState {
    NO_VPN,
    TUNNELGUARD_STARTING,
    TUNNELGUARD_ACTIVE,
    TUNNELGUARD_STOPPING,
    UPSTREAM_VPN,
    VPN_CONFLICT,
    ERROR
}

class TunnelGuardVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var lastEstablishedApps: Set<String>? = null
    private var lastEmergencyLock: Boolean? = null
    private var lastCountryFailureLog: String? = null
    private lateinit var config: TunnelGuardConfig
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var vpnDetector: VpnDetector
    private lateinit var appMonitor: ProtectedAppMonitor

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var monitorJob: Job? = null

    // Callback registration tracking to avoid multiple registrations across repeated ACTION_UPDATE commands
    private var isCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        /**
         * Re-evaluates VPN routing when a network becomes available.
         */
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            config.addLog("Network Callback: onAvailable. Re-evaluating routing.")
            serviceScope.launch { checkAndRunVpnRouting() }
        }

        /**
         * Handles network loss by clearing cached network data and re-evaluating VPN routing.
         *
         * @param network The network that was lost.
         */
        override fun onLost(network: Network) {
            super.onLost(network)
            config.addLog("Network Callback: onLost. Re-evaluating routing.")
            (vpnDetector as? DefaultVpnDetector)?.countryResolver?.clearCacheForNetwork(network)
            serviceScope.launch { checkAndRunVpnRouting() }
        }

        /**
         * Re-evaluates VPN routing when a network's capabilities change.
         *
         * @param network The network whose capabilities changed.
         * @param networkCapabilities The updated capabilities of the network.
         */
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val transports = mutableListOf<String>()
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ETHERNET")
            val transportStr = if (transports.isEmpty()) "OTHER" else transports.joinToString(", ")
            config.addLog("Network Capabilities Changed. Transports: $transportStr. Re-evaluating routing.")
            serviceScope.launch { checkAndRunVpnRouting() }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        /**
         * Re-evaluates VPN protection in response to a screen or user-presence event.
         *
         * @param context The context receiving the event.
         * @param intent The event intent.
         */
        override fun onReceive(context: Context, intent: Intent) {
            config.addLog("Screen/Wake event: ${intent.action}. Re-evaluating protection.")
            serviceScope.launch { checkAndRunVpnRouting() }
        }
    }

    private var isScreenReceiverRegistered = false

    companion object {
        const val ACTION_START = "com.tunnelguard.app.START"
        const val ACTION_STOP = "com.tunnelguard.app.STOP"
        const val ACTION_UPDATE = "com.tunnelguard.app.UPDATE"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "TunnelGuardVpnChannel"
        private const val ALERT_CHANNEL_ID = "TunnelGuardAlertChannel"

        // Global check utility to find if VpnService is active (for UI binding)
        var isServiceRunning = false
            private set

        @Volatile
        var isServiceStarting = false

        @Volatile
        var isTunnelEstablished = false

        @Volatile
        var pendingWarningId: String? = null

        val stateLock = Any()
        val warningLock = Any()

        fun shouldPostFallbackWarning(warningId: String): Boolean {
            synchronized(warningLock) {
                return pendingWarningId == warningId
            }
        }

        @Volatile
        var currentServiceState = ServiceState.NO_VPN
            private set

        fun updateServiceState(state: ServiceState) {
            synchronized(stateLock) {
                currentServiceState = state
            }
        }

        private val suppressedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun suppressPackage(packageName: String, durationMs: Long = 15000) {
            suppressedPackages[packageName] = System.currentTimeMillis() + durationMs
        }

        fun isPackageSuppressed(packageName: String): Boolean {
            val expiry = suppressedPackages[packageName] ?: return false
            if (System.currentTimeMillis() > expiry) {
                suppressedPackages.remove(packageName)
                return false
            }
            return true
        }

        fun shouldTriggerWarning(
            currentApp: String,
            lastForegroundApp: String?,
            isVpnOn: Boolean,
            wasVpnOn: Boolean?,
            isSuppressed: Boolean
        ): Boolean {
            return !isVpnOn && !isSuppressed && (currentApp != lastForegroundApp || wasVpnOn == true)
        }
    }

    /**
     * Initializes the VPN service and its connectivity configuration.
     */
    private fun transitionTo(newState: ServiceState) {
        synchronized(stateLock) {
            val oldState = currentServiceState
            if (oldState != newState) {
                updateServiceState(newState)
                config.addLog("Service State Transition: $oldState -> $newState")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceStarting = false
        config = TunnelGuardConfig(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        vpnDetector = DefaultVpnDetector(config)
        appMonitor = ProtectedAppMonitor(config, vpnDetector)
        isServiceRunning = true
        transitionTo(ServiceState.TUNNELGUARD_STARTING)
    }

    private fun startMonitoring() {
        val enabled = config.isAppMonitorEnabled() &&
                      config.hasUsageStatsPermission(this) &&
                      config.hasSystemAlertWindowPermission()

        if (!enabled) {
            stopMonitoring()
            return
        }
        if (monitorJob != null) return
        monitorJob = serviceScope.launch {
            var lastForegroundApp: String? = null
            var wasVpnOn: Boolean? = null
            while (isActive) {
                try {
                    val loopEnabled = config.isAppMonitorEnabled() &&
                                  config.hasUsageStatsPermission(this@TunnelGuardVpnService) &&
                                  config.hasSystemAlertWindowPermission()
                    if (!loopEnabled) {
                        config.addLog("App monitor or permissions revoked/disabled. Cancelling monitoring loop.")
                        stopMonitoring()
                        break
                    }

                    val evalResult = appMonitor.evaluateMonitoringState(
                        context = this@TunnelGuardVpnService,
                        connectivityManager = connectivityManager,
                        lastForegroundApp = lastForegroundApp,
                        wasVpnOn = wasVpnOn
                    )

                    when (evalResult) {
                        is MonitoringCheckResult.TriggerWarning -> {
                            val currentApp = evalResult.targetPackage
                            val vpnChoice = config.getVpnAppOfChoice()

                            if (config.isAutoConnectVpnEnabled() && vpnChoice != null) {
                                config.addLog("Protected app opened ($currentApp) without VPN. Auto-connect VPN active; launching $vpnChoice directly in background.")
                                config.setPendingVpnRedirectTarget(currentApp)
                                try {
                                    val launchIntent = packageManager.getLaunchIntentForPackage(vpnChoice)
                                    if (launchIntent != null) {
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(launchIntent)
                                    } else {
                                        config.addLog("Could not find launch intent for VPN app $vpnChoice, falling back to VpnWarningActivity", "WARN")
                                        launchWarningActivity(currentApp)
                                    }
                                } catch (e: Exception) {
                                    config.addLog("Failed to auto-launch VPN app $vpnChoice: ${e.message}, falling back to VpnWarningActivity", "ERROR")
                                    launchWarningActivity(currentApp)
                                }
                            } else {
                                launchWarningActivity(currentApp)
                            }

                            lastForegroundApp = evalResult.targetPackage
                            wasVpnOn = evalResult.isVpnOn
                        }
                        is MonitoringCheckResult.NoAction -> {
                            if (evalResult.currentApp != null) {
                                lastForegroundApp = evalResult.currentApp
                            }
                            if (evalResult.isVpnOn != null) {
                                wasVpnOn = evalResult.isVpnOn
                            }
                        }
                    }
                } catch (e: Exception) {
                    config.addLog("Error in app monitor loop: ${e.message}")
                }
                delay(1000) // Poll every 1 second
            }
        }
    }

    private fun launchWarningActivity(currentApp: String) {
        val warningId = java.util.UUID.randomUUID().toString()
        synchronized(warningLock) {
            pendingWarningId = warningId
        }

        config.addLog("Protected app opened or VPN dropped: $currentApp. Automatically opening warning and VPN redirection.")

        val warningIntent = Intent(this@TunnelGuardVpnService, VpnWarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_package", currentApp)
            putExtra("warning_id", warningId)
        }

        // Attempt to directly launch the warning/redirection activity
        config.addLog("Attempting direct launch of VpnWarningActivity for $currentApp.")
        try {
            this@TunnelGuardVpnService.startActivity(warningIntent)
        } catch (e: Exception) {
            config.addLog("Could not start VpnWarningActivity directly from background: ${e.message}")
        }

        serviceScope.launch {
            delay(1000)
            synchronized(warningLock) {
                if (shouldPostFallbackWarning(warningId)) {
                    config.addLog("VpnWarningActivity did not launch in time. Posting fallback warning notification.")
                    val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        android.app.ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        ).toBundle()
                    } else {
                        null
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        this@TunnelGuardVpnService,
                        1002,
                        warningIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        options
                    )

                    var appLabel = currentApp
                    try {
                        val pm = packageManager
                        val appInfo = pm.getApplicationInfo(currentApp, 0)
                        appLabel = pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        // Ignore
                    }

                    val warningNotificationBuilder = NotificationCompat.Builder(this@TunnelGuardVpnService, ALERT_CHANNEL_ID)
                        .setContentTitle("Security Warning")
                        .setContentText("$appLabel opened without an active VPN connection!")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)

                    // Only call setFullScreenIntent when permission is available according to NotificationManagerCompat
                    val managerCompat = androidx.core.app.NotificationManagerCompat.from(this@TunnelGuardVpnService)
                    if (managerCompat.canUseFullScreenIntent()) {
                        warningNotificationBuilder.setFullScreenIntent(pendingIntent, true)
                    }

                    val warningNotification = warningNotificationBuilder.build()
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(1002, warningNotification)
                } else {
                    config.addLog("VpnWarningActivity launched successfully. Skipping fallback notification.")
                }
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Handles service start, update, and stop commands, registering required callbacks and evaluating VPN routing.
     *
     * @return `START_NOT_STICKY` when the service is stopped; `START_STICKY` for normal operation.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        config.addLog("VpnService received action: $action")

        if (action == ACTION_STOP) {
            config.setLastDisconnectReason("User stopped protection")
            synchronized(stateLock) {
                transitionTo(ServiceState.TUNNELGUARD_STOPPING)
                stopVpn()
            }
            return START_NOT_STICKY
        }

        // Default or ACTION_START or ACTION_UPDATE: Establish/Update VPN interface
        startForegroundServiceNotification()

        startMonitoring()

        // Listen to connectivity changes for dynamic fail-closed blocking only if NOT already registered
        if (!isCallbackRegistered) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
                isCallbackRegistered = true
                config.addLog("Network callback registered successfully.")
            } catch (e: Exception) {
                config.addLog("Error registering network callback: ${e.message}")
            }
        }

        if (!isScreenReceiverRegistered) {
            try {
                val screenFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                registerReceiver(screenReceiver, screenFilter)
                isScreenReceiverRegistered = true
                config.addLog("Screen/Wake receiver registered successfully.")
            } catch (e: Exception) {
                config.addLog("Error registering screen receiver: ${e.message}")
            }
        }

        serviceScope.launch { checkAndRunVpnRouting() }

        return START_STICKY
    }

    /**
     * Releases VPN resources and unregisters service callbacks when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isServiceStarting = false
        stopMonitoring()
        // Cancel all coroutines in serviceScope to prevent leaks
        serviceScope.coroutineContext[Job]?.cancel()
        if (isCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isCallbackRegistered = false
            } catch (e: Exception) {
                // Ignored
            }
        }
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
                isScreenReceiverRegistered = false
            } catch (e: Exception) {
                // Ignored
            }
        }
        stopVpn()
        config.addLog("VpnService destroyed")
    }

    /**
     * Handles system revocation of the VPN service when another VPN becomes active.
     */
    override fun onRevoke() {
        config.addLog("VpnService revoked by the system (another VPN started).")
        config.setLastDisconnectReason("System revoked VPN (another VPN started)")
        transitionTo(ServiceState.VPN_CONFLICT)
        closeVpnInterface()
        serviceScope.launch { checkAndRunVpnRouting() }
        super.onRevoke()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TunnelGuard Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "TunnelGuard Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority security alert notifications when protected applications are opened without VPN."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alertChannel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TunnelGuard Active")
            .setContentText("Monitoring per-app network fail-closed protection.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Evaluates the current VPN state and configures the fail-closed routing interface for protected applications.
     *
     * Closes the local interface when no applications are protected or an upstream VPN is active without Emergency Lock.
     * Establishes or reuses a local blocking interface when required, updating and broadcasting state changes as needed.
     */
    private fun checkAndRunVpnRouting() {
        synchronized(stateLock) {
            val simulated = config.isSimulatedVpnEnabled()
        val currentVpnState: VPNState
        var upstreamEvaluation: UpstreamVpnEvaluation? = null

        if (simulated) {
            // In simulation mode, read state from preferences
            currentVpnState = config.getVPNState()
            config.addLog("Checking VPN in Simulation Mode. Status: $currentVpnState")
        } else {
            val foregroundApp = config.getForegroundPackageName(this)
                ?.takeIf { config.isAppProtected(it) && it != packageName }
            val requiredCountry = config.getEffectiveVpnCountry(foregroundApp)
            val evaluation = if (vpnDetector is DefaultVpnDetector) {
                (vpnDetector as DefaultVpnDetector).evaluateUpstreamVpn(connectivityManager, requiredCountry)
            } else {
                when (vpnDetector.detectVpnState(connectivityManager)) {
                    VpnDetectionResult.VPN_DETECTED -> UpstreamVpnEvaluation.Valid()
                    VpnDetectionResult.VPN_NOT_DETECTED -> UpstreamVpnEvaluation.Missing
                    VpnDetectionResult.VPN_UNKNOWN -> UpstreamVpnEvaluation.Unknown
                }
            }
            logCountryFailureOnce(foregroundApp, evaluation)
            upstreamEvaluation = evaluation
            val prevState = config.getVPNState()
            currentVpnState = when (evaluation) {
                is UpstreamVpnEvaluation.Valid -> VPNState.PROTECTED
                is UpstreamVpnEvaluation.CountryMismatch, UpstreamVpnEvaluation.Missing, UpstreamVpnEvaluation.Unknown -> {
                    if (vpnInterface != null) VPNState.BLOCKED else VPNState.DISCONNECTED
                }
            }
            if ((prevState == VPNState.CONNECTED || prevState == VPNState.PROTECTED) && currentVpnState == VPNState.DISCONNECTED) {
                val reason = if (evaluation is UpstreamVpnEvaluation.CountryMismatch) {
                    "VPN country requirement not satisfied"
                } else {
                    "Loss of network connectivity"
                }
                config.setLastDisconnectReason(reason)
            }
            config.setVPNState(currentVpnState)
            config.addLog("Checking VPN in Real Mode. Status: $currentVpnState")
        }

        // Broadcaster for UI updates
        val broadcastIntent = Intent("com.tunnelguard.app.STATE_CHANGED").apply {
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)

        val isEmergencyLock = config.isEmergencyLockEnabled()

        // Check if user was redirected to turn on VPN and automatically launch target app when VPN is active and Emergency Lock is off
        if (!isEmergencyLock && (currentVpnState == VPNState.CONNECTED || currentVpnState == VPNState.PROTECTED)) {
            val pendingTarget = config.getPendingVpnRedirectTarget()
            if (pendingTarget != null) {
                config.clearPendingVpnRedirectTarget()
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pendingTarget)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        config.addLog("Auto-redirected to target application after VPN connection: $pendingTarget")
                    } else {
                        config.addLog("Could not find launch intent for pending target app: $pendingTarget", "WARN")
                    }
                } catch (e: Exception) {
                    config.addLog("Failed to auto-redirect to target app $pendingTarget: ${e.message}", "ERROR")
                }
            }
        }

        // Fetch selected applications for protection
        val protectedApps = config.getProtectedApps()
        if (protectedApps.isEmpty()) {
            config.addLog("No apps selected for protection. Closing local tunnel interface.")
            config.setLastDisconnectReason("No apps selected for protection")
            closeVpnInterface()
            transitionTo(ServiceState.NO_VPN)
            sendBroadcast(broadcastIntent)
            return
        }

        // --- PREVENT UNCONDITIONAL VPN TAKEOVER ---
        // Never compete for Android's single VPN slot when an unsuitable external VPN is active.
        if (upstreamEvaluation is UpstreamVpnEvaluation.CountryMismatch) {
            // Android only permits one VPN owner. Do not attempt to establish TunnelGuard's local
            // interface while the unsuitable external VPN owns that slot; state remains invalid
            // and the monitor drives the existing warning/redirect workflow.
            closeVpnInterface()
            transitionTo(ServiceState.VPN_CONFLICT)
            sendBroadcast(broadcastIntent)
            return
        } else if (isEmergencyLock) {
            config.addLog("Emergency Lock is ACTIVE. Forcing local blackhole block interface.")
        } else if (currentVpnState == VPNState.CONNECTED || currentVpnState == VPNState.PROTECTED) {
            config.addLog("Upstream VPN is CONNECTED/ACTIVE. Bypassing local tunnel block interface.")
            closeVpnInterface()
            transitionTo(ServiceState.UPSTREAM_VPN)
            sendBroadcast(broadcastIntent)
            return
        }

        // Avoid redundant rebuilding of the local block interface if already active and config hasn't changed
        if (vpnInterface != null && protectedApps == lastEstablishedApps && isEmergencyLock == lastEmergencyLock) {
            config.addLog("Routing check: local interface is already active and configuration has not changed. No-op.")
            transitionTo(ServiceState.TUNNELGUARD_ACTIVE)
            return
        }

        // Close previous interface before establishing a new one
        closeVpnInterface()

        var established = false

        // Attempt establishing with IPv6 support first
        try {
            val ipv6Builder = Builder()
                .setSession("TunnelGuardFailClosedTunnel")
                .addAddress(TunnelGuardConfig.TUNNEL_ADDRESS, TunnelGuardConfig.TUNNEL_PREFIX_LENGTH)
                .addAddress("2001:db8::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setMtu(1500)

            var addedAny = false
            for (app in protectedApps) {
                try {
                    ipv6Builder.addAllowedApplication(app)
                    addedAny = true
                } catch (e: Exception) {
                    config.addLog("Could not add allowed app on IPv6 builder: $app. Error: ${e.message}")
                }
            }

            if (addedAny) {
                val pfd = ipv6Builder.establish()
                if (pfd != null) {
                    vpnInterface = pfd
                    isTunnelEstablished = true
                    established = true
                    config.setIpv6ProtectionActive(true)
                    config.addLog("Local block interface (IPv4 + IPv6) established successfully. Fail-closed ACTIVE for protected apps.")
                }
            }
        } catch (e: Exception) {
            config.addLog("Failed to establish IPv6 block tunnel, falling back to IPv4-only: ${e.message}", "WARN")
        }

        // Fallback to IPv4-only if IPv6 establishment failed
        if (!established) {
            try {
                val ipv4Builder = Builder()
                    .setSession("TunnelGuardFailClosedTunnel")
                    .addAddress(TunnelGuardConfig.TUNNEL_ADDRESS, TunnelGuardConfig.TUNNEL_PREFIX_LENGTH)
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)

                var addedAny = false
                for (app in protectedApps) {
                    try {
                        ipv4Builder.addAllowedApplication(app)
                        addedAny = true
                    } catch (e: Exception) {
                        config.addLog("Could not add allowed app on IPv4 fallback builder: $app. Error: ${e.message}")
                    }
                }

                if (addedAny) {
                    val pfd = ipv4Builder.establish()
                    if (pfd != null) {
                        vpnInterface = pfd
                        isTunnelEstablished = true
                        established = true
                        config.setIpv6ProtectionActive(false)
                        config.addLog("Local block interface (IPv4-Only fallback) established successfully. Fail-closed ACTIVE for protected apps. IPv6 is UNPROTECTED.")
                    }
                }
            } catch (e: Exception) {
                isTunnelEstablished = false
                config.addLog("Failed to establish IPv4 fallback block tunnel: ${e.message}", "ERROR")
                config.setVPNState(VPNState.ERROR)
                val failureBroadcastIntent = Intent("com.tunnelguard.app.STATE_CHANGED").apply {
                    setPackage(packageName)
                }
                sendBroadcast(failureBroadcastIntent)
                closeVpnInterface()
                transitionTo(ServiceState.ERROR)
                return
            }
        }

        if (!established) {
            isTunnelEstablished = false
            config.addLog("Failed to establish any block tunnel (neither IPv6 nor IPv4-Only succeeded).", "ERROR")
            config.setVPNState(VPNState.ERROR)
            val failureBroadcastIntent = Intent("com.tunnelguard.app.STATE_CHANGED").apply {
                setPackage(packageName)
            }
            sendBroadcast(failureBroadcastIntent)
            closeVpnInterface()
            transitionTo(ServiceState.ERROR)
            return
        }

        lastEstablishedApps = protectedApps.toSet()
        lastEmergencyLock = isEmergencyLock
        if (!simulated) {
            config.setVPNState(VPNState.BLOCKED)
            val successBroadcastIntent = Intent("com.tunnelguard.app.STATE_CHANGED").apply {
                setPackage(packageName)
            }
            sendBroadcast(successBroadcastIntent)
        }
        transitionTo(ServiceState.TUNNELGUARD_ACTIVE)
        }
    }

    private fun logCountryFailureOnce(packageName: String?, evaluation: UpstreamVpnEvaluation) {
        val message = when (evaluation) {
            is UpstreamVpnEvaluation.CountryMismatch -> if (evaluation.detected == null) {
                "Unable to verify VPN exit country for ${packageName ?: "global policy"}. Required ${evaluation.required}. Failing closed."
            } else {
                "Protected app ${packageName ?: "global policy"} requires VPN country ${evaluation.required}. Active VPN country ${evaluation.detected} does not satisfy it. Failing closed."
            }
            else -> null
        }
        if (message != lastCountryFailureLog) {
            message?.let(config::addLogWarning)
            lastCountryFailureLog = message
        }
    }

    /**
     * Closes the active VPN interface and clears its cached routing configuration.
     */
    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            config.addLog("Error closing VPN interface: ${e.message}")
        } finally {
            vpnInterface = null
            isTunnelEstablished = false
            config.setIpv6ProtectionActive(false)
            lastEstablishedApps = null
            lastEmergencyLock = null
        }
    }

    /**
     * Stops VPN operation, clears its state, removes the foreground notification, and stops the service.
     */
    private fun stopVpn() {
        synchronized(stateLock) {
            stopMonitoring()
            closeVpnInterface()
            isServiceStarting = false
            val simulated = config.isSimulatedVpnEnabled()
            if (!simulated) {
                config.setVPNState(VPNState.DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            val broadcastIntent = Intent("com.tunnelguard.app.STATE_CHANGED").apply {
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
            transitionTo(ServiceState.NO_VPN)
            stopSelf()
        }
    }
}
