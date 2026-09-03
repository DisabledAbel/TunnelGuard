package com.tunnelguard.app

import java.util.Locale

enum class ForegroundNotificationType {
    STARTING, EMPTY, PROTECTED, BLOCKING, CONNECTING, COUNTRY_MISMATCH, EMERGENCY_LOCK, PROBLEM
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
    /**
     * Selects the notification state that best represents the current protection conditions.
     *
     * @param facts The inputs used to determine the notification state.
     * @return The selected foreground notification state.
     */
    fun select(facts: ForegroundNotificationFacts): ForegroundNotificationState {
        val count = facts.protectedAppCount
        facts.problem?.takeIf { it.isNotBlank() }?.let {
            return state(ForegroundNotificationType.PROBLEM, "Protection Problem", it, facts, count)
        }
        if (facts.starting) {
            return state(ForegroundNotificationType.STARTING, "Starting", "Checking VPN and protection state…", facts, count)
        }
        if (count == 0) {
            return state(ForegroundNotificationType.EMPTY, "No Protected Apps", "Choose apps to protect", facts, count)
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

    /**
         * Creates a notification state with the standard title prefix and foreground app context.
         *
         * @param type The notification type.
         * @param suffix The title suffix.
         * @param message The notification message.
         * @param facts The facts providing the foreground app label.
         * @param count The number of protected apps.
         * @return The rendered foreground notification state.
         */
        private fun state(type: ForegroundNotificationType, suffix: String, message: String, facts: ForegroundNotificationFacts, count: Int) =
        ForegroundNotificationState(type, "TunnelGuard • $suffix", message, facts.foregroundAppLabel, count)

    /**
 * Chooses the singular or plural label for a protected-app count.
 *
 * @param count The number of protected apps.
 * @return "app" when the count is one; otherwise, "apps".
 */
private fun apps(count: Int) = if (count == 1) "app" else "apps"

    /**
     * Converts a country code to its localized country name.
     *
     * @param code The country code to convert.
     * @return The localized country name, or the normalized country code when no name is available.
     */
    private fun countryName(code: String): String {
        val normalized = code.trim().uppercase(Locale.US)
        return Locale("", normalized).getDisplayCountry(Locale.US).takeIf { it.isNotBlank() } ?: normalized
    }
}

/** Returns true only when a notification needs to be posted. */
class ForegroundNotificationChangeTracker {
    private var lastRenderedContent: Pair<String, String>? = null

    /**
     * Determines whether the notification content has changed since the previous check.
     *
     * @param state The notification state whose title and message are compared.
     * @return `true` if the title or message changed, `false` otherwise.
     */
    fun shouldNotify(state: ForegroundNotificationState): Boolean {
        val renderedContent = state.title to state.message
        if (renderedContent == lastRenderedContent) return false
        lastRenderedContent = renderedContent
        return true
    }
}
