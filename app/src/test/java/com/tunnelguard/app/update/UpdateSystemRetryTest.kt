package com.tunnelguard.app.update

import android.app.Activity
import com.tunnelguard.app.TunnelGuardConfig
import com.tunnelguard.app.UpdateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateSystemRetryTest {

    private lateinit var mockActivity: Activity
    private lateinit var mockConfig: TunnelGuardConfig
    private lateinit var updateManager: UpdateManager

    @Before
    fun setUp() {
        mockActivity = mock(Activity::class.java)
        mockConfig = mock(TunnelGuardConfig::class.java)
        updateManager = UpdateManager(mockActivity, mockConfig)
    }

    @Test
    fun testValidateVersionName() {
        assertTrue(updateManager.validateVersionName("1.0.0"))
        assertTrue(updateManager.validateVersionName("2.1.3-beta"))
        assertTrue(updateManager.validateVersionName("v3.0.0_release"))
        assertFalse(updateManager.validateVersionName("1.0.0/../path"))
        assertFalse(updateManager.validateVersionName("version; rm -rf"))
    }
}
