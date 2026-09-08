package com.tunnelguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ContextCompat
import androidx.core.app.NotificationCompat

/**
 * Owns protection observation independently of Android's single VPN slot.
 *
 * A [VpnService] can be revoked (and subsequently destroyed) as soon as another VPN takes the
 * slot.  This ordinary special-use foreground service therefore observes VPN transport changes
 * and starts the local blocker only after the slot is vacant and consent is still valid.
 */
class ProtectionMonitorService : Service() {
    private lateinit var config: TunnelGuardConfig
    private lateinit var connectivity: ConnectivityManager
    private var callbackRegistered = false
    private var stopping = false
    private var recoveryInFlight = false
    private var recoveryAttempts = 0
    private val lifecycleLock = Any()
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private val recoveryCheck = Runnable {
        val shouldRetry = synchronized(lifecycleLock) {
            when {
                stopping || !protectionRequested() -> false
                TunnelGuardVpnService.isTunnelEstablished -> {
                    recoveryInFlight = false
                    recoveryAttempts = 0
                    false
                }
                TunnelGuardVpnService.currentServiceState == ServiceState.ERROR &&
                    recoveryAttempts < MAX_RECOVERY_ATTEMPTS -> {
                    recoveryInFlight = false
                    true
                }
                else -> false
            }
        }
        if (shouldRetry) reevaluate("Local blocking recovery failed; retrying")
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = reevaluate("VPN became available")
        override fun onLost(network: Network) = reevaluate("VPN was lost")
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            reevaluate("VPN capabilities changed")
    }

    override fun onCreate() {
        super.onCreate()
        config = TunnelGuardConfig(this)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lifecycleLock) {
            if (intent?.action == ACTION_STOP || !protectionRequested()) {
                stopping = true
                recoveryInFlight = false
                recoveryHandler.removeCallbacks(recoveryCheck)
                stopSelf()
                return START_NOT_STICKY
            }
            stopping = false
            if (!isMonitoringRunning) {
                startForeground(NOTIFICATION_ID, notification("Watching VPN connection changes"))
                isMonitoringRunning = true
            }
            registerVpnObserver()
        }
        reevaluate("Monitoring started")
        return START_STICKY
    }

    private fun stopIfNoLongerRequested(): Boolean = synchronized(lifecycleLock) {
        if (stopping || !protectionRequested()) {
            stopping = true
            recoveryInFlight = false
            recoveryHandler.removeCallbacks(recoveryCheck)
            stopSelf()
            true
        } else {
            false
        }
    }

    private fun registerVpnObserver() {
        if (callbackRegistered) return
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(),
            vpnCallback
        )
        callbackRegistered = true
        observerRegistrationCount++
        config.addLog("Independent VPN transition observer registered.")
    }

    private fun reevaluate(reason: String) {
        if (stopIfNoLongerRequested()) return
        val upstreamPresent = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        config.addLog("Protection observer: $reason; upstream VPN present=$upstreamPresent.")
        ProfileAutomationManager.onNetworkChanged(this)
        if (TunnelGuardVpnService.isTunnelEstablished) {
            recoveryInFlight = false
            recoveryAttempts = 0
            TunnelGuardVpnService.updateServiceState(ServiceState.TUNNELGUARD_ACTIVE)
            updateNotification("Local fail-closed blocking is active")
            return
        }
        if (upstreamPresent) {
            recoveryInFlight = false
            recoveryAttempts = 0
            val foreground = config.getForegroundPackageName(this)
            val evaluation = DefaultVpnDetector(config).evaluateUpstreamVpn(
                connectivity, config.getForegroundVpnPolicy(foreground)
            )
            if (evaluation.isValid) {
                TunnelGuardVpnService.updateServiceState(ServiceState.UPSTREAM_VPN)
                config.setVPNState(VPNState.PROTECTED)
                updateNotification("External VPN satisfies policy; monitoring continues")
            } else {
                TunnelGuardVpnService.updateServiceState(ServiceState.VPN_CONFLICT)
                config.setVPNState(VPNState.DISCONNECTED)
                updateNotification("External VPN does not satisfy the current policy")
            }
            broadcastState()
            return
        }
        if (VpnService.prepare(this) != null) {
            recoveryInFlight = false
            recoveryAttempts = 0
            TunnelGuardVpnService.updateServiceState(ServiceState.PERMISSION_REQUIRED)
            config.setVPNState(VPNState.ERROR)
            config.setLastDisconnectReason("VPN permission is required to restore local blocking")
            updateNotification("Unprotected: open TunnelGuard and select protection to restore permission")
            broadcastState()
            return
        }
        synchronized(lifecycleLock) {
            // Serialize the last eligibility check and dispatch with ACTION_STOP. A callback that
            // began before protection was disabled must never enqueue a late recovery.
            if (stopping || !protectionRequested()) return
            if (!recoveryInFlight && !TunnelGuardVpnService.isTunnelEstablished) {
                recoveryInFlight = true
                recoveryAttempts++
                TunnelGuardVpnService.updateServiceState(ServiceState.TUNNELGUARD_STARTING)
                val recover = Intent(this, TunnelGuardVpnService::class.java)
                    .setAction(TunnelGuardVpnService.ACTION_RECOVER)
                ContextCompat.startForegroundService(this, recover)
                updateNotification("Restoring local fail-closed blocking")
                recoveryHandler.removeCallbacks(recoveryCheck)
                recoveryHandler.postDelayed(recoveryCheck, RECOVERY_RETRY_DELAY_MS)
            }
        }
    }

    private fun protectionRequested() = config.isProtectionEnabled() || config.isEmergencyLockEnabled()

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Protection monitoring", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("TunnelGuard monitoring")
        .setContentText(message)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun broadcastState() = sendBroadcast(Intent("com.tunnelguard.app.STATE_CHANGED").setPackage(packageName))

    override fun onDestroy() {
        isMonitoringRunning = false
        recoveryInFlight = false
        recoveryHandler.removeCallbacks(recoveryCheck)
        if (callbackRegistered) {
            connectivity.unregisterNetworkCallback(vpnCallback)
            callbackRegistered = false
            observerRegistrationCount--
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.tunnelguard.app.MONITOR_START"
        const val ACTION_STOP = "com.tunnelguard.app.MONITOR_STOP"
        private const val CHANNEL_ID = "TunnelGuardMonitorChannel"
        private const val NOTIFICATION_ID = 1003
        private const val RECOVERY_RETRY_DELAY_MS = 5_000L
        private const val MAX_RECOVERY_ATTEMPTS = 3

        @Volatile var isMonitoringRunning = false
            private set
        @Volatile internal var observerRegistrationCount = 0

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, ProtectionMonitorService::class.java).setAction(ACTION_START)
        )

        fun stop(context: Context) {
            context.startService(Intent(context, ProtectionMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
