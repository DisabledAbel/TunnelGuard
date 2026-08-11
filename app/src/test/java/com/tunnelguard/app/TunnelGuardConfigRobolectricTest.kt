package com.tunnelguard.app

import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    @Test
    fun testServiceStateTransitions() {
        // Initially should be NO_VPN
        assertEquals(ServiceState.NO_VPN, TunnelGuardVpnService.currentServiceState)

        // Try transitioning
        TunnelGuardVpnService.updateServiceState(ServiceState.TUNNELGUARD_STARTING)
        assertEquals(ServiceState.TUNNELGUARD_STARTING, TunnelGuardVpnService.currentServiceState)

        TunnelGuardVpnService.updateServiceState(ServiceState.TUNNELGUARD_ACTIVE)
        assertEquals(ServiceState.TUNNELGUARD_ACTIVE, TunnelGuardVpnService.currentServiceState)

        // Reset
        TunnelGuardVpnService.updateServiceState(ServiceState.NO_VPN)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun testDetectRealVpnCapabilitiesOnAndroidR() {
        val mockConnectivityManager = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(android.net.Network::class.java)
        val mockCapabilities = mock(NetworkCapabilities::class.java)

        whenever(mockConnectivityManager.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork)).thenReturn(mockCapabilities)
        whenever(mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)

        // 1. If it's our own VPN, it should skip it and return false
        whenever(mockCapabilities.ownerUid).thenReturn(android.os.Process.myUid())
        assertFalse(config.detectRealVpnCapabilities(mockConnectivityManager))

        // 2. If it's another VPN (different ownerUid), it should detect it as upstream and return true
        whenever(mockCapabilities.ownerUid).thenReturn(android.os.Process.myUid() + 1)
        assertTrue(config.detectRealVpnCapabilities(mockConnectivityManager))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun testDetectRealVpnCapabilitiesOnLegacySdk() {
        val mockConnectivityManager = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(android.net.Network::class.java)
        val mockCapabilities = mock(NetworkCapabilities::class.java)

        whenever(mockConnectivityManager.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork)).thenReturn(mockCapabilities)
        whenever(mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)

        // 1. If our tunnel is established, it should skip it and return false
        TunnelGuardVpnService.isTunnelEstablished = true
        assertFalse(config.detectRealVpnCapabilities(mockConnectivityManager))

        // 2. If tunnel is not established, it should check link addresses (our fallback)
        TunnelGuardVpnService.isTunnelEstablished = false
        // Without mock link addresses, isOurOurVpn returns false, so it should return true
        assertTrue(config.detectRealVpnCapabilities(mockConnectivityManager))
    }
}
