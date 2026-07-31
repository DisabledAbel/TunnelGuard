package com.tunnelguard.app

import android.os.Build
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MainActivityUpdateTest {

    @Before
    fun setUp() {
        MainActivity.hasCheckedForUpdates = false
    }

    @Test
    fun testUpdateCheckTriggeredOnActivityLaunch() {
        // Initially should be false before the activity is created
        org.junit.Assert.assertFalse(MainActivity.hasCheckedForUpdates)

        // Launch MainActivity using Robolectric
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()

        // Verify that hasCheckedForUpdates has been toggled to true
        assertTrue(MainActivity.hasCheckedForUpdates)
    }

    @Test
    fun testVersionComparatorDetectsNewerVersion() {
        // Assert some key comparisons in our update path
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(VersionComparator.isNewerVersion("1.0", "1.0.1"))
        assertTrue(VersionComparator.isNewerVersion("1.0.1", "1.1.0"))
        assertTrue(VersionComparator.isNewerVersion("0.9.9", "1.0.0"))
    }
}
