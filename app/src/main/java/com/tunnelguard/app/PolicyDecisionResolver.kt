package com.tunnelguard.app

/** The enforcement outcome shared by runtime monitoring and read-only diagnostics. */
enum class PolicyDecision {
    ALLOWED, PROTECTED, BLOCKED_FAIL_CLOSED, TEMPORARILY_ALLOWED, VPN_REQUIRED,
    VPN_PROVIDER_UNAVAILABLE, VPN_CONFLICT, COUNTRY_MISMATCH, COUNTRY_UNKNOWN,
    POLICY_UNAVAILABLE, EMERGENCY_LOCKED
}

data class PolicyDecisionInput(
    val protectedApp: Boolean,
    val policy: EffectiveVpnPolicy?,
    val upstream: UpstreamVpnEvaluation,
    val emergencyLock: Boolean,
    val temporaryOverride: Boolean,
    val providerAvailable: Boolean? = null
)

data class PolicyDecisionResult(val decision: PolicyDecision, val reason: String)

/**
 * Authoritative, pure final-decision layer. Both enforcement and Policy Tester feed the same
 * effective policy and upstream observation into this resolver, preventing diagnostic drift.
 */
object PolicyDecisionResolver {
    fun resolve(input: PolicyDecisionInput): PolicyDecisionResult {
        if (!input.protectedApp) return result(PolicyDecision.ALLOWED,
            "This app is not included in the active protection profile.")
        val policy = input.policy ?: return result(PolicyDecision.POLICY_UNAVAILABLE,
            "The effective policy could not be read, so its result is unavailable.")
        if (input.emergencyLock) return result(PolicyDecision.EMERGENCY_LOCKED,
            "Emergency Lock is active and takes precedence over temporary overrides.")
        if (input.temporaryOverride) return result(PolicyDecision.TEMPORARILY_ALLOWED,
            "This app has an active temporary override.")
        if (!policy.requireVpn) return result(PolicyDecision.ALLOWED,
            "The active policy does not require an upstream VPN for this app.")
        if (input.providerAvailable == false && !input.upstream.isValid)
            return result(PolicyDecision.VPN_PROVIDER_UNAVAILABLE,
            "The configured VPN provider is not installed or cannot be launched.")
        return when (val upstream = input.upstream) {
            is UpstreamVpnEvaluation.Valid -> result(PolicyDecision.PROTECTED,
                "The current upstream VPN satisfies the effective policy.")
            UpstreamVpnEvaluation.Missing -> result(PolicyDecision.VPN_REQUIRED,
                "This app is protected and no valid upstream VPN is active.")
            is UpstreamVpnEvaluation.CountryMismatch -> if (upstream.detected == null)
                result(PolicyDecision.COUNTRY_UNKNOWN,
                    "VPN country could not be verified, so TunnelGuard is failing closed.")
            else result(PolicyDecision.COUNTRY_MISMATCH,
                "The VPN is active, but ${upstream.required} is required and ${upstream.detected} was detected.")
            UpstreamVpnEvaluation.ForegroundUnknown, UpstreamVpnEvaluation.Unknown ->
                result(PolicyDecision.BLOCKED_FAIL_CLOSED,
                    "The upstream VPN state is unknown or unverifiable, so TunnelGuard is failing closed.")
        }
    }

    private fun result(decision: PolicyDecision, reason: String) = PolicyDecisionResult(decision, reason)
}
