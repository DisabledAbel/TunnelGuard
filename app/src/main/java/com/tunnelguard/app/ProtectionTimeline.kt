package com.tunnelguard.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ProtectionEventSeverity { INFO, WARNING, ERROR }
enum class ProtectionEventCategory { VPN, PROTECTION, PROFILES, OVERRIDES, APP_MONITORING, SYSTEM }

enum class ProtectionEventType(val category: ProtectionEventCategory) {
    VPN_DETECTED(ProtectionEventCategory.VPN), VPN_LOST(ProtectionEventCategory.VPN),
    VPN_PROVIDER_CHANGED(ProtectionEventCategory.VPN), PROVIDER_UNAVAILABLE(ProtectionEventCategory.VPN),
    COUNTRY_VERIFIED(ProtectionEventCategory.VPN), COUNTRY_MISMATCH(ProtectionEventCategory.VPN),
    COUNTRY_UNKNOWN(ProtectionEventCategory.VPN), VPN_CONFLICT(ProtectionEventCategory.VPN),
    BLOCKING_STARTED(ProtectionEventCategory.PROTECTION), BLOCKING_STOPPED(ProtectionEventCategory.PROTECTION),
    PROTECTED_PACKAGES_CHANGED(ProtectionEventCategory.PROTECTION), ROUTING_REBUILT(ProtectionEventCategory.PROTECTION),
    RECOVERY_STARTED(ProtectionEventCategory.PROTECTION), RECOVERY_COMPLETED(ProtectionEventCategory.PROTECTION),
    RECOVERY_FAILED(ProtectionEventCategory.PROTECTION),
    PROFILE_MANUAL(ProtectionEventCategory.PROFILES), PROFILE_AUTOMATIC(ProtectionEventCategory.PROFILES),
    PROFILE_SCHEDULED(ProtectionEventCategory.PROFILES), PROFILE_NETWORK(ProtectionEventCategory.PROFILES),
    INVALID_PROFILE(ProtectionEventCategory.PROFILES),
    EMERGENCY_LOCK_ENABLED(ProtectionEventCategory.PROTECTION), EMERGENCY_LOCK_DISABLED(ProtectionEventCategory.PROTECTION),
    EMERGENCY_LOCK_ENFORCED(ProtectionEventCategory.PROTECTION),
    OVERRIDE_STARTED(ProtectionEventCategory.OVERRIDES), OVERRIDE_EXPIRED(ProtectionEventCategory.OVERRIDES),
    OVERRIDE_CANCELLED(ProtectionEventCategory.OVERRIDES), OVERRIDE_APP_CLOSED(ProtectionEventCategory.OVERRIDES),
    OVERRIDE_SUPPRESSED(ProtectionEventCategory.OVERRIDES),
    APP_FOREGROUND(ProtectionEventCategory.APP_MONITORING), APP_BACKGROUND(ProtectionEventCategory.APP_MONITORING),
    VPN_PERMISSION_REVOKED(ProtectionEventCategory.SYSTEM), VPN_PERMISSION_RESTORED(ProtectionEventCategory.SYSTEM),
    USAGE_ACCESS_MISSING(ProtectionEventCategory.SYSTEM), OVERLAY_PERMISSION_MISSING(ProtectionEventCategory.SYSTEM),
    BOOT_STARTED(ProtectionEventCategory.SYSTEM), BOOT_RECOVERED(ProtectionEventCategory.SYSTEM),
    BOOT_RECOVERY_FAILED(ProtectionEventCategory.SYSTEM), WATCHDOG_RECOVERY(ProtectionEventCategory.SYSTEM)
}

data class ProtectionEvent(
    val id: String,
    val timestamp: Long,
    val sequence: Long,
    val type: ProtectionEventType,
    val severity: ProtectionEventSeverity,
    val title: String,
    val message: String,
    val packageName: String? = null,
    val profileId: String? = null,
    val profileName: String? = null,
    val vpnProvider: String? = null,
    val country: String? = null,
    val previousState: String? = null,
    val newState: String? = null,
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

fun interface TimelineClock { fun now(): Long }

/** Thread-safe, local structured companion to TunnelGuard's existing diagnostic log. */
class ProtectionTimelineRepository(
    context: Context,
    private val clock: TimelineClock = TimelineClock { System.currentTimeMillis() },
    private val maximumEvents: Int = MAX_EVENTS,
    private val maximumAgeMs: Long = MAX_AGE_MS
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(
        type: ProtectionEventType,
        severity: ProtectionEventSeverity,
        title: String,
        message: String,
        packageName: String? = null,
        profileId: String? = null,
        profileName: String? = null,
        vpnProvider: String? = null,
        country: String? = null,
        previousState: String? = null,
        newState: String? = null,
        correlationId: String? = null,
        metadata: Map<String, String> = emptyMap(),
        deduplicationKey: String? = null
    ): ProtectionEvent? = synchronized(LOCK) {
        val now = clock.now()
        val items = readLocked(now).toMutableList()
        val deduplicationTimes = readDeduplicationTimes()
        deduplicationTimes.entries.removeAll { now - it.value !in 0 until DEDUPLICATION_WINDOW_MS }
        if (deduplicationKey != null && deduplicationTimes[deduplicationKey]?.let {
                now - it in 0 until DEDUPLICATION_WINDOW_MS
            } == true) return null
        val sequence = prefs.getLong(KEY_SEQUENCE, 0L) + 1
        val event = ProtectionEvent("$now-$sequence-${UUID.randomUUID()}", now, sequence, type, severity,
            title, message, packageName, profileId, profileName, vpnProvider, country, previousState,
            newState, correlationId, metadata)
        items += event
        val retained = items.filter { now - it.timestamp <= maximumAgeMs }.takeLast(maximumEvents)
        if (deduplicationKey != null) deduplicationTimes[deduplicationKey] = now
        prefs.edit().putString(KEY_EVENTS, encode(retained).toString()).putLong(KEY_SEQUENCE, sequence)
            .putString(KEY_DEDUPLICATION_TIMES, JSONObject(deduplicationTimes as Map<String, Any>).toString())
            .remove(KEY_LAST_DEDUP).commit()
        event
    }

    fun getEvents(filter: TimelineFilter = TimelineFilter.ALL): List<ProtectionEvent> = synchronized(LOCK) {
        readLocked(clock.now()).asSequence().filter(filter::matches)
            .sortedWith(compareByDescending<ProtectionEvent> { it.timestamp }.thenByDescending { it.sequence }).toList()
    }

    fun clear() = synchronized(LOCK) {
        prefs.edit().remove(KEY_EVENTS).remove(KEY_DEDUPLICATION_TIMES).remove(KEY_LAST_DEDUP).commit()
        Unit
    }

    fun exportJson(): String = synchronized(LOCK) {
        JSONObject().put("schemaVersion", SCHEMA_VERSION).put("exportedAt", clock.now())
            .put("events", encode(readLocked(clock.now()))).toString(2)
    }

    fun exportToFile(context: Context): File? = runCatching {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        File(directory, "protection-timeline-${clock.now()}.json").apply { writeText(exportJson()) }
    }.getOrNull()

    fun summary(): TimelineSummary {
        val events = getEvents()
        fun last(type: ProtectionEventType) = events.firstOrNull { it.type == type }
        return TimelineSummary(events.size, events.firstOrNull(), last(ProtectionEventType.VPN_LOST),
            last(ProtectionEventType.BLOCKING_STARTED), events.firstOrNull { it.type in PROFILE_TYPES },
            events.firstOrNull { it.type.category == ProtectionEventCategory.OVERRIDES },
            events.firstOrNull { it.metadata.containsKey("recoveryDurationMs") }?.metadata?.get("recoveryDurationMs")?.toLongOrNull())
    }

    private fun readLocked(now: Long): List<ProtectionEvent> {
        val raw = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) runCatching { decode(array.getJSONObject(i)) }.getOrNull()?.let(::add) }
        }.getOrDefault(emptyList())
        val retained = parsed.filter { now - it.timestamp <= maximumAgeMs }.takeLast(maximumEvents)
        if (retained.size != parsed.size) prefs.edit().putString(KEY_EVENTS, encode(retained).toString()).commit()
        return retained
    }

    private fun readDeduplicationTimes(): MutableMap<String, Long> = runCatching {
        val json = JSONObject(prefs.getString(KEY_DEDUPLICATION_TIMES, "{}") ?: "{}")
        buildMap { json.keys().forEach { key -> put(key, json.getLong(key)) } }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    companion object {
        const val MAX_EVENTS = 500
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val SCHEMA_VERSION = 1
        private const val PREFS = "protection_timeline"
        private const val KEY_EVENTS = "events_v1"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_LAST_DEDUP = "last_deduplication_key"
        private const val KEY_DEDUPLICATION_TIMES = "deduplication_times_v1"
        const val DEDUPLICATION_WINDOW_MS = 5_000L
        private val LOCK = Any()
        private val PROFILE_TYPES = setOf(ProtectionEventType.PROFILE_MANUAL, ProtectionEventType.PROFILE_AUTOMATIC,
            ProtectionEventType.PROFILE_SCHEDULED, ProtectionEventType.PROFILE_NETWORK, ProtectionEventType.INVALID_PROFILE)

        fun observedDurationMs(startTimestamp: Long, endTimestamp: Long): Long? =
            (endTimestamp - startTimestamp).takeIf { startTimestamp >= 0 && it >= 0 }

        private fun encode(events: List<ProtectionEvent>) = JSONArray().also { array -> events.forEach { e ->
            array.put(JSONObject().put("id", e.id).put("timestamp", e.timestamp).put("sequence", e.sequence)
                .put("type", e.type.name).put("severity", e.severity.name).put("title", e.title)
                .put("message", e.message).putOpt("packageName", e.packageName).putOpt("profileId", e.profileId)
                .putOpt("profileName", e.profileName).putOpt("vpnProvider", e.vpnProvider).putOpt("country", e.country)
                .putOpt("previousState", e.previousState).putOpt("newState", e.newState)
                .putOpt("correlationId", e.correlationId).put("metadata", JSONObject(e.metadata)))
        } }

        private fun decode(o: JSONObject): ProtectionEvent {
            fun optional(key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }
            val metadataObject = o.optJSONObject("metadata") ?: JSONObject()
            val metadata = mutableMapOf<String, String>()
            metadataObject.keys().forEach { key -> metadata[key] = metadataObject.optString(key) }
            return ProtectionEvent(o.getString("id"), o.getLong("timestamp"), o.optLong("sequence"),
                ProtectionEventType.valueOf(o.getString("type")), ProtectionEventSeverity.valueOf(o.getString("severity")),
                o.getString("title"), o.getString("message"), optional("packageName"), optional("profileId"),
                optional("profileName"), optional("vpnProvider"), optional("country"), optional("previousState"),
                optional("newState"), optional("correlationId"), metadata)
        }
    }
}

data class TimelineSummary(val count: Int, val lastEvent: ProtectionEvent?, val lastVpnLoss: ProtectionEvent?,
    val lastBlocking: ProtectionEvent?, val lastProfile: ProtectionEvent?, val lastOverride: ProtectionEvent?,
    val lastRecoveryDurationMs: Long?)

enum class TimelineFilter {
    ALL, VPN, PROTECTION, PROFILES, OVERRIDES, WARNINGS_ERRORS;
    fun matches(event: ProtectionEvent) = when (this) {
        ALL -> true
        VPN -> event.type.category == ProtectionEventCategory.VPN
        PROTECTION -> event.type.category in setOf(ProtectionEventCategory.PROTECTION, ProtectionEventCategory.APP_MONITORING, ProtectionEventCategory.SYSTEM)
        PROFILES -> event.type.category == ProtectionEventCategory.PROFILES
        OVERRIDES -> event.type.category == ProtectionEventCategory.OVERRIDES
        WARNINGS_ERRORS -> event.severity != ProtectionEventSeverity.INFO
    }
}
