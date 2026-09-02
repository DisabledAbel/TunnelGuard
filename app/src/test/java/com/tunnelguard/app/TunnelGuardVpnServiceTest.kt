package com.tunnelguard.app

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class TunnelGuardVpnServiceTest {

    @Test
    fun testIntentActionsValidity() {
        assertEquals("com.tunnelguard.app.START", TunnelGuardVpnService.ACTION_START)
        assertEquals("com.tunnelguard.app.STOP", TunnelGuardVpnService.ACTION_STOP)
        assertEquals("com.tunnelguard.app.UPDATE", TunnelGuardVpnService.ACTION_UPDATE)
    }

    @Test
    fun testStateEnums() {
        // Verify VPNState values exist and conform to requested API
        val states = VPNState.values().map { it.name }
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.contains("CONNECTING"))
        assertTrue(states.contains("DISCONNECTED"))
        assertTrue(states.contains("ERROR"))
        assertTrue(states.contains("PROTECTED"))
        assertTrue(states.contains("BLOCKED"))

        // Verify ProtectionState values
        val protectionStates = ProtectionState.values().map { it.name }
        assertTrue(protectionStates.contains("ACTIVE"))
        assertTrue(protectionStates.contains("BLOCKING"))
        assertTrue(protectionStates.contains("INACTIVE"))
    }

    @Test
    fun emergencyLockDoesNotYieldToCountryMismatch() {
        val mismatch = UpstreamVpnEvaluation.CountryMismatch("US", "CA")

        assertFalse(TunnelGuardVpnService.shouldEnterVpnConflict(mismatch, emergencyLock = true))
        assertTrue(TunnelGuardVpnService.shouldEnterVpnConflict(mismatch, emergencyLock = false))
    }
}
