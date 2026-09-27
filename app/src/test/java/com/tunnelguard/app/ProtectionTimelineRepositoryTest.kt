package com.tunnelguard.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProtectionTimelineRepositoryTest {
    private lateinit var context: Context
    private var now = 1_000_000L
    private lateinit var repository: ProtectionTimelineRepository

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("protection_timeline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tunnel_guard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        repository = ProtectionTimelineRepository(context, TimelineClock { now })
    }

    @Test fun vpnConnectDisconnectAndDuplicateCallbacksAreStructured() {
        val config = TunnelGuardConfig(context)
        config.setVPNState(VPNState.PROTECTED)
        config.setVPNState(VPNState.PROTECTED)
        config.setVPNState(VPNState.DISCONNECTED)
        config.setVPNState(VPNState.DISCONNECTED)
        val events = ProtectionTimelineRepository(context).getEvents()
        assertEquals(1, events.count { it.type == ProtectionEventType.VPN_DETECTED })
        assertEquals(1, events.count { it.type == ProtectionEventType.VPN_LOST })
    }

    @Test fun blockingStartedStoppedAndRecoveryEventsCanCarryMeasuredDuration() {
        repository.record(ProtectionEventType.BLOCKING_STARTED, ProtectionEventSeverity.INFO, "started", "started")
        now += 867
        repository.record(ProtectionEventType.RECOVERY_COMPLETED, ProtectionEventSeverity.INFO, "recovered", "recovered",
            metadata = mapOf("recoveryDurationMs" to "867"))
        repository.record(ProtectionEventType.BLOCKING_STOPPED, ProtectionEventSeverity.INFO, "stopped", "stopped")
        assertEquals(867L, repository.summary().lastRecoveryDurationMs)
        assertEquals(867L, ProtectionTimelineRepository.observedDurationMs(1_000, 1_867))
        assertNull(ProtectionTimelineRepository.observedDurationMs(2_000, 1_000))
    }

    @Test fun requiredEventTaxonomySerializesAndFilters() {
        val types = listOf(ProtectionEventType.ROUTING_REBUILT, ProtectionEventType.PROFILE_MANUAL,
            ProtectionEventType.PROFILE_AUTOMATIC, ProtectionEventType.COUNTRY_VERIFIED,
            ProtectionEventType.COUNTRY_MISMATCH, ProtectionEventType.PROVIDER_UNAVAILABLE,
            ProtectionEventType.EMERGENCY_LOCK_ENABLED, ProtectionEventType.EMERGENCY_LOCK_DISABLED,
            ProtectionEventType.OVERRIDE_STARTED, ProtectionEventType.OVERRIDE_EXPIRED,
            ProtectionEventType.OVERRIDE_CANCELLED, ProtectionEventType.APP_FOREGROUND,
            ProtectionEventType.VPN_PERMISSION_REVOKED, ProtectionEventType.RECOVERY_COMPLETED,
            ProtectionEventType.RECOVERY_FAILED)
        types.forEachIndexed { index, type ->
            now++
            repository.record(type, if (type in setOf(ProtectionEventType.COUNTRY_MISMATCH,
                    ProtectionEventType.PROVIDER_UNAVAILABLE)) ProtectionEventSeverity.WARNING else ProtectionEventSeverity.INFO,
                type.name, "event $index")
        }
        val restored = ProtectionTimelineRepository(context, TimelineClock { now }).getEvents()
        assertEquals(types.size, restored.size)
        assertEquals(3, repository.getEvents(TimelineFilter.VPN).size)
        assertEquals(2, repository.getEvents(TimelineFilter.PROFILES).size)
        assertEquals(2, repository.getEvents(TimelineFilter.WARNINGS_ERRORS).size)
        assertEquals(1, JSONObject(repository.exportJson()).getInt("schemaVersion"))
    }

    @Test fun retentionPrunesByAgeAndMaximumCountAndSortsNewestFirst() {
        val small = ProtectionTimelineRepository(context, TimelineClock { now }, maximumEvents = 3, maximumAgeMs = 10)
        repeat(5) { now++; small.record(ProtectionEventType.ROUTING_REBUILT, ProtectionEventSeverity.INFO, "$it", "$it") }
        assertEquals(listOf("4", "3", "2"), small.getEvents().map { it.title })
        now += 11
        assertTrue(small.getEvents().isEmpty())
    }

    @Test fun clearOnlyDeletesHistoryAndMalformedRecordsAreSkipped() {
        TunnelGuardConfig(context).setProtectionEnabled(true)
        repository.record(ProtectionEventType.ROUTING_REBUILT, ProtectionEventSeverity.INFO, "route", "route")
        repository.clear()
        assertTrue(repository.getEvents().isEmpty())
        assertTrue(TunnelGuardConfig(context).isProtectionEnabled())
        context.getSharedPreferences("protection_timeline", Context.MODE_PRIVATE).edit()
            .putString("events_v1", "[{\"broken\":true}, {not json]").commit()
        assertTrue(repository.getEvents().isEmpty())
    }

    @Test fun stableSequenceOrdersEventsWithIdenticalTimestampsAcrossRecreation() {
        repository.record(ProtectionEventType.VPN_DETECTED, ProtectionEventSeverity.INFO, "first", "first")
        repository.record(ProtectionEventType.BLOCKING_STARTED, ProtectionEventSeverity.INFO, "second", "second")
        val recreated = ProtectionTimelineRepository(context, TimelineClock { now })
        assertEquals(listOf("second", "first"), recreated.getEvents().map { it.title })
        assertNotEquals(recreated.getEvents()[0].id, recreated.getEvents()[1].id)
    }
}
