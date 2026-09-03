package com.tunnelguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationStateTest {
    @Test fun protectedStateIncludesCount() {
        val state = select(upstreamEvaluation = UpstreamVpnEvaluation.Valid(), protectedAppCount = 5)
        assertEquals(ForegroundNotificationType.PROTECTED, state.type)
        assertEquals("TunnelGuard • Protected", state.title)
        assertEquals("5 protected apps", state.message)
    }

    @Test fun zeroProtectedAppsHasAnEmptyStateRegardlessOfVpnStatus() {
        val state = select(upstreamEvaluation = UpstreamVpnEvaluation.Valid(), protectedAppCount = 0)
        assertEquals(ForegroundNotificationType.EMPTY, state.type)
        assertEquals("TunnelGuard • No Protected Apps", state.title)
        assertEquals("Choose apps to protect", state.message)
    }

    @Test fun blockingUsesHumanReadableForegroundLabel() {
        val state = select(foregroundAppLabel = "TiviMate", blocking = true)
        assertEquals(ForegroundNotificationType.BLOCKING, state.type)
        assertEquals("Blocking TiviMate to prevent a network leak", state.message)
    }

    @Test fun emergencyLockTakesPriorityAndIncludesCount() {
        val state = select(emergencyLock = true, protectedAppCount = 5)
        assertEquals("TunnelGuard • Emergency Lock", state.title)
        assertEquals("Blocking 5 protected apps", state.message)
    }

    @Test fun countryMismatchUsesFriendlyCountryNames() {
        val state = select(
            foregroundAppLabel = "TiviMate",
            upstreamEvaluation = UpstreamVpnEvaluation.CountryMismatch("US", "CA")
        )
        assertEquals(ForegroundNotificationType.COUNTRY_MISMATCH, state.type)
        assertEquals("TiviMate requires United States • VPN is Canada", state.message)
    }

    @Test fun startingState() {
        val state = select(starting = true)
        assertEquals("TunnelGuard • Starting", state.title)
        assertEquals("Checking VPN and protection state…", state.message)
    }

    @Test fun problemDoesNotExposeImplementationDetails() {
        val state = select(problem = "Another VPN took control of the VPN connection")
        assertEquals(ForegroundNotificationType.PROBLEM, state.type)
        assertEquals("TunnelGuard • Protection Problem", state.title)
        assertEquals("Another VPN took control of the VPN connection", state.message)
    }

    @Test fun connectingNamesTargetApp() {
        val state = select(autoConnecting = true, foregroundAppLabel = "TiviMate")
        assertEquals(ForegroundNotificationType.CONNECTING, state.type)
        assertEquals("Waiting for VPN protection for TiviMate", state.message)
    }

    @Test fun identicalStatesAreNotReposted() {
        val tracker = ForegroundNotificationChangeTracker()
        val state = select(upstreamEvaluation = UpstreamVpnEvaluation.Valid())
        assertTrue(tracker.shouldNotify(state))
        assertFalse(tracker.shouldNotify(state.copy()))
    }

    @Test fun nonRenderedLabelChangeIsNotReposted() {
        val tracker = ForegroundNotificationChangeTracker()
        val first = select(upstreamEvaluation = UpstreamVpnEvaluation.Valid(), foregroundAppLabel = "TiviMate")
        val sameContent = select(upstreamEvaluation = UpstreamVpnEvaluation.Valid(), foregroundAppLabel = "Kodi")
        assertEquals(first.title, sameContent.title)
        assertEquals(first.message, sameContent.message)
        assertTrue(tracker.shouldNotify(first))
        assertFalse(tracker.shouldNotify(sameContent))
    }

    @Test fun protectedBlockedProtectedTransitionsArePosted() {
        val tracker = ForegroundNotificationChangeTracker()
        val protected = select(upstreamEvaluation = UpstreamVpnEvaluation.Valid())
        val blocked = select(blocking = true)
        assertTrue(tracker.shouldNotify(protected))
        assertTrue(tracker.shouldNotify(blocked))
        assertTrue(tracker.shouldNotify(protected))
    }

    private fun select(
        starting: Boolean = false,
        protectedAppCount: Int = 1,
        foregroundAppLabel: String? = null,
        upstreamEvaluation: UpstreamVpnEvaluation? = UpstreamVpnEvaluation.Missing,
        blocking: Boolean = false,
        emergencyLock: Boolean = false,
        autoConnecting: Boolean = false,
        problem: String? = null
    ) = ForegroundNotificationStateSelector.select(
        ForegroundNotificationFacts(starting, protectedAppCount, foregroundAppLabel, upstreamEvaluation, blocking, emergencyLock, autoConnecting, problem)
    )
}
