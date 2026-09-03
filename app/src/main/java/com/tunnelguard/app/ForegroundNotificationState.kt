package com.tunnelguard.app

import java.util.Locale

enum class ForegroundNotificationType {
    STARTING, PROTECTED, BLOCKING, CONNECTING, COUNTRY_MISMATCH, EMERGENCY_LOCK, PROBLEM
}

data class ForegroundNotificationState(
    val type: ForegroundNotificationType,
    val title: String,
    val message: String,
    val foregroundApp: String? = null,
    val protectedAppCount: Int? = null
)

data class ForegroundNotificationFacts(
    val starting: Boolean = false,
    val protectedAppCount: Int = 0,
    val foregroundAppLabel: String? = null,
    val upstreamEvaluation: UpstreamVpnEvaluation? = null,
    val blocking: Boolean = false,
    val emergencyLock: Boolean = false,
    val autoConnecting: Boolean = false,
    val problem: String? = null
)

/** Pure rendering policy for the foreground-service notification. */
object ForegroundNotificationStateSelector {
    fun select(facts: ForegroundNotificationFacts): ForegroundNotificationState {
        val count = facts.protectedAppCount
        facts.problem?.takeIf { it.isNotBlank() }?.let {
            return state(ForegroundNotificationType.PROBLEM, "Protection Problem", it, facts, count)
        }
        if (facts.starting) {
            return state(ForegroundNotificationType.STARTING, "Starting", "Checking VPN and protection state…", facts, count)
        }
        if (facts.emergencyLock) {
            return state(ForegroundNotificationType.EMERGENCY_LOCK, "Emergency Lock", "Blocking $count protected ${apps(count)}", facts, count)
        }
        val mismatch = facts.upstreamEvaluation as? UpstreamVpnEvaluation.CountryMismatch
        if (mismatch != null) {
            val app = facts.foregroundAppLabel ?: "Protected app"
            val required = countryName(mismatch.required)
            val detected = mismatch.detected?.let(::countryName)
            val message = if (detected == null) "$app requires $required" else "$app requires $required • VPN is $detected"
            return state(ForegroundNotificationType.COUNTRY_MISMATCH, "Wrong VPN Location", message, facts, count)
        }
        if (facts.autoConnecting) {
            val target = facts.foregroundAppLabel ?: "a protected app"
            return state(ForegroundNotificationType.CONNECTING, "Connecting VPN", "Waiting for VPN protection for $target", facts, count)
        }
        if (facts.upstreamEvaluation is UpstreamVpnEvaluation.Valid) {
            return state(ForegroundNotificationType.PROTECTED, "Protected", "$count protected ${apps(count)}", facts, count)
        }
        val message = facts.foregroundAppLabel?.let { "Blocking $it to prevent a network leak" }
            ?: "Protected app traffic is blocked"
        return state(ForegroundNotificationType.BLOCKING, "Blocking", message, facts, count)
    }

    private fun state(type: ForegroundNotificationType, suffix: String, message: String, facts: ForegroundNotificationFacts, count: Int) =
        ForegroundNotificationState(type, "TunnelGuard • $suffix", message, facts.foregroundAppLabel, count)

    private fun apps(count: Int) = if (count == 1) "app" else "apps"

    private fun countryName(code: String): String {
        val normalized = code.trim().uppercase(Locale.US)
        return Locale("", normalized).getDisplayCountry(Locale.US).takeIf { it.isNotBlank() } ?: normalized
    }
}

/** Returns true only when a notification needs to be posted. */
class ForegroundNotificationChangeTracker {
    private var lastState: ForegroundNotificationState? = null
    fun shouldNotify(state: ForegroundNotificationState): Boolean {
        if (state == lastState) return false
        lastState = state
        return true
    }
}
