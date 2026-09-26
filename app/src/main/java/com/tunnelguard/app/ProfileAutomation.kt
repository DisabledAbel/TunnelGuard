package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

enum class ProfileRuleCondition(val label: String) {
    WIFI_CONNECTED("Wi-Fi Connected"),
    WIFI_NETWORK("Specific Wi-Fi Network"),
    UNKNOWN_WIFI_NETWORK("Unknown Wi-Fi Network"),
    ETHERNET_CONNECTED("Ethernet Connected"),
    VPN_CONNECTED("VPN Connected"),
    VPN_DISCONNECTED("VPN Disconnected")
}

/** Optional fields make rules written by older TunnelGuard versions source and storage compatible. */
data class ProfileSwitchRule(
    val id: String,
    val condition: ProfileRuleCondition,
    val profileId: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val networkIdentifier: String? = null
) {
    fun summaryCondition(): String = when (condition) {
        ProfileRuleCondition.WIFI_NETWORK -> "${normalizeSsid(networkIdentifier) ?: "Unnamed"} Wi-Fi"
        ProfileRuleCondition.UNKNOWN_WIFI_NETWORK -> "Unknown Wi-Fi"
        ProfileRuleCondition.ETHERNET_CONNECTED -> "Ethernet"
        else -> condition.label
    }
}

enum class WifiIdentityStatus { NOT_WIFI, KNOWN, UNAVAILABLE }

data class ProfileNetworkState(
    val wifi: Boolean,
    val ethernet: Boolean,
    val upstreamVpn: Boolean?,
    val wifiIdentity: WifiIdentityStatus = if (wifi) WifiIdentityStatus.UNAVAILABLE else WifiIdentityStatus.NOT_WIFI,
    val wifiSsid: String? = null
) {
    val signature: String get() = "$wifi|$ethernet|$upstreamVpn|$wifiIdentity|${normalizeSsid(wifiSsid).orEmpty()}"
    fun transportDescription() = when {
        wifi && ethernet -> "Wi-Fi + Ethernet"
        wifi -> "Wi-Fi"
        ethernet -> "Ethernet"
        else -> "Other / disconnected"
    }
}

/** Removes Android's presentation quotes and rejects values that do not identify a network. */
fun normalizeSsid(value: String?): String? {
    var result = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (result.length >= 2 && result.first() == '"' && result.last() == '"') {
        result = result.substring(1, result.length - 1).trim()
    }
    if (result.isEmpty() || result.equals("<unknown ssid>", ignoreCase = true)) return null
    return result
}

/** Pure deterministic rule selection; Android network discovery is intentionally kept outside. */
object ProfileRuleEvaluator {
    fun match(rules: List<ProfileSwitchRule>, state: ProfileNetworkState, validProfiles: Set<String>): ProfileSwitchRule? {
        val candidates = rules.filter { it.enabled && it.profileId in validProfiles }
        val usableSsid = if (state.wifiIdentity == WifiIdentityStatus.KNOWN) normalizeSsid(state.wifiSsid) else null
        val recognized = usableSsid != null && candidates.any {
            it.condition == ProfileRuleCondition.WIFI_NETWORK && normalizeSsid(it.networkIdentifier) == usableSsid
        }
        return candidates.asSequence().filter { rule ->
            when (rule.condition) {
                ProfileRuleCondition.WIFI_CONNECTED -> state.wifi
                ProfileRuleCondition.WIFI_NETWORK -> state.wifi && usableSsid != null &&
                    normalizeSsid(rule.networkIdentifier) == usableSsid
                ProfileRuleCondition.UNKNOWN_WIFI_NETWORK -> state.wifi && usableSsid != null && !recognized
                ProfileRuleCondition.ETHERNET_CONNECTED -> state.ethernet
                ProfileRuleCondition.VPN_CONNECTED -> state.upstreamVpn == true
                ProfileRuleCondition.VPN_DISCONNECTED -> state.upstreamVpn == false
            }
        }.sortedWith(compareBy<ProfileSwitchRule> { it.priority }.thenBy { it.id }).firstOrNull()
    }

    fun reason(rule: ProfileSwitchRule): String = when (rule.condition) {
        ProfileRuleCondition.WIFI_NETWORK -> "Wi-Fi network ${normalizeSsid(rule.networkIdentifier)}"
        ProfileRuleCondition.UNKNOWN_WIFI_NETWORK -> "unrecognized Wi-Fi network"
        else -> rule.condition.label
    }
}

/** Single Android-specific collector for transport and Wi-Fi identity. It never reads BSSID/MAC. */
object ProfileNetworkStateCollector {
    fun collect(context: Context, config: TunnelGuardConfig, cm: ConnectivityManager?): ProfileNetworkState {
        var wifi = false
        var ethernet = false
        var ssid: String? = null
        try {
            cm?.allNetworks?.forEach { network ->
                cm.getNetworkCapabilities(network)?.let { caps ->
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            wifi = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                ssid = ssid ?: normalizeSsid((caps.transportInfo as? WifiInfo)?.ssid)
                            }
                        }
                        ethernet = ethernet || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    }
                }
            }
            if (wifi && ssid == null) {
                @Suppress("DEPRECATION")
                val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
                ssid = normalizeSsid(info?.ssid)
            }
        } catch (_: SecurityException) {
            ssid = null
        } catch (_: RuntimeException) {
            ssid = null
        }
        val upstream = if (config.isSimulatedVpnEnabled()) {
            config.getVPNState() in setOf(VPNState.CONNECTED, VPNState.PROTECTED)
        } else when (config.evaluateUpstreamVpn(cm, requiredCountryCode = "ANY")) {
            is UpstreamVpnEvaluation.Valid, is UpstreamVpnEvaluation.CountryMismatch -> true
            is UpstreamVpnEvaluation.Missing -> false
            UpstreamVpnEvaluation.ForegroundUnknown, UpstreamVpnEvaluation.Unknown -> null
        }
        return ProfileNetworkState(
            wifi, ethernet, upstream,
            if (!wifi) WifiIdentityStatus.NOT_WIFI else if (ssid != null) WifiIdentityStatus.KNOWN else WifiIdentityStatus.UNAVAILABLE,
            ssid
        )
    }
}

/** Tracks meaningful identities separately from callback frequency, including manual override scope. */
class ProfileAutomationStateTracker {
    private var lastObserved: String? = null
    private var manualOverride: String? = null
    private var manualPending = false
    fun noteManualSelection() { manualOverride = null; manualPending = true }
    fun observe(state: ProfileNetworkState): ProfileAutomationResult? {
        val signature = state.signature
        if (manualPending) {
            manualPending = false; manualOverride = signature; lastObserved = signature
            return ProfileAutomationResult.ManualOverride
        }
        if (manualOverride == signature) return ProfileAutomationResult.ManualOverride
        if (lastObserved == signature) return ProfileAutomationResult.UnchangedState
        lastObserved = signature
        manualOverride = null
        return null
    }
}

/** Process coordinator called by the VPN service's single network callback. */
object ProfileAutomationManager {
    const val DEBOUNCE_MS = 1500L
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = ProfileAutomationStateTracker()
    private var pending: Runnable? = null
    private var cancelled = false
    @Volatile var latestState: ProfileNetworkState? = null
        private set

    fun noteManualSelection() = tracker.noteManualSelection()
    fun cancelPending() { cancelled = true; pending?.let(handler::removeCallbacks); pending = null }
    fun resume() { cancelled = false }
    fun onNetworkChanged(context: Context, immediate: Boolean = false) {
        if (cancelled) return
        pending?.let(handler::removeCallbacks)
        val task = Runnable { if (!cancelled) evaluateNow(context) }
        pending = task
        if (immediate) task.run() else handler.postDelayed(task, DEBOUNCE_MS)
    }

    fun evaluateNow(context: Context): ProfileAutomationResult {
        val config = TunnelGuardConfig(context.applicationContext)
        if (!config.isAutomaticProfileSwitchingEnabled()) return ProfileAutomationResult.Disabled
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val state = readState(context, config, cm)
        latestState = state
        tracker.observe(state)?.let { return it }
        if (state.wifiIdentity == WifiIdentityStatus.UNAVAILABLE &&
            config.getProfileSwitchRules().any { it.enabled && it.condition == ProfileRuleCondition.WIFI_NETWORK }) {
            config.addLog("Wi-Fi identity unavailable; named-network rules skipped", "WARN")
        }
        val profiles = config.getProfiles()
        val rule = ProfileRuleEvaluator.match(config.getProfileSwitchRules(), state, profiles.map { it.id }.toSet())
            ?: return config.ensureValidSelectedProfile()
        val reason = ProfileRuleEvaluator.reason(rule)
        if (rule.condition == ProfileRuleCondition.WIFI_NETWORK) {
            config.addLog("Network automation: SSID ${normalizeSsid(state.wifiSsid)} matched rule ${rule.id}")
        }
        val previous = config.getSelectedProfileId()
        if (previous == rule.profileId) {
            config.recordAutomaticRuleMatch(rule, reason)
            return ProfileAutomationResult.AlreadyActive
        }
        config.setSelectedProfileIdAutomatically(rule.profileId, rule, reason)
        if (TunnelGuardVpnService.isServiceRunning) {
            context.startService(Intent(context, TunnelGuardVpnService::class.java).setAction(TunnelGuardVpnService.ACTION_UPDATE))
        }
        val oldName = profiles.find { it.id == previous }?.name ?: previous
        val newName = profiles.find { it.id == rule.profileId }?.name ?: rule.profileId
        config.addLog("Automatic profile switch: $oldName -> $newName. Reason: $reason")
        return ProfileAutomationResult.Switched(previous, rule.profileId, rule.id)
    }

    fun readState(context: Context, config: TunnelGuardConfig, cm: ConnectivityManager?) =
        ProfileNetworkStateCollector.collect(context, config, cm)
}

sealed class ProfileAutomationResult {
    data class Switched(val previousProfileId: String, val newProfileId: String, val ruleId: String) : ProfileAutomationResult()
    data object AlreadyActive : ProfileAutomationResult()
    data object NoMatchingRule : ProfileAutomationResult()
    data object Disabled : ProfileAutomationResult()
    data object ManualOverride : ProfileAutomationResult()
    data object UnchangedState : ProfileAutomationResult()
}
