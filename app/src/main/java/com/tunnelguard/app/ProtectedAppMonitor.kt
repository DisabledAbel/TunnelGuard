package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager

sealed class MonitoringCheckResult {
    abstract val foregroundPolicyChanged: Boolean
    data class TriggerWarning(
        val targetPackage: String,
        val isVpnOn: Boolean,
        override val foregroundPolicyChanged: Boolean
    ) : MonitoringCheckResult()
    data class NoAction(
        val currentApp: String?,
        val isVpnOn: Boolean?,
        override val foregroundPolicyChanged: Boolean
    ) : MonitoringCheckResult()
}

class ProtectedAppMonitor(
    private val config: TunnelGuardConfig,
    private val vpnDetector: VpnDetector = DefaultVpnDetector(config),
    private val transitionDetector: ForegroundPolicyTransitionDetector = ForegroundPolicyTransitionDetector()
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
        val foregroundPolicy = config.getForegroundVpnPolicy(detectedApp)
        val policyChanged = transitionDetector.observe(
            ForegroundPolicyObservation(detectedApp, foregroundPolicy)
        )
        val currentApp = detectedApp ?: lastForegroundApp
        if (currentApp == null) {
            return MonitoringCheckResult.NoAction(null, wasVpnOn, policyChanged)
        }

        var isVpnOn = if (config.isSimulatedVpnEnabled()) {
            val state = config.getVPNState()
            state == VPNState.CONNECTED || state == VPNState.PROTECTED
        } else if (vpnDetector is DefaultVpnDetector) {
            vpnDetector.evaluateUpstreamVpn(connectivityManager, foregroundPolicy).isValid
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
                return MonitoringCheckResult.TriggerWarning(currentApp, isVpnOn, policyChanged)
            }
        }

        return MonitoringCheckResult.NoAction(currentApp, isVpnOn, policyChanged)
    }
}
