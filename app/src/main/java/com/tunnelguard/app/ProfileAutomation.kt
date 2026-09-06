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

data class ProfileNetworkState(val wifi: Boolean, val ethernet: Boolean, val upstreamVpn: Boolean) {
    fun matches(condition: ProfileRuleCondition) = when (condition) {
        ProfileRuleCondition.WIFI_CONNECTED -> wifi
        ProfileRuleCondition.ETHERNET_CONNECTED -> ethernet
        ProfileRuleCondition.VPN_CONNECTED -> upstreamVpn
        ProfileRuleCondition.VPN_DISCONNECTED -> !upstreamVpn
    }
}

object ProfileRuleEvaluator {
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

    fun noteManualSelection() { manualOverrideState = lastObserved }

    fun onNetworkChanged(context: Context, immediate: Boolean = false) {
        pending?.let(handler::removeCallbacks)
        val task = Runnable { evaluateNow(context) }
        pending = task
        if (immediate) task.run() else handler.postDelayed(task, DEBOUNCE_MS)
    }

    fun evaluateNow(context: Context): ProfileAutomationResult {
        val config = TunnelGuardConfig(context.applicationContext)
        if (!config.isAutomaticProfileSwitchingEnabled()) return ProfileAutomationResult.Disabled
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val state = readState(config, cm)
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
        val upstream = if (config.isSimulatedVpnEnabled()) config.getVPNState() in setOf(VPNState.CONNECTED, VPNState.PROTECTED)
        else config.detectRealVpnCapabilities(cm) == VpnDetectionResult.VPN_DETECTED
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
