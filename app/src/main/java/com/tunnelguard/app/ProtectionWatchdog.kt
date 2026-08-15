package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager

class ProtectionWatchdog(
    private val context: Context,
    private val config: TunnelGuardConfig,
    private val vpnDetector: VpnDetector = DefaultVpnDetector(config)
) {
    data class WatchdogResult(
        val isHealthy: Boolean,
        val issueDescription: String? = null
    )

    fun verifyProtectionHealth(
        isServiceRunning: Boolean,
        isServiceStarting: Boolean,
        isTunnelEstablished: Boolean,
        connectivityManager: ConnectivityManager?
    ): WatchdogResult {
        // If protection is not enabled and emergency lock is off, no watchdog enforcement needed
        if (!config.isProtectionEnabled() && !config.isEmergencyLockEnabled()) {
            return WatchdogResult(true)
        }

        val currentState = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = isServiceRunning,
            isServiceStarting = isServiceStarting,
            isTunnelEstablished = isTunnelEstablished,
            connectivityManager = connectivityManager
        )

        // Unprotected fault indicates missing VPN preparation / service died when protection was supposed to be active
        if (currentState == SecurityState.UNPROTECTED_FAULT) {
            config.addLog("Watchdog anomaly detected: Protection enabled but state is UNPROTECTED_FAULT.", "ERROR")
            return WatchdogResult(false, "Protection is enabled but TunnelGuard service or VPN permission is unready.")
        }

        // If protection is active in real mode without simulated VPN, check if local block tunnel is missing when no upstream VPN exists
        if (!config.isSimulatedVpnEnabled() && isServiceRunning && !isTunnelEstablished) {
            val vpnState = vpnDetector.detectVpnState(connectivityManager)
            if (vpnState != VpnDetectionResult.VPN_DETECTED) {
                config.addLog("Watchdog anomaly detected: Service running, no upstream VPN detected, but local tunnel is NOT established.", "ERROR")
                return WatchdogResult(false, "Local blackhole tunnel interface is missing while service is active.")
            }
        }

        return WatchdogResult(true)
    }
}
