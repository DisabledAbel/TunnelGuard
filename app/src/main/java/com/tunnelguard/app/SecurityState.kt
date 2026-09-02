package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService

enum class SecurityState {
    PROTECTED,  // VPN is active and securing traffic
    BLOCKING,   // Traffic is fail-closed blocked because no secure VPN is active
    INACTIVE,   // Protection is disabled
    CONNECTING, // VPN / tunnel is connecting/establishing
    ERROR,      // Tunnel failed to establish or another fatal error occurred
    UNPROTECTED_FAULT // Protection is supposed to be active, but is currently faulted/unprotected
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
        connectivityManager: ConnectivityManager?,
        vpnDetector: VpnDetector = DefaultVpnDetector(config)
    ): SecurityState {
        // If Emergency Lock is enabled
        if (config.isEmergencyLockEnabled()) {
            if (config.isSimulatedVpnEnabled()) {
                return SecurityState.BLOCKING
            }
            val vpnPrepared = VpnService.prepare(context) == null
            if (!vpnPrepared) {
                return SecurityState.UNPROTECTED_FAULT
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
        val foregroundApp = config.getForegroundPackageName(context)
        val foregroundPolicy = config.getForegroundVpnPolicy(foregroundApp)
        val upstreamValid = if (vpnDetector is DefaultVpnDetector) {
            vpnDetector.evaluateUpstreamVpn(
                connectivityManager,
                foregroundPolicy
            ).isValid
        } else {
            vpnDetector.detectVpnState(connectivityManager) == VpnDetectionResult.VPN_DETECTED
        }
        if (upstreamValid) {
            return SecurityState.PROTECTED
        }

        // Upstream VPN is NOT confirmed detected (VPN_NOT_DETECTED or VPN_UNKNOWN).
        // Protected traffic must remain BLOCKED (or UNPROTECTED_FAULT if permissions/service issue).
        if (isTunnelEstablished) {
            return SecurityState.BLOCKING
        }

        // Check if VPN permission has been revoked or not granted
        val vpnPrepared = VpnService.prepare(context) == null
        if (!vpnPrepared) {
            return SecurityState.UNPROTECTED_FAULT
        }

        // If service is running or starting but tunnel not yet established
        if (isServiceStarting || isServiceRunning) {
            return SecurityState.CONNECTING
        }

        return SecurityState.UNPROTECTED_FAULT
    }
}

data class ProtocolProtectionInfo(
    val ipv4Text: String,
    val ipv4ColorRes: Int,
    val ipv6Text: String,
    val ipv6ColorRes: Int
)

object ProtocolProtectionMapper {
    fun getInfo(state: SecurityState, isIpv6Active: Boolean, isDiagnostics: Boolean = false): ProtocolProtectionInfo {
        val blockedSuffix = if (isDiagnostics) " (Fail-Closed active)" else " (Fail-Closed)"
        return when (state) {
            SecurityState.PROTECTED -> ProtocolProtectionInfo(
                "Protected (VPN Routing)", R.color.status_active,
                "Protected (VPN Routing)", R.color.status_active
            )
            SecurityState.BLOCKING -> {
                val ipv6Txt = if (isIpv6Active) "Blocked$blockedSuffix" else "Unprotected (IPv6 Unsupported)"
                val ipv6Col = if (isIpv6Active) R.color.status_blocking else R.color.status_disconnected
                ProtocolProtectionInfo(
                    "Blocked$blockedSuffix", R.color.status_blocking,
                    ipv6Txt, ipv6Col
                )
            }
            SecurityState.CONNECTING -> ProtocolProtectionInfo(
                "Establishing...", R.color.status_connecting,
                "Establishing...", R.color.status_connecting
            )
            SecurityState.ERROR -> ProtocolProtectionInfo(
                "Error", R.color.status_disconnected,
                "Error", R.color.status_disconnected
            )
            SecurityState.UNPROTECTED_FAULT -> ProtocolProtectionInfo(
                "Unprotected (Fault)", R.color.status_disconnected,
                "Unprotected (Fault)", R.color.status_disconnected
            )
            else -> ProtocolProtectionInfo(
                "Unprotected", R.color.text_secondary,
                "Unprotected", R.color.text_secondary
            )
        }
    }
}
