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
import android.os.IBinder
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
        isMonitoringRunning = true
        startForeground(NOTIFICATION_ID, notification("Watching VPN connection changes"))
        registerVpnObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !protectionRequested()) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        reevaluate("Monitoring started")
        return START_STICKY
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
        if (stopping || !protectionRequested()) return
        val upstreamPresent = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        config.addLog("Protection observer: $reason; upstream VPN present=$upstreamPresent.")
        ProfileAutomationManager.onNetworkChanged(this)
        if (TunnelGuardVpnService.isTunnelEstablished) {
            recoveryInFlight = false
            TunnelGuardVpnService.updateServiceState(ServiceState.TUNNELGUARD_ACTIVE)
            updateNotification("Local fail-closed blocking is active")
            return
        }
        if (upstreamPresent) {
            recoveryInFlight = false
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
            TunnelGuardVpnService.updateServiceState(ServiceState.PERMISSION_REQUIRED)
            config.setVPNState(VPNState.ERROR)
            config.setLastDisconnectReason("VPN permission is required to restore local blocking")
            updateNotification("Unprotected: open TunnelGuard and select protection to restore permission")
            broadcastState()
            return
        }
        if (!recoveryInFlight && !TunnelGuardVpnService.isTunnelEstablished) {
            recoveryInFlight = true
            TunnelGuardVpnService.updateServiceState(ServiceState.TUNNELGUARD_STARTING)
            val recover = Intent(this, TunnelGuardVpnService::class.java)
                .setAction(TunnelGuardVpnService.ACTION_RECOVER)
            ContextCompat.startForegroundService(this, recover)
            updateNotification("Restoring local fail-closed blocking")
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
