package com.tunnelguard.app

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class TunnelGuardConfigRobolectricTest {

    private lateinit var context: Context
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = TunnelGuardConfig(context)
    }

    @Test
    fun testUsageStatsPermissionCheckQAllowed() {
        val spyContext = spy(context)
        val mockAppOpsManager = mock(AppOpsManager::class.java)

        whenever(spyContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mockAppOpsManager)

        // Mock the return value of unsafeCheckOpNoThrow
        whenever(mockAppOpsManager.unsafeCheckOpNoThrow(
            eq(AppOpsManager.OPSTR_GET_USAGE_STATS),
            any(),
            any()
        )).thenReturn(AppOpsManager.MODE_ALLOWED)

        assertTrue(config.hasUsageStatsPermission(spyContext))
    }

    @Test
    fun testUsageStatsPermissionCheckQDenied() {
        val spyContext = spy(context)
        val mockAppOpsManager = mock(AppOpsManager::class.java)

        whenever(spyContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mockAppOpsManager)

        // Mock the return value of unsafeCheckOpNoThrow to be ignored/denied
        whenever(mockAppOpsManager.unsafeCheckOpNoThrow(
            eq(AppOpsManager.OPSTR_GET_USAGE_STATS),
            any(),
            any()
        )).thenReturn(AppOpsManager.MODE_IGNORED)

        assertFalse(config.hasUsageStatsPermission(spyContext))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun testHasNotificationPermissionTiramisuGranted() {
        val spyContext = spy(context)
        whenever(spyContext.checkPermission(
            eq(android.Manifest.permission.POST_NOTIFICATIONS),
            any(),
            any()
        )).thenReturn(android.content.pm.PackageManager.PERMISSION_GRANTED)

        val configWithSpy = TunnelGuardConfig(spyContext)
        assertTrue(configWithSpy.hasNotificationPermission())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun testHasNotificationPermissionTiramisuDenied() {
        val spyContext = spy(context)
        whenever(spyContext.checkPermission(
            eq(android.Manifest.permission.POST_NOTIFICATIONS),
            any(),
            any()
        )).thenReturn(android.content.pm.PackageManager.PERMISSION_DENIED)

        val configWithSpy = TunnelGuardConfig(spyContext)
        assertFalse(configWithSpy.hasNotificationPermission())
    }
}
