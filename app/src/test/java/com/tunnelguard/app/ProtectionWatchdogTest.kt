package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
class ProtectionWatchdogTest {

    private lateinit var context: Context
    private lateinit var config: TunnelGuardConfig
    private lateinit var mockVpnDetector: VpnDetector
    private lateinit var watchdog: ProtectionWatchdog

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowVpnService.setPrepareResult(null) // Mock VPN permission as granted
        config = TunnelGuardConfig(context)
        mockVpnDetector = mock(VpnDetector::class.java)
        watchdog = ProtectionWatchdog(context, config, mockVpnDetector)
    }

    @Test
    fun testWatchdogHealthyWhenProtectionDisabled() {
        config.setProtectionEnabled(false)
        config.setEmergencyLockEnabled(false)

        val result = watchdog.verifyProtectionHealth(
            isServiceRunning = false,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = null
        )

        assertTrue(result.isHealthy)
        assertNull(result.issueDescription)
    }

    @Test
    fun testWatchdogDetectsMissingTunnelWhenServiceRunningNoUpstreamVpn() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        whenever(mockVpnDetector.detectVpnState(mockCm)).thenReturn(VpnDetectionResult.VPN_NOT_DETECTED)

        val result = watchdog.verifyProtectionHealth(
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = false, // Anomaly: tunnel missing!
            connectivityManager = mockCm
        )

        assertFalse(result.isHealthy)
        assertNotNull(result.issueDescription)
        assertTrue(result.issueDescription!!.contains("Local blackhole tunnel interface is missing"))
    }

    @Test
    fun testWatchdogHealthyWhenUpstreamVpnActive() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        whenever(mockVpnDetector.detectVpnState(mockCm)).thenReturn(VpnDetectionResult.VPN_DETECTED)

        val result = watchdog.verifyProtectionHealth(
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = mockCm
        )

        assertTrue(result.isHealthy)
    }
}
