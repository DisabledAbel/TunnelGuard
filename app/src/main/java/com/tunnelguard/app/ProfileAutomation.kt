package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.TimeZone

enum class ProfileRuleCondition(val label: String) {
    WIFI_CONNECTED("Wi-Fi Connected"),
    WIFI_NETWORK("Specific Wi-Fi Network"),
    UNKNOWN_WIFI_NETWORK("Unknown Wi-Fi Network"),
    ETHERNET_CONNECTED("Ethernet Connected"),
    VPN_CONNECTED("VPN Connected"),
    VPN_DISCONNECTED("VPN Disconnected"),
    SCHEDULED_TIME("Scheduled Time")
}

/** Optional fields make rules written by older TunnelGuard versions source and storage compatible. */
data class ProfileSwitchRule(
    val id: String,
    val condition: ProfileRuleCondition,
    val profileId: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val networkIdentifier: String? = null,
    /** Minutes after local midnight (0..1439), deliberately independent of display formatting. */
    val startMinute: Int? = null,
    /** Exclusive range end, or null for a one-time daily transition. */
    val endMinute: Int? = null,
    /** java.util.Calendar day constants, stored explicitly rather than as UI labels. */
    val daysOfWeek: Set<Int> = emptySet()
) {
    fun summaryCondition(): String = when (condition) {
        ProfileRuleCondition.WIFI_NETWORK -> "${normalizeSsid(networkIdentifier) ?: "Unnamed"} Wi-Fi"
        ProfileRuleCondition.UNKNOWN_WIFI_NETWORK -> "Unknown Wi-Fi"
        ProfileRuleCondition.ETHERNET_CONNECTED -> "Ethernet"
        ProfileRuleCondition.SCHEDULED_TIME -> ScheduleRules.summary(this)
        else -> condition.label
    }

    fun hasValidSchedule() = condition != ProfileRuleCondition.SCHEDULED_TIME ||
        startMinute in 0..1439 && (endMinute == null || endMinute in 0..1439) &&
        daysOfWeek.isNotEmpty() && daysOfWeek.all { it in Calendar.SUNDAY..Calendar.SATURDAY }
}

data class ProfileTimeState(val dayOfWeek: Int, val minuteOfDay: Int, val epochMillis: Long, val timeZoneId: String)

/** Pure local wall-clock matching. Calendar construction is kept at the Android boundary. */
object ScheduleRules {
    val everyDay = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
    val weekdays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    val weekends = setOf(Calendar.SATURDAY, Calendar.SUNDAY)

    fun matches(rule: ProfileSwitchRule, time: ProfileTimeState, pendingBoundaryMillis: Long? = null): Boolean {
        if (!rule.hasValidSchedule()) return false
        val start = rule.startMinute!!
        val end = rule.endMinute
        if (end == null) {
            if (time.dayOfWeek in rule.daysOfWeek && time.minuteOfDay == start) return true
            return pendingBoundaryMillis?.takeIf { it in 1L..time.epochMillis }?.let { boundary ->
                val boundaryTime = ProfileTimeStateCollector.collect(boundary, TimeZone.getTimeZone(time.timeZoneId))
                boundaryTime.dayOfWeek in rule.daysOfWeek && boundaryTime.minuteOfDay == start
            } == true
        }
        if (start == end) return time.dayOfWeek in rule.daysOfWeek // explicit 24-hour range
        return if (start < end) {
            time.dayOfWeek in rule.daysOfWeek && time.minuteOfDay >= start && time.minuteOfDay < end
        } else {
            (time.dayOfWeek in rule.daysOfWeek && time.minuteOfDay >= start) ||
                (previousDay(time.dayOfWeek) in rule.daysOfWeek && time.minuteOfDay < end)
        }
    }

    fun phaseSignature(rules: List<ProfileSwitchRule>, time: ProfileTimeState, pendingBoundaryMillis: Long? = null): String = rules.asSequence()
        .filter { it.enabled && it.condition == ProfileRuleCondition.SCHEDULED_TIME && matches(it, time, pendingBoundaryMillis) }
        .map { it.id }.sorted().joinToString(",")

    fun summary(rule: ProfileSwitchRule): String {
        if (!rule.hasValidSchedule()) return "Invalid schedule"
        val days = when (rule.daysOfWeek) {
            everyDay -> "Every day"
            weekdays -> "Weekdays"
            weekends -> "Weekends"
            else -> rule.daysOfWeek.sorted().joinToString(", ") { shortDay(it) }
        }
        val range = formatMinute(rule.startMinute!!) + (rule.endMinute?.let { "–${formatMinute(it)}" } ?: "")
        return "$days • $range"
    }

    fun formatMinute(value: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, value / 60); set(Calendar.MINUTE, value % 60) }
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(c.time)
    }
    private fun shortDay(day: Int) = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").getOrElse(day) { "?" }
    private fun previousDay(day: Int) = if (day == Calendar.SUNDAY) Calendar.SATURDAY else day - 1
}

object ProfileTimeStateCollector {
    fun collect(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): ProfileTimeState {
        val c = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        return ProfileTimeState(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), nowMillis, zone.id)
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

/** Validates Android's UTF-8 SSID form (32 bytes) and hexadecimal form (32 octets). */
fun isValidSsidIdentifier(value: String?): Boolean {
    val normalized = normalizeSsid(value) ?: return false
    if (normalized.toByteArray(Charsets.UTF_8).size <= 32) return true
    val hex = normalized.removePrefix("0x").removePrefix("0X")
    return hex.length in 2..64 && hex.length % 2 == 0 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

/** Pure deterministic rule selection; Android network discovery is intentionally kept outside. */
object ProfileRuleEvaluator {
    fun match(rules: List<ProfileSwitchRule>, state: ProfileNetworkState, validProfiles: Set<String>, time: ProfileTimeState = ProfileTimeStateCollector.collect(), pendingBoundaryMillis: Long? = null): ProfileSwitchRule? {
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
                ProfileRuleCondition.SCHEDULED_TIME -> ScheduleRules.matches(rule, time, pendingBoundaryMillis)
            }
        }.sortedWith(compareBy<ProfileSwitchRule> { it.priority }.thenBy { it.id }).firstOrNull()
    }

    fun reason(rule: ProfileSwitchRule): String = when (rule.condition) {
        ProfileRuleCondition.WIFI_NETWORK -> "Wi-Fi network ${normalizeSsid(rule.networkIdentifier)}"
        ProfileRuleCondition.UNKNOWN_WIFI_NETWORK -> "unrecognized Wi-Fi network"
        ProfileRuleCondition.SCHEDULED_TIME -> "Scheduled rule ${rule.id}"
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
    fun noteManualSelection(state: ProfileNetworkState, schedulePhase: String = "") {
        manualOverride = state.signature + "|schedule=$schedulePhase"
        lastObserved = manualOverride
    }
    fun clear() { lastObserved = null; manualOverride = null }
    fun observe(state: ProfileNetworkState, schedulePhase: String = ""): ProfileAutomationResult? {
        val signature = state.signature + "|schedule=$schedulePhase"
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

    fun noteManualSelection(context: Context) {
        val config = TunnelGuardConfig(context.applicationContext)
        if (!config.isAutomaticProfileSwitchingEnabled()) {
            tracker.clear()
            return
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val time = ProfileTimeStateCollector.collect()
        tracker.noteManualSelection(readState(context, config, cm), ScheduleRules.phaseSignature(config.getProfileSwitchRules(), time))
    }
    fun clearManualSelectionState() = tracker.clear()
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
        fun finish(result: ProfileAutomationResult): ProfileAutomationResult {
            ProfileScheduleManager.schedule(context)
            return result
        }
        if (!config.isAutomaticProfileSwitchingEnabled()) {
            tracker.clear()
            return finish(ProfileAutomationResult.Disabled)
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val state = readState(context, config, cm)
        val time = ProfileTimeStateCollector.collect()
        val pendingBoundary = config.getNextProfileScheduleBoundary().takeIf { it in 1L..time.epochMillis }
        latestState = state
        tracker.observe(state, ScheduleRules.phaseSignature(config.getProfileSwitchRules(), time, pendingBoundary))?.let { return finish(it) }
        if (state.wifiIdentity == WifiIdentityStatus.UNAVAILABLE &&
            config.getProfileSwitchRules().any { it.enabled && it.condition == ProfileRuleCondition.WIFI_NETWORK }) {
            config.addLog("Wi-Fi identity unavailable; named-network rules skipped", "WARN")
        }
        val profiles = config.getProfiles()
        val rule = ProfileRuleEvaluator.match(config.getProfileSwitchRules(), state, profiles.map { it.id }.toSet(), time, pendingBoundary)
            ?: return finish(config.ensureValidSelectedProfile())
        val reason = ProfileRuleEvaluator.reason(rule)
        if (rule.condition == ProfileRuleCondition.WIFI_NETWORK) {
            config.addLog("Network automation: SSID ${normalizeSsid(state.wifiSsid)} matched rule ${rule.id}")
        }
        if (rule.condition == ProfileRuleCondition.SCHEDULED_TIME) {
            config.addLog("Schedule rule matched: ${ScheduleRules.summary(rule)} -> ${profiles.find { it.id == rule.profileId }?.name ?: rule.profileId}")
        }
        val previous = config.getSelectedProfileId()
        if (previous == rule.profileId) {
            config.recordAutomaticRuleMatch(rule, reason)
            return finish(ProfileAutomationResult.AlreadyActive)
        }
        config.setSelectedProfileIdAutomatically(rule.profileId, rule, reason)
        if (TunnelGuardVpnService.isServiceRunning) {
            context.startService(Intent(context, TunnelGuardVpnService::class.java).setAction(TunnelGuardVpnService.ACTION_UPDATE))
        }
        val oldName = profiles.find { it.id == previous }?.name ?: previous
        val newName = profiles.find { it.id == rule.profileId }?.name ?: rule.profileId
        config.addLog("Automatic profile switch: $oldName -> $newName. Reason: $reason")
        return finish(ProfileAutomationResult.Switched(previous, rule.profileId, rule.id))
    }

    fun readState(context: Context, config: TunnelGuardConfig, cm: ConnectivityManager?) =
        ProfileNetworkStateCollector.collect(context, config, cm)
}

/** Calculates and registers only the next meaningful local-time boundary; DST is delegated to Calendar. */
object ProfileScheduleManager {
    const val ACTION_BOUNDARY = "com.tunnelguard.app.PROFILE_SCHEDULE_BOUNDARY"
    fun nextBoundary(rules: List<ProfileSwitchRule>, nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Long? {
        val scheduled = rules.filter { it.enabled && it.hasValidSchedule() && it.condition == ProfileRuleCondition.SCHEDULED_TIME }
        if (scheduled.isEmpty()) return null
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        var best: Long? = null
        fun consider(dayOffset: Int, minute: Int) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, minute / 60)
                set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val value = candidate.timeInMillis
            if (value > nowMillis && (best == null || value < best!!)) best = value
        }
        // -1 is needed so an overnight rule that began yesterday can contribute today's end.
        for (offset in -1..8) {
            val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }.get(Calendar.DAY_OF_WEEK)
            for (rule in scheduled) if (day in rule.daysOfWeek) {
                consider(offset, rule.startMinute!!)
                rule.endMinute?.let { end -> consider(offset + if (rule.startMinute > end) 1 else 0, end) }
            }
        }
        return best
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(app, ProfileScheduleReceiver::class.java).setAction(ACTION_BOUNDARY)
        val pending = PendingIntent.getBroadcast(app, 9017, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val config = TunnelGuardConfig(app)
        val next = if (config.isAutomaticProfileSwitchingEnabled()) nextBoundary(config.getProfileSwitchRules()) else null
        config.setNextProfileScheduleBoundary(next ?: 0L)
        next?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it, pending)
            else alarm.set(AlarmManager.RTC_WAKEUP, it, pending)
        }
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
