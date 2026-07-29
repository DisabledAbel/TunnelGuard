package com.tunnelguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

class TunnelGuardVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var config: TunnelGuardConfig
    private lateinit var connectivityManager: ConnectivityManager

    // Callback registration tracking to avoid multiple registrations across repeated ACTION_UPDATE commands
    private var isCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            checkAndRunVpnRouting()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            checkAndRunVpnRouting()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            checkAndRunVpnRouting()
        }
    }

    companion object {
        const val ACTION_START = "com.tunnelguard.app.START"
        const val ACTION_STOP = "com.tunnelguard.app.STOP"
        const val ACTION_UPDATE = "com.tunnelguard.app.UPDATE"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "TunnelGuardVpnChannel"

        // Global check utility to find if VpnService is active (for UI binding)
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        config = TunnelGuardConfig(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        config.addLog("VpnService received action: $action")

        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // Default or ACTION_START or ACTION_UPDATE: Establish/Update VPN interface
        startForegroundServiceNotification()

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

        checkAndRunVpnRouting()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (isCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isCallbackRegistered = false
            } catch (e: Exception) {
                // Ignored
            }
        }
        stopVpn()
        config.addLog("VpnService destroyed")
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TunnelGuard Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
     * Checks the current physical and simulated connection states and starts or updates the routing interface.
     */
    @Synchronized
    private fun checkAndRunVpnRouting() {
        val simulated = config.isSimulatedVpnEnabled()
        val currentVpnState: VPNState

        if (simulated) {
            // In simulation mode, read state from preferences
            currentVpnState = config.getVPNState()
            config.addLog("Checking VPN in Simulation Mode. Status: $currentVpnState")
        } else {
            // Check real network capabilities for TRANSPORT_VPN (to detect upstream VPN tunnels)
            val isUpstreamVpnConnected = detectRealVpnCapabilities()
            currentVpnState = if (isUpstreamVpnConnected) {
                VPNState.CONNECTED
            } else {
                VPNState.DISCONNECTED
            }
            config.setVPNState(currentVpnState)
            config.addLog("Checking VPN in Real Mode. Status: $currentVpnState")
        }

        // Broadcaster for UI updates
        val broadcastIntent = Intent("com.tunnelguard.app.STATE_CHANGED")
        sendBroadcast(broadcastIntent)

        // Fetch selected applications for protection
        val protectedApps = config.getProtectedApps()
        if (protectedApps.isEmpty()) {
            config.addLog("No apps selected for protection. Closing local tunnel interface.")
            closeVpnInterface()
            return
        }

        // --- PREVENT UNCONDITIONAL VPN TAKEOVER ---
        // If the upstream VPN is active/connected, we MUST NOT establish our local VpnService.
        // Doing so would terminate the other active VPN connection.
        // Instead, we close our local block interface to allow the apps' traffic to flow freely
        // through the active upstream VPN!
        if (currentVpnState == VPNState.CONNECTED || currentVpnState == VPNState.PROTECTED) {
            config.addLog("Upstream VPN is CONNECTED/ACTIVE. Bypassing local tunnel block interface.")
            closeVpnInterface()
            return
        }

        // If upstream is DISCONNECTED/BLOCKING, establish local VpnService and direct all packets
        // of allowed (protected) apps into it without forwarding them (blackholing/fail-closed block).
        val builder = Builder()
            .setSession("TunnelGuardFailClosedTunnel")
            .addAddress("10.0.0.1", 24)
            .addRoute("0.0.0.0", 0) // Capture all IPv4 traffic of the allowed applications
            .setMtu(1500)

        // Add each protected application to the VPN tunnel
        var addedAny = false
        for (app in protectedApps) {
            try {
                builder.addAllowedApplication(app)
                addedAny = true
            } catch (e: Exception) {
                config.addLog("Could not add allowed app: $app. Error: ${e.message}")
            }
        }

        if (!addedAny) {
            config.addLog("No valid protected apps could be added to VPN tunnel.")
            closeVpnInterface()
            return
        }

        // Close previous interface before establishing a new one
        closeVpnInterface()

        try {
            vpnInterface = builder.establish()
            config.addLog("Local block interface established successfully. Fail-closed ACTIVE for protected apps.")
        } catch (e: Exception) {
            config.addLog("Failed to establish VPN Interface: ${e.message}")
            config.setVPNState(VPNState.ERROR)
        }
    }

    private fun detectRealVpnCapabilities(): Boolean {
        try {
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue

                // Exclude the local VPN interface created by our own service to prevent self-detection feedback loops
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (caps.ownerUid == Process.myUid()) {
                        continue // Skip networks owned by this app
                    }
                } else {
                    // Pre-Q fallback: use link properties / interface name check
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val interfaceName = linkProperties?.interfaceName ?: ""
                    if (interfaceName.contains("tun") && vpnInterface != null) {
                        continue // Skip our own local interface
                    }
                }

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true
                }
            }
        } catch (e: Exception) {
            config.addLog("Error detecting active VPN capabilities: ${e.message}")
        }
        return false
    }

    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            config.addLog("Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
    }

    private fun stopVpn() {
        closeVpnInterface()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }
}
