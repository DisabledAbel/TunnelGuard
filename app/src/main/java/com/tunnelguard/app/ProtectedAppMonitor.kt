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
        TemporaryOverrideManager.onForegroundChanged(context, detectedApp)
        if (detectedApp != null && detectedApp != lastForegroundApp) {
            val timeline = ProtectionTimelineRepository(context)
            if (lastForegroundApp != null && config.isAppProtected(lastForegroundApp)) timeline.record(
                ProtectionEventType.APP_BACKGROUND, ProtectionEventSeverity.INFO, "Protected app left foreground",
                "$lastForegroundApp is no longer in the foreground.", packageName = lastForegroundApp,
                deduplicationKey = "background:$lastForegroundApp")
            if (config.isAppProtected(detectedApp)) timeline.record(
                ProtectionEventType.APP_FOREGROUND, ProtectionEventSeverity.INFO, "Protected app entered foreground",
                "$detectedApp is now in the foreground.", packageName = detectedApp,
                deduplicationKey = "foreground:$detectedApp")
        }
        if (currentApp == null) {
            return MonitoringCheckResult.NoAction(null, wasVpnOn, policyChanged)
        }

        val effectivePolicy = config.resolveEffectiveVpnPolicy(currentApp, config.isEmergencyLockEnabled())

        val upstreamObservation = if (config.isSimulatedVpnEnabled()) {
            val state = config.getVPNState()
            if (state == VPNState.CONNECTED || state == VPNState.PROTECTED) UpstreamVpnEvaluation.Valid()
            else UpstreamVpnEvaluation.Missing
        } else if (vpnDetector is DefaultVpnDetector) {
            vpnDetector.evaluateUpstreamVpn(connectivityManager, foregroundPolicy)
        } else {
            when (vpnDetector.detectVpnState(connectivityManager)) {
                VpnDetectionResult.VPN_DETECTED -> UpstreamVpnEvaluation.Valid()
                VpnDetectionResult.VPN_NOT_DETECTED -> UpstreamVpnEvaluation.Missing
                VpnDetectionResult.VPN_UNKNOWN -> UpstreamVpnEvaluation.Unknown
            }
        }
        val isVpnOn = upstreamObservation.isValid

        val isProtected = config.isAppProtected(currentApp) && currentApp != context.packageName
        val emergencyLock = config.isEmergencyLockEnabled()
        val hasOverride = TemporaryOverrideManager.getActiveOverride(context, currentApp, emergencyLock) != null
        val enforcementDecision = PolicyDecisionResolver.resolve(PolicyDecisionInput(
            isProtected, effectivePolicy, upstreamObservation,
            emergencyLock, hasOverride
        )).decision
        if (enforcementDecision == PolicyDecision.ALLOWED ||
            enforcementDecision == PolicyDecision.TEMPORARILY_ALLOWED) {
            return MonitoringCheckResult.NoAction(currentApp, true, policyChanged)
        }
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
