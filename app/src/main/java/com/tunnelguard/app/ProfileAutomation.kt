package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

enum class ProfileRuleCondition(val label: String) {
    WIFI_CONNECTED("Wi-Fi Connected"), ETHERNET_CONNECTED("Ethernet Connected"),
    VPN_CONNECTED("VPN Connected"), VPN_DISCONNECTED("VPN Disconnected")
}

data class ProfileSwitchRule(
    val id: String,
    val condition: ProfileRuleCondition,
    val profileId: String,
    val enabled: Boolean = true,
    val priority: Int = 0
)

data class ProfileNetworkState(val wifi: Boolean, val ethernet: Boolean, val upstreamVpn: Boolean?) {
    /**
     * Determines whether the network state satisfies the specified condition.
     *
     * @param condition The network condition to evaluate.
     * @return `true` if the state satisfies the condition, `false` otherwise.
     */
    fun matches(condition: ProfileRuleCondition) = when (condition) {
        ProfileRuleCondition.WIFI_CONNECTED -> wifi
        ProfileRuleCondition.ETHERNET_CONNECTED -> ethernet
        ProfileRuleCondition.VPN_CONNECTED -> upstreamVpn == true
        ProfileRuleCondition.VPN_DISCONNECTED -> upstreamVpn == false
    }
}

object ProfileRuleEvaluator {
    /**
             * Selects the highest-priority enabled rule that matches the network state and targets a valid profile.
             *
             * @param rules The candidate profile-switching rules.
             * @param state The current network state.
             * @param validProfiles The profile IDs that can be selected.
             * @return The matching rule with the lowest priority and ID, or `null` if no rule matches.
             */
            fun match(rules: List<ProfileSwitchRule>, state: ProfileNetworkState, validProfiles: Set<String>): ProfileSwitchRule? =
        rules.asSequence().filter { it.enabled && it.profileId in validProfiles && state.matches(it.condition) }
            .sortedWith(compareBy<ProfileSwitchRule> { it.priority }.thenBy { it.id }).firstOrNull()
}

/** Process coordinator. It is called by the VPN service's single network callback, not an Activity. */
object ProfileAutomationManager {
    const val DEBOUNCE_MS = 1500L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastObserved: ProfileNetworkState? = null
    private var manualOverrideState: ProfileNetworkState? = null
    private var manualOverridePending = false
    private var cancelled = false

    /**
     * Marks the next observed network state as manually overridden.
     */
    fun noteManualSelection() {
        // Bind the override to the next observed state. This also covers a selection made before
        // the first evaluation and a selection made while a debounced callback is pending.
        manualOverrideState = null
        manualOverridePending = true
    }

    /**
     * Cancels any pending profile automation evaluation and disables further processing.
     */
    fun cancelPending() {
        cancelled = true
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    /**
 * Re-enables profile automation evaluation.
 */
fun resume() { cancelled = false }

    /**
     * Schedules profile automation evaluation after a network state change.
     *
     * @param immediate Whether to evaluate immediately instead of applying the debounce delay.
     */
    fun onNetworkChanged(context: Context, immediate: Boolean = false) {
        if (cancelled) return
        pending?.let(handler::removeCallbacks)
        val task = Runnable { if (!cancelled) evaluateNow(context) }
        pending = task
        if (immediate) task.run() else handler.postDelayed(task, DEBOUNCE_MS)
    }

    /**
     * Evaluates the current network state and applies the highest-priority matching profile rule.
     *
     * @param context Context used to read configuration and network state.
     * @return The evaluation outcome, including whether automation is disabled, overridden,
     * unchanged, already active, switched, or no matching rule was found.
     */
    fun evaluateNow(context: Context): ProfileAutomationResult {
        val config = TunnelGuardConfig(context.applicationContext)
        if (!config.isAutomaticProfileSwitchingEnabled()) return ProfileAutomationResult.Disabled
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val state = readState(config, cm)
        if (manualOverridePending) {
            manualOverridePending = false
            manualOverrideState = state
            lastObserved = state
            return ProfileAutomationResult.ManualOverride
        }
        if (manualOverrideState == state) return ProfileAutomationResult.ManualOverride
        if (lastObserved != null && lastObserved == state) return ProfileAutomationResult.UnchangedState
        lastObserved = state
        manualOverrideState = null
        val profiles = config.getProfiles()
        val rule = ProfileRuleEvaluator.match(config.getProfileSwitchRules(), state, profiles.map { it.id }.toSet())
            ?: return config.ensureValidSelectedProfile()
        val previous = config.getSelectedProfileId()
        if (previous == rule.profileId) return ProfileAutomationResult.AlreadyActive
        config.setSelectedProfileIdAutomatically(rule.profileId, rule)
        if (TunnelGuardVpnService.isServiceRunning) {
            context.startService(Intent(context, TunnelGuardVpnService::class.java).setAction(TunnelGuardVpnService.ACTION_UPDATE))
        }
        config.addLog("Automatic profile switch: $previous -> ${rule.profileId}. Reason: ${rule.condition.label}")
        return ProfileAutomationResult.Switched(previous, rule.profileId, rule.id)
    }

    /**
     * Reads the current Wi-Fi, Ethernet, and upstream VPN connectivity state.
     *
     * @param config Configuration used to determine simulated or real VPN state.
     * @param cm Connectivity manager used to inspect active network transports.
     * @return The observed network state, including an indeterminate upstream VPN state when detection is unavailable.
     */
    fun readState(config: TunnelGuardConfig, cm: ConnectivityManager?): ProfileNetworkState {
        var wifi = false; var ethernet = false
        try {
            cm?.allNetworks?.forEach { network ->
                cm.getNetworkCapabilities(network)?.let { caps ->
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        wifi = wifi || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        ethernet = ethernet || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    }
                }
            }
        } catch (_: Exception) { }
        // Simulation intentionally drives VPN rules. Real mode reuses the security-relevant detector,
        // which excludes TunnelGuard's owner UID/tunnel address.
        val upstream = if (config.isSimulatedVpnEnabled()) {
            config.getVPNState() in setOf(VPNState.CONNECTED, VPNState.PROTECTED)
        } else {
            // ANY deliberately ignores country policy: automation asks whether an external VPN
            // exists, while evaluateUpstreamVpn still excludes TunnelGuard's own local tunnel.
            when (config.evaluateUpstreamVpn(cm, requiredCountryCode = "ANY")) {
                is UpstreamVpnEvaluation.Valid, is UpstreamVpnEvaluation.CountryMismatch -> true
                is UpstreamVpnEvaluation.Missing -> false
                UpstreamVpnEvaluation.ForegroundUnknown, UpstreamVpnEvaluation.Unknown -> null
            }
        }
        return ProfileNetworkState(wifi, ethernet, upstream)
    }
}

sealed class ProfileAutomationResult {
    data class Switched(val previousProfileId: String, val newProfileId: String, val ruleId: String) : ProfileAutomationResult()
    data object AlreadyActive : ProfileAutomationResult()
    data object NoMatchingRule : ProfileAutomationResult()
    data object Disabled : ProfileAutomationResult()
    data object ManualOverride : ProfileAutomationResult()
    data object UnchangedState : ProfileAutomationResult()
}
