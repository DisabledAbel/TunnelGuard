package com.tunnelguard.app

import org.junit.Assert.*
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun testIsNewerVersion() {
        // Simple major/minor/patch comparison
        assertTrue(VersionComparator.isNewerVersion("1.0", "1.0.5"))
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "1.0.5"))
        assertTrue(VersionComparator.isNewerVersion("1.0.4", "1.0.5"))

        // Major increment
        assertTrue(VersionComparator.isNewerVersion("1.0.5", "2.0.0"))
        assertTrue(VersionComparator.isNewerVersion("1.5", "2.0"))

        // Equal versions
        assertFalse(VersionComparator.isNewerVersion("1.0.5", "1.0.5"))
        assertFalse(VersionComparator.isNewerVersion("1.0.0", "1.0"))
        assertFalse(VersionComparator.isNewerVersion("1.0", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("1", "1.0.0"))

        // Older versions
        assertFalse(VersionComparator.isNewerVersion("1.0.5", "1.0.4"))
        assertFalse(VersionComparator.isNewerVersion("1.1.0", "1.0.5"))
        assertFalse(VersionComparator.isNewerVersion("2.0", "1.0.5"))

        // Leading 'v' support
        assertTrue(VersionComparator.isNewerVersion("v1.0", "v1.0.5"))
        assertTrue(VersionComparator.isNewerVersion("1.0", "v1.0.5"))
        assertTrue(VersionComparator.isNewerVersion("v1.0.0", "1.0.5"))
        assertFalse(VersionComparator.isNewerVersion("v1.0.5", "v1.0.5"))

        // Edge cases with invalid text or spaces
        assertTrue(VersionComparator.isNewerVersion("1.0", "1.0.5-beta")) // IntOrNull handles non-integers by treating them as 0
    }
}
