package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
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
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class VpnSecurityMatrixTest {

    private lateinit var context: Context
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = TunnelGuardConfig(context)
        config.setProtectionEnabled(false)
        config.setSimulatedVpnEnabled(false)
        config.setEmergencyLockEnabled(false)
        config.setVPNState(VPNState.DISCONNECTED)
        TunnelGuardVpnService.isTunnelEstablished = false
        TunnelGuardVpnService.isServiceStarting = false
        TunnelGuardVpnService.updateServiceState(ServiceState.NO_VPN)
    }

    /**
     * Test the required security matrix:
     * Upstream VPN | TunnelGuard | Detection | Expected
     * OFF          | OFF         | NOT_DETECTED | BLOCK (INACTIVE or UNPROTECTED_FAULT)
     * OFF          | ON          | NOT_DETECTED | BLOCK (BLOCKING)
     * ON           | OFF         | DETECTED     | PROTECTED
     * ON           | ON          | DETECTED     | PROTECTED
     * UNKNOWN      | ON          | UNKNOWN      | BLOCK (BLOCKING)
     * UNKNOWN      | OFF         | UNKNOWN      | BLOCK (INACTIVE / FAULT)
     * VPN disappear| ON          | UNKNOWN/OFF  | BLOCK (BLOCKING)
     * VPN appear   | ON          | DETECTED     | PROTECTED
     */

    @Test
    fun testMatrix_UpstreamOff_TGOff_NotDetected() {
        config.setProtectionEnabled(false)
        val mockCm = mock(ConnectivityManager::class.java)
        whenever(mockCm.allNetworks).thenReturn(emptyArray())

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = false,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = mockCm
        )

        assertEquals(SecurityState.INACTIVE, state)
        // Ensure traffic is NOT allowed as PROTECTED
        assertNotEquals(SecurityState.PROTECTED, state)
    }

    @Test
    fun testMatrix_UpstreamOff_TGOn_NotDetected() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        whenever(mockCm.allNetworks).thenReturn(emptyArray())

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = true, // TunnelGuard local block tunnel is established
            connectivityManager = mockCm
        )

        assertEquals(SecurityState.BLOCKING, state)
    }

    @Test
    fun testMatrix_UpstreamOn_TGOff_Detected() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(Network::class.java)
        val mockCaps = mock(NetworkCapabilities::class.java)
        val mockLp = mock(LinkProperties::class.java)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(mockCaps)
        whenever(mockCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(mockNetwork)).thenReturn(mockLp)

        val mockAddr = mock(LinkAddress::class.java)
        whenever(mockAddr.address).thenReturn(InetAddress.getByName("10.8.0.2"))
        whenever(mockLp.linkAddresses).thenReturn(listOf(mockAddr))

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = false,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = mockCm
        )

        assertEquals(SecurityState.PROTECTED, state)
    }

    @Test
    fun testMatrix_UpstreamOn_TGOn_Detected() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(Network::class.java)
        val mockCaps = mock(NetworkCapabilities::class.java)
        val mockLp = mock(LinkProperties::class.java)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(mockCaps)
        whenever(mockCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(mockNetwork)).thenReturn(mockLp)

        val mockAddr = mock(LinkAddress::class.java)
        whenever(mockAddr.address).thenReturn(InetAddress.getByName("10.8.0.2"))
        whenever(mockLp.linkAddresses).thenReturn(listOf(mockAddr))

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = true,
            connectivityManager = mockCm
        )

        assertEquals(SecurityState.PROTECTED, state)
    }

    @Test
    fun testMatrix_Unknown_TGOn_Unknown_ResultsInBlocking() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(Network::class.java)

        // Network is present, but getNetworkCapabilities returns null -> VPN_UNKNOWN
        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(null)

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = true,
            connectivityManager = mockCm
        )

        // Must fail closed to BLOCKING, never PROTECTED
        assertEquals(SecurityState.BLOCKING, state)
        assertNotEquals(SecurityState.PROTECTED, state)
    }

    @Test
    fun testMatrix_Unknown_TGOff_Unknown_ResultsInInactiveOrFault() {
        config.setProtectionEnabled(false)
        val mockCm = mock(ConnectivityManager::class.java)
        whenever(mockCm.allNetworks).thenThrow(RuntimeException("Connectivity service error"))

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = false,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = mockCm
        )

        assertNotEquals(SecurityState.PROTECTED, state)
        assertEquals(SecurityState.INACTIVE, state)
    }

    @Test
    fun testMatrix_VpnDisappears_TGOn_FailClosedToBlocking() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)

        // Upstream VPN drops -> no networks found
        whenever(mockCm.allNetworks).thenReturn(emptyArray())

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = true,
            connectivityManager = mockCm
        )

        assertEquals(SecurityState.BLOCKING, state)
        assertNotEquals(SecurityState.PROTECTED, state)
    }

    @Test
    fun testMatrix_VpnAppears_TGOn_EntersProtected() {
        config.setProtectionEnabled(true)
        val mockCm = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(Network::class.java)
        val mockCaps = mock(NetworkCapabilities::class.java)
        val mockLp = mock(LinkProperties::class.java)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(mockCaps)
        whenever(mockCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(mockNetwork)).thenReturn(mockLp)

        val mockAddr = mock(LinkAddress::class.java)
        whenever(mockAddr.address).thenReturn(InetAddress.getByName("10.8.0.2"))
        whenever(mockLp.linkAddresses).thenReturn(listOf(mockAddr))

        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = true,
            connectivityManager = mockCm
        )

        assertEquals(SecurityState.PROTECTED, state)
    }

    @Test
    fun testMissingLinkPropertiesResultsInUnknown() {
        val mockCm = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(Network::class.java)
        val mockCaps = mock(NetworkCapabilities::class.java)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(mockCaps)
        whenever(mockCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(mockNetwork)).thenReturn(null) // Missing LinkProperties

        val result = config.detectRealVpnCapabilities(mockCm)
        assertEquals(VpnDetectionResult.VPN_UNKNOWN, result)
    }

    @Test
    fun testEmptyLinkAddressesResultsInUnknown() {
        val mockCm = mock(ConnectivityManager::class.java)
        val mockNetwork = mock(Network::class.java)
        val mockCaps = mock(NetworkCapabilities::class.java)
        val mockLp = mock(LinkProperties::class.java)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNetwork))
        whenever(mockCm.getNetworkCapabilities(mockNetwork)).thenReturn(mockCaps)
        whenever(mockCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(mockNetwork)).thenReturn(mockLp)
        whenever(mockLp.linkAddresses).thenReturn(emptyList()) // Empty link addresses

        val result = config.detectRealVpnCapabilities(mockCm)
        assertEquals(VpnDetectionResult.VPN_UNKNOWN, result)
    }

    @Test
    fun testExceptionsFromConnectivityManagerResultsInUnknownAndFailClosed() {
        val mockCm = mock(ConnectivityManager::class.java)
        whenever(mockCm.allNetworks).thenThrow(SecurityException("Permission denied"))

        val result = config.detectRealVpnCapabilities(mockCm)
        assertEquals(VpnDetectionResult.VPN_UNKNOWN, result)

        config.setProtectionEnabled(true)
        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = true,
            isServiceStarting = false,
            isTunnelEstablished = true,
            connectivityManager = mockCm
        )
        assertEquals(SecurityState.BLOCKING, state)
    }

    @Test
    fun testMultipleVpnInterfacesDetection() {
        val mockCm = mock(ConnectivityManager::class.java)
        val ourNetwork = mock(Network::class.java)
        val ourCaps = mock(NetworkCapabilities::class.java)
        val ourLp = mock(LinkProperties::class.java)

        val ourAddr = mock(LinkAddress::class.java)
        whenever(ourAddr.address).thenReturn(InetAddress.getByName(TunnelGuardConfig.TUNNEL_ADDRESS))
        whenever(ourLp.linkAddresses).thenReturn(listOf(ourAddr))

        whenever(mockCm.getNetworkCapabilities(ourNetwork)).thenReturn(ourCaps)
        whenever(ourCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(ourNetwork)).thenReturn(ourLp)

        val upstreamNetwork = mock(Network::class.java)
        val upstreamCaps = mock(NetworkCapabilities::class.java)
        val upstreamLp = mock(LinkProperties::class.java)

        val upstreamAddr = mock(LinkAddress::class.java)
        whenever(upstreamAddr.address).thenReturn(InetAddress.getByName("10.8.0.5"))
        whenever(upstreamLp.linkAddresses).thenReturn(listOf(upstreamAddr))

        whenever(mockCm.getNetworkCapabilities(upstreamNetwork)).thenReturn(upstreamCaps)
        whenever(upstreamCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        whenever(mockCm.getLinkProperties(upstreamNetwork)).thenReturn(upstreamLp)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(ourNetwork, upstreamNetwork))

        val result = config.detectRealVpnCapabilities(mockCm)
        assertEquals(VpnDetectionResult.VPN_DETECTED, result)
    }
}
