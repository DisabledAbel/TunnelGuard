package com.tunnelguard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsActivityTest {

    @Test
    fun testCalculateNextVersionWithThreeParts() {
        // Test standard 3-part version (major.minor.patch)
        assertEquals("1.0.6", SettingsActivity.calculateNextVersion("1.0.5"))
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion("1.0.0"))
        assertEquals("2.1.10", SettingsActivity.calculateNextVersion("2.1.9"))
    }

    @Test
    fun testCalculateNextVersionWithTwoParts() {
        // Test 2-part version (major.minor)
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion("1.0"))
        assertEquals("2.5.1", SettingsActivity.calculateNextVersion("2.5"))
    }

    @Test
    fun testCalculateNextVersionWithSingleOrInvalidParts() {
        // Test fallback for single part or empty or invalid values
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion("1"))
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion(""))
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion("abc"))
    }

    @Test
    fun testCalculateNextVersionWithNonNumericPatch() {
        // Test fallback for non-numeric patch level
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion("1.0.beta"))
        assertEquals("1.0.1", SettingsActivity.calculateNextVersion("1.0.5-beta"))
    }
}
