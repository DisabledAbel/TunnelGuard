package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService

enum class SecurityState {
    PROTECTED,  // VPN is active and securing traffic
    BLOCKING,   // Traffic is fail-closed blocked because no secure VPN is active
    INACTIVE,   // Protection is disabled
    CONNECTING, // VPN / tunnel is connecting/establishing
    ERROR       // Tunnel failed to establish or another fatal error occurred
}

object SecurityStateMachine {

    /**
     * Computes the deterministic overall security state of the application.
     */
    fun getSecurityState(
        context: Context,
        config: TunnelGuardConfig,
        isServiceRunning: Boolean,
        isServiceStarting: Boolean,
        isTunnelEstablished: Boolean,
        connectivityManager: ConnectivityManager?
    ): SecurityState {
        // If Emergency Lock is enabled
        if (config.isEmergencyLockEnabled()) {
            if (config.isSimulatedVpnEnabled()) {
                return SecurityState.BLOCKING
            }
            return if (isTunnelEstablished) {
                SecurityState.BLOCKING
            } else if (config.getVPNState() == VPNState.ERROR) {
                SecurityState.ERROR
            } else {
                SecurityState.CONNECTING
            }
        }

        // If Protection is disabled
        if (!config.isProtectionEnabled()) {
            return SecurityState.INACTIVE
        }

        // Protection is enabled, check for errors
        if (config.getVPNState() == VPNState.ERROR) {
            return SecurityState.ERROR
        }

        // Simulation Mode check
        if (config.isSimulatedVpnEnabled()) {
            val simState = config.getVPNState()
            return if (simState == VPNState.PROTECTED) {
                SecurityState.PROTECTED
            } else {
                SecurityState.BLOCKING
            }
        }

        // Real Mode check
        val isUpstreamVpnActive = config.detectRealVpnCapabilities(connectivityManager)
        if (isUpstreamVpnActive) {
            return SecurityState.PROTECTED
        }

        // Upstream VPN is not connected, check if local block tunnel is active
        if (isTunnelEstablished) {
            return SecurityState.BLOCKING
        }

        // If service is running or starting but tunnel not yet established
        if (isServiceStarting || isServiceRunning) {
            return SecurityState.CONNECTING
        }

        // Check if VPN permission has been revoked or not granted
        val vpnPrepared = VpnService.prepare(context) == null
        if (!vpnPrepared) {
            return SecurityState.ERROR
        }

        return SecurityState.CONNECTING
    }
}
