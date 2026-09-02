package com.tunnelguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectCoordinatorTest {
    private var now = 1_000L
    private val coordinator = AutoConnectCoordinator({ now })

    @Test
    fun `valid VPN completes before timeout and stale timeout is inert`() {
        assertTrue(coordinator.start("target", "vpn"))
        now += 5_000
        assertTrue(coordinator.evaluate(isRelevant = true, requirementSatisfied = true) is AutoConnectCoordinator.Evaluation.Succeeded)
        now += AUTO_CONNECT_TIMEOUT_MS
        assertEquals(AutoConnectCoordinator.Evaluation.None, coordinator.evaluate(true, false))
    }

    @Test
    fun `invalid VPN times out and re-arms exactly once`() {
        coordinator.start("target", "vpn")
        now += AUTO_CONNECT_TIMEOUT_MS
        assertTrue(coordinator.evaluate(true, false) is AutoConnectCoordinator.Evaluation.TimedOut)
        assertEquals(AutoConnectCoordinator.Evaluation.None, coordinator.evaluate(true, false))
        assertNull(coordinator.activeAttempt)
    }

    @Test
    fun `repeated start does not create a launch storm`() {
        assertTrue(coordinator.start("target", "vpn"))
        repeat(20) { assertFalse(coordinator.start("target", "vpn")) }
        assertEquals(1_000L, coordinator.activeAttempt?.startedAtElapsedMs)
    }

    @Test
    fun `wrong or unknown required country remains unsatisfied until timeout`() {
        coordinator.start("target", "vpn")
        val wrong = UpstreamVpnEvaluation.fromObservation(true, "US", "CA")
        assertFalse(wrong.isValid)
        assertTrue(coordinator.evaluate(true, wrong.isValid) is AutoConnectCoordinator.Evaluation.Waiting)
        val unknown = UpstreamVpnEvaluation.fromObservation(true, "US", null)
        assertFalse(unknown.isValid)
        now += AUTO_CONNECT_TIMEOUT_MS
        assertTrue(coordinator.evaluate(true, unknown.isValid) is AutoConnectCoordinator.Evaluation.TimedOut)
    }

    @Test
    fun `matching country completes attempt`() {
        coordinator.start("target", "vpn")
        val matching = UpstreamVpnEvaluation.fromObservation(true, "US", "US")
        assertTrue(coordinator.evaluate(true, matching.isValid) is AutoConnectCoordinator.Evaluation.Succeeded)
    }

    @Test
    fun `settings target and shutdown changes cancel attempt`() {
        coordinator.start("target", "vpn-a")
        assertTrue(coordinator.evaluate(isRelevant = false, requirementSatisfied = false) is AutoConnectCoordinator.Evaluation.Cancelled)
        assertNull(coordinator.activeAttempt)
        coordinator.start("target", "vpn-b")
        assertEquals("target", coordinator.cancel()?.targetPackage)
        assertNull(coordinator.activeAttempt)
    }
}
