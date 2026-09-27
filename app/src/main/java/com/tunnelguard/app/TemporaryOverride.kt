package com.tunnelguard.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

enum class TemporaryOverrideType { TIMED, UNTIL_APP_CLOSES }

data class TemporaryOverride(
    val packageName: String,
    val type: TemporaryOverrideType,
    val createdWallTimeMs: Long,
    val createdElapsedTimeMs: Long,
    val expiresWallTimeMs: Long? = null,
    val expiresElapsedTimeMs: Long? = null,
    val source: String = "Manage protected apps"
)

interface OverrideClock {
    fun wallTimeMillis(): Long
    fun elapsedRealtime(): Long
}

object AndroidOverrideClock : OverrideClock {
    override fun wallTimeMillis() = System.currentTimeMillis()
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
}

/** Pure, synchronized authority for override lifetime and Emergency Lock precedence. */
class TemporaryOverrideEngine(private val clock: OverrideClock) {
    companion object {
        const val FOREGROUND_START_TIMEOUT_MS = 30_000L
        const val UNTIL_CLOSE_MAX_LIFETIME_MS = 4 * 60 * 60_000L
    }

    private val records = linkedMapOf<String, TemporaryOverride>()
    private val foregroundSessionsStarted = mutableSetOf<String>()

    @Synchronized fun restore(timed: Collection<TemporaryOverride>) {
        records.clear()
        foregroundSessionsStarted.clear()
        timed.filter { it.type == TemporaryOverrideType.TIMED }.forEach { records[it.packageName] = it }
        reconcile()
    }

    @Synchronized fun startTimed(packageName: String, durationMs: Long, source: String): TemporaryOverride {
        require(durationMs > 0)
        val value = TemporaryOverride(packageName, TemporaryOverrideType.TIMED,
            clock.wallTimeMillis(), clock.elapsedRealtime(), clock.wallTimeMillis() + durationMs,
            clock.elapsedRealtime() + durationMs, source)
        records[packageName] = value
        return value
    }

    @Synchronized fun startUntilClosed(
        packageName: String,
        source: String,
        startupTimeoutMs: Long = FOREGROUND_START_TIMEOUT_MS
    ): TemporaryOverride {
        require(startupTimeoutMs > 0)
        val value = TemporaryOverride(packageName, TemporaryOverrideType.UNTIL_APP_CLOSES,
            clock.wallTimeMillis(), clock.elapsedRealtime(),
            clock.wallTimeMillis() + startupTimeoutMs, clock.elapsedRealtime() + startupTimeoutMs, source)
        records[packageName] = value
        return value
    }

    @Synchronized fun cancel(packageName: String): Boolean {
        foregroundSessionsStarted.remove(packageName)
        return records.remove(packageName) != null
    }

    @Synchronized fun active(packageName: String, emergencyLock: Boolean): TemporaryOverride? {
        reconcile()
        return records[packageName]?.takeUnless { emergencyLock }
    }

    @Synchronized fun allStored(): List<TemporaryOverride> { reconcile(); return records.values.toList() }

    @Synchronized fun onForegroundChanged(packageName: String?): List<String> {
        reconcile()
        records[packageName]?.takeIf { it.type == TemporaryOverrideType.UNTIL_APP_CLOSES }?.let {
            if (foregroundSessionsStarted.add(it.packageName)) {
                records[it.packageName] = it.copy(
                    expiresWallTimeMs = it.createdWallTimeMs + UNTIL_CLOSE_MAX_LIFETIME_MS,
                    expiresElapsedTimeMs = it.createdElapsedTimeMs + UNTIL_CLOSE_MAX_LIFETIME_MS
                )
            }
        }
        val removed = records.values.filter {
            it.type == TemporaryOverrideType.UNTIL_APP_CLOSES && it.packageName != packageName &&
                foregroundSessionsStarted.contains(it.packageName)
        }.map { it.packageName }
        removed.forEach { records.remove(it); foregroundSessionsStarted.remove(it) }
        return removed
    }

    @Synchronized fun reconcile(): List<String> {
        val nowWall = clock.wallTimeMillis()
        val nowElapsed = clock.elapsedRealtime()
        val expired = records.values.filter { value ->
            (
                nowElapsed < value.createdElapsedTimeMs ||
                    nowWall >= requireNotNull(value.expiresWallTimeMs) ||
                    nowElapsed >= requireNotNull(value.expiresElapsedTimeMs)
                )
        }.map { it.packageName }
        expired.forEach(records::remove)
        return expired
    }
}

/** Application-wide facade. Timed records persist; foreground-session records deliberately do not. */
object TemporaryOverrideManager {
    const val ACTION_CHANGED = "com.tunnelguard.app.TEMPORARY_OVERRIDE_CHANGED"
    val durationsMinutes = listOf(5, 15, 30, 60)
    private const val PREFS = "temporary_override_runtime"
    private const val KEY_TIMED = "timed_records"
    private const val ALARM_REQUEST = 7041
    private lateinit var appContext: Context
    private var engine: TemporaryOverrideEngine? = null
    private val suppressionLogged = mutableSetOf<String>()

    @Synchronized fun initialize(context: Context): TemporaryOverrideManager {
        appContext = context.applicationContext
        if (engine == null) {
            engine = TemporaryOverrideEngine(AndroidOverrideClock).also { it.restore(readTimed()) }
            pruneAndPersist(logExpired = true)
        }
        return this
    }

    fun startTimed(context: Context, packageName: String, minutes: Int, source: String = "Manage protected apps") {
        require(minutes in durationsMinutes)
        initialize(context)
        engine!!.startTimed(packageName, minutes * 60_000L, source)
        TunnelGuardConfig(context).addLog("Temporary override started: $packageName for $minutes minutes")
        ProtectionTimelineRepository(context).record(ProtectionEventType.OVERRIDE_STARTED, ProtectionEventSeverity.WARNING,
            "Temporary override started", "$packageName is exempt for $minutes minutes.", packageName = packageName,
            metadata = mapOf("durationMinutes" to minutes.toString(), "source" to source))
        persistAndNotify(context)
    }

    fun startUntilAppCloses(context: Context, packageName: String, source: String = "Manage protected apps") {
        initialize(context)
        engine!!.startUntilClosed(packageName, source)
        TunnelGuardConfig(context).addLog("Temporary override started: $packageName until app closes")
        ProtectionTimelineRepository(context).record(ProtectionEventType.OVERRIDE_STARTED, ProtectionEventSeverity.WARNING,
            "Temporary override started", "$packageName is exempt until it leaves the foreground.", packageName = packageName,
            metadata = mapOf("mode" to "until_app_closes", "source" to source))
        persistAndNotify(context)
    }

    fun cancel(context: Context, packageName: String, reason: String = "cancelled") {
        initialize(context)
        if (engine!!.cancel(packageName)) {
            TunnelGuardConfig(context).addLog("Temporary override $reason: $packageName")
            ProtectionTimelineRepository(context).record(ProtectionEventType.OVERRIDE_CANCELLED, ProtectionEventSeverity.INFO,
                "Temporary override cancelled", "$packageName is protected normally again.", packageName = packageName,
                metadata = mapOf("reason" to reason))
            persistAndNotify(context)
        }
    }

    fun getActiveOverride(context: Context, packageName: String, emergencyLock: Boolean): TemporaryOverride? {
        initialize(context); pruneAndPersist(true)
        val stored = engine!!.allStored().any { it.packageName == packageName }
        synchronized(suppressionLogged) {
            if (stored && emergencyLock && suppressionLogged.add(packageName)) {
                TunnelGuardConfig(context).addLog("Temporary override suppressed by Emergency Lock: $packageName")
                ProtectionTimelineRepository(context).record(ProtectionEventType.OVERRIDE_SUPPRESSED, ProtectionEventSeverity.WARNING,
                    "Override suppressed by Emergency Lock", "$packageName remains protected while Emergency Lock is enabled.",
                    packageName = packageName, deduplicationKey = "override-suppressed:$packageName")
            } else if (!emergencyLock) {
                suppressionLogged.remove(packageName)
            } else {
                Unit
            }
        }
        return engine!!.active(packageName, emergencyLock)
    }

    fun getStoredOverrides(context: Context): List<TemporaryOverride> {
        initialize(context); pruneAndPersist(true); return engine!!.allStored()
    }

    fun onForegroundChanged(context: Context, foregroundPackage: String?) {
        initialize(context)
        val activeEngine = engine!!
        val previousDeadline = foregroundPackage?.let { packageName ->
            activeEngine.allStored().find { it.packageName == packageName }?.expiresElapsedTimeMs
        }
        val removedPackages = activeEngine.onForegroundChanged(foregroundPackage)
        val currentDeadline = foregroundPackage?.let { packageName ->
            activeEngine.allStored().find { it.packageName == packageName }?.expiresElapsedTimeMs
        }
        removedPackages.forEach {
            TunnelGuardConfig(context).addLog("Temporary override cleared after app left foreground: $it")
            ProtectionTimelineRepository(context).record(ProtectionEventType.OVERRIDE_APP_CLOSED, ProtectionEventSeverity.INFO,
                "Override ended when app closed", "$it is protected normally again.", packageName = it)
        }
        if (removedPackages.isNotEmpty()) persistAndNotify(context)
        else if (currentDeadline != null && currentDeadline != previousDeadline) schedule()
    }

    fun clearForBoot(context: Context) {
        initialize(context)
        engine!!.restore(emptyList())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        schedule()
    }

    private fun pruneAndPersist(logExpired: Boolean) {
        val expired = engine!!.reconcile()
        if (expired.isNotEmpty()) {
            if (logExpired) expired.forEach {
                TunnelGuardConfig(appContext).addLog("Temporary override expired: $it")
                ProtectionTimelineRepository(appContext).record(ProtectionEventType.OVERRIDE_EXPIRED, ProtectionEventSeverity.INFO,
                    "Temporary override expired", "$it is protected normally again.", packageName = it)
            }
            persistAndNotify(appContext)
        }
    }

    private fun persistAndNotify(context: Context) {
        val array = JSONArray()
        engine!!.allStored().filter { it.type == TemporaryOverrideType.TIMED }.forEach { o ->
            array.put(JSONObject().put("package", o.packageName).put("createdWall", o.createdWallTimeMs)
                .put("createdElapsed", o.createdElapsedTimeMs).put("expiresWall", o.expiresWallTimeMs)
                .put("expiresElapsed", o.expiresElapsedTimeMs).put("source", o.source))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TIMED, array.toString()).commit()
        schedule()
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
        val service = Intent(context, TunnelGuardVpnService::class.java).setAction(TunnelGuardVpnService.ACTION_UPDATE)
        if (TunnelGuardVpnService.isServiceRunning) context.startService(service)
    }

    private fun readTimed(): List<TemporaryOverride> = runCatching {
        val array = JSONArray(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TIMED, "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            TemporaryOverride(o.getString("package"), TemporaryOverrideType.TIMED,
                o.getLong("createdWall"), o.getLong("createdElapsed"), o.getLong("expiresWall"),
                o.getLong("expiresElapsed"), o.optString("source", "Manage protected apps"))
        } }
    }.getOrDefault(emptyList())

    private fun schedule() {
        if (!::appContext.isInitialized) return
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(appContext, ALARM_REQUEST,
            Intent(appContext, TemporaryOverrideReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val next = engine!!.allStored().mapNotNull { it.expiresElapsedTimeMs }.minOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pending)
        else alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pending)
    }
}

class TemporaryOverrideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            intent.data?.schemeSpecificPart?.let { TemporaryOverrideManager.cancel(context, it, "cleared because app was uninstalled") }
        } else {
            TemporaryOverrideManager.initialize(context).getStoredOverrides(context)
        }
    }
}
