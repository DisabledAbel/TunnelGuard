package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager

sealed class MonitoringCheckResult {
    data class TriggerWarning(val targetPackage: String, val isVpnOn: Boolean) : MonitoringCheckResult()
    data class NoAction(val currentApp: String?, val isVpnOn: Boolean?) : MonitoringCheckResult()
}

class ProtectedAppMonitor(
    private val config: TunnelGuardConfig,
    private val vpnDetector: VpnDetector = DefaultVpnDetector(config)
) {

    fun evaluateMonitoringState(
        context: Context,
        connectivityManager: ConnectivityManager?,
        lastForegroundApp: String?,
        wasVpnOn: Boolean?
    ): MonitoringCheckResult {
        val detectedApp = config.getForegroundPackageName(context)
        val currentApp = detectedApp ?: lastForegroundApp
        if (currentApp == null) {
            return MonitoringCheckResult.NoAction(null, wasVpnOn)
        }

        val isVpnOn = if (config.isSimulatedVpnEnabled()) {
            val state = config.getVPNState()
            state == VPNState.CONNECTED || state == VPNState.PROTECTED
        } else {
            vpnDetector.detectVpnState(connectivityManager) == VpnDetectionResult.VPN_DETECTED
        }

        val isProtected = config.isAppProtected(currentApp) && currentApp != context.packageName
        if (isProtected) {
            val isSuppressed = TunnelGuardVpnService.isPackageSuppressed(currentApp)
            val shouldTrigger = TunnelGuardVpnService.shouldTriggerWarning(
                currentApp = currentApp,
                lastForegroundApp = lastForegroundApp,
                isVpnOn = isVpnOn,
                wasVpnOn = wasVpnOn,
                isSuppressed = isSuppressed
            )

            if (shouldTrigger) {
                return MonitoringCheckResult.TriggerWarning(currentApp, isVpnOn)
            }
        }

        return MonitoringCheckResult.NoAction(currentApp, isVpnOn)
    }
}
