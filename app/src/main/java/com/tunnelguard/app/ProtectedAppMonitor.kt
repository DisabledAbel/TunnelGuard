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

    /**
     * Evaluates the current monitoring state and determines whether a warning should be triggered.
     *
     * @param connectivityManager The connectivity manager used to detect the VPN state.
     * @param lastForegroundApp The previously detected foreground package, used when the current package is unavailable.
     * @param wasVpnOn The VPN state from the previous evaluation.
     * @return The monitoring result, including the relevant package and VPN state.
     */
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

        val appCountry = config.getAppVpnCountry(currentApp)
        var isVpnOn = if (config.isSimulatedVpnEnabled()) {
            val state = config.getVPNState()
            state == VPNState.CONNECTED || state == VPNState.PROTECTED
        } else if (appCountry != null && vpnDetector is DefaultVpnDetector) {
            config.detectRealVpnCapabilities(
                connectivityManager,
                networkCountryResolver = { network -> vpnDetector.countryResolver.resolveCountry(network) },
                requiredCountryCode = appCountry
            ) == VpnDetectionResult.VPN_DETECTED
        } else {
            vpnDetector.detectVpnState(connectivityManager) == VpnDetectionResult.VPN_DETECTED
        }

        val isProtected = config.isAppProtected(currentApp) && currentApp != context.packageName
        if (isProtected) {
            if (isVpnOn && !config.isAppVpnCountryMatch(currentApp)) {
                config.addLogWarning("VPN country does not match $appCountry required by $currentApp")
                isVpnOn = false
            }
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
