package com.tunnelguard.app

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.VpnService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.kotlin.*

/**
 * Unit tests for TunnelGuardConfig configuration management and VPN detection logic.
 */
class TunnelGuardConfigTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var config: TunnelGuardConfig

    private val prefsStore = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        // Reset prefs store
        prefsStore.clear()

        // Mock shared preferences retrieval and writing
        whenever(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)

        // Mock Editor returns
        whenever(mockEditor.putString(anyString(), anyOrNull())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String?>(1)
            if (value != null) {
                prefsStore[key] = value
            } else {
                prefsStore.remove(key)
            }
            mockEditor
        }
        whenever(mockEditor.putBoolean(anyString(), anyBoolean())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<Boolean>(1)
            prefsStore[key] = value
            mockEditor
        }
        whenever(mockEditor.putLong(anyString(), anyLong())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<Long>(1)
            prefsStore[key] = value
            mockEditor
        }
        whenever(mockEditor.remove(anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            prefsStore.remove(key)
            mockEditor
        }

        // Mock SharedPreferences get returns with anyOrNull matchers
        whenever(mockPrefs.getString(anyString(), anyOrNull())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<String?>(1)
            (prefsStore[key] as? String) ?: default
        }
        whenever(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<Boolean>(1)
            (prefsStore[key] as? Boolean) ?: default
        }
        whenever(mockPrefs.getLong(anyString(), anyLong())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<Long>(1)
            (prefsStore[key] as? Long) ?: default
        }

        config = TunnelGuardConfig(mockContext).apply {
            elapsedRealtimeProvider = { System.currentTimeMillis() }
        }
    }

    @Test
    fun testProtectedAppsSelection() {
        // Initially "streaming" profile is selected by default, which is pre-populated with default streaming apps.
        // Let's select "custom" profile for clean empty selection testing.
        config.setSelectedProfileId("custom")
        assertTrue(config.getProtectedApps().isEmpty())

        // Protect an app
        config.setAppProtected("com.tivimate.app", true)
        assertTrue(config.isAppProtected("com.tivimate.app"))
        assertEquals(1, config.getProtectedApps().size)
        assertTrue(config.getProtectedApps().contains("com.tivimate.app"))

        // Add another
        config.setAppProtected("org.courville.nova", true)
        assertEquals(2, config.getProtectedApps().size)
        assertTrue(config.getProtectedApps().contains("org.courville.nova"))

        // Unprotect first
        config.setAppProtected("com.tivimate.app", false)
        assertFalse(config.isAppProtected("com.tivimate.app"))
        assertEquals(1, config.getProtectedApps().size)
    }

    @Test
    fun testStartupBehaviorConfiguration() {
        assertFalse(config.isStartOnBootEnabled())

        config.setStartOnBootEnabled(true)
        assertTrue(config.isStartOnBootEnabled())

        config.setStartOnBootEnabled(false)
        assertFalse(config.isStartOnBootEnabled())
    }

    @Test
    fun testVpnSimulationToggle() {
        assertFalse(config.isSimulatedVpnEnabled())

        config.setSimulatedVpnEnabled(true)
        assertTrue(config.isSimulatedVpnEnabled())
    }

    @Test
    fun testHasNotificationPermissionOnLegacySdk() {
        assertTrue(config.hasNotificationPermission())
    }

    @Test
    fun testProtectionStateTransitions() {
        config.setProtectionEnabled(false)
        assertEquals(ProtectionState.INACTIVE, config.getProtectionState())

        config.setProtectionEnabled(true)

        config.setVPNState(VPNState.CONNECTED)
        assertEquals(ProtectionState.ACTIVE, config.getProtectionState())

        config.setVPNState(VPNState.DISCONNECTED)
        assertEquals(ProtectionState.BLOCKING, config.getProtectionState())

        config.setVPNState(VPNState.ERROR)
        assertEquals(ProtectionState.BLOCKING, config.getProtectionState())

        config.setVPNState(VPNState.PROTECTED)
        assertEquals(ProtectionState.ACTIVE, config.getProtectionState())
    }

    @Test
    fun testEmergencyLockState() {
        assertFalse(config.isEmergencyLockEnabled())

        config.setEmergencyLockEnabled(true)
        assertTrue(config.isEmergencyLockEnabled())
        assertEquals(ProtectionState.BLOCKING, config.getProtectionState())

        config.setEmergencyLockEnabled(false)
        assertFalse(config.isEmergencyLockEnabled())
    }

    @Test
    fun testProfilesManagement() {
        val initialProfiles = config.getProfiles()
        assertEquals(3, initialProfiles.size) // streaming, everything, custom

        // Create Profile
        val newId = config.createProfile("Gaming Profile")
        val updatedProfiles = config.getProfiles()
        assertEquals(4, updatedProfiles.size)
        assertNotNull(updatedProfiles.find { it.id == newId })

        // Rename Profile
        config.renameProfile(newId, "Heavy Gaming")
        val renamedProfiles = config.getProfiles()
        assertEquals("Heavy Gaming", renamedProfiles.find { it.id == newId }?.name)

        // Delete Profile
        config.deleteProfile(newId)
        val finalProfiles = config.getProfiles()
        assertEquals(3, finalProfiles.size)
        assertNull(finalProfiles.find { it.id == newId })
    }

    @Test
    fun testDNSStatusDetectionInSimulation() {
        config.setSimulatedVpnEnabled(true)

        config.setVPNState(VPNState.CONNECTED)
        assertEquals(DNSStatus.PROTECTED, config.detectDnsStatus(null, false))

        config.setVPNState(VPNState.DISCONNECTED)
        config.setProtectionEnabled(true)
        assertEquals(DNSStatus.PROTECTED, config.detectDnsStatus(null, false))

        config.setProtectionEnabled(false)
        assertEquals(DNSStatus.WARNING, config.detectDnsStatus(null, false))
    }

    @Test
    fun testLoggingOperations() {
        assertTrue(config.getLogs().isEmpty())

        config.addLog("Test log entry 1")
        config.addLog("Test log entry 2")

        val logs = config.getLogs()
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("Test log entry 2"))

        config.clearLogs()
        assertTrue(config.getLogs().isEmpty())
    }

    @Test
    fun testAppVersionName() {
        whenever(mockContext.packageName).thenReturn("com.tunnelguard.app")

        val mockPackageManager = mock(PackageManager::class.java)
        val mockPackageInfo = android.content.pm.PackageInfo().apply {
            versionName = "1.0.0"
        }
        whenever(mockContext.packageManager).thenReturn(mockPackageManager)
        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(0))).thenReturn(mockPackageInfo)

        whenever(mockPrefs.getString(eq("override_version_name"), eq(null))).thenAnswer {
            prefsStore["override_version_name"] as? String
        }

        assertEquals("1.0.0", config.getAppVersionName())

        config.setAppVersionName("1.1.0")
        assertEquals("1.1.0", config.getAppVersionName())
    }

    @Test
    fun testTunnelAddressIsCorrect() {
        assertEquals("10.0.0.1", TunnelGuardConfig.TUNNEL_ADDRESS)
    }

    @Test
    fun testAppMonitorPreferences() {
        // Default should be false
        assertFalse(config.isAppMonitorEnabled())

        // Enable monitor
        config.setAppMonitorEnabled(true)
        assertTrue(config.isAppMonitorEnabled())

        // Disable monitor
        config.setAppMonitorEnabled(false)
        assertFalse(config.isAppMonitorEnabled())
    }

    @Test
    fun testVpnAppOfChoicePreference() {
        // Default should be null
        assertNull(config.getVpnAppOfChoice())

        // Set VPN app of choice package name
        config.setVpnAppOfChoice("com.protonvpn.android")
        assertEquals("com.protonvpn.android", config.getVpnAppOfChoice())

        // Clear VPN app of choice
        config.setVpnAppOfChoice(null)
        assertNull(config.getVpnAppOfChoice())
    }

    @Test
    fun testAutoConnectVpnPreference() {
        // Default should be true
        assertTrue(config.isAutoConnectVpnEnabled())

        // Disable auto-connect
        config.setAutoConnectVpnEnabled(false)
        assertFalse(config.isAutoConnectVpnEnabled())

        // Re-enable auto-connect
        config.setAutoConnectVpnEnabled(true)
        assertTrue(config.isAutoConnectVpnEnabled())
    }

    @Test
    fun testUsageStatsPermissionCheck() {
        val mockAppOpsManager = mock(android.app.AppOpsManager::class.java)
        whenever(mockContext.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mockAppOpsManager)
        whenever(mockContext.packageName).thenReturn("com.tunnelguard.app")

        val mockProcess = org.mockito.Mockito.mockStatic(android.os.Process::class.java)
        try {
            mockProcess.`when`<Int> { android.os.Process.myUid() }.thenReturn(10001)

            // Mock allowed state
            whenever(mockAppOpsManager.checkOpNoThrow(
                eq(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS),
                eq(10001),
                eq("com.tunnelguard.app")
            )).thenReturn(android.app.AppOpsManager.MODE_ALLOWED)

            whenever(mockAppOpsManager.unsafeCheckOpNoThrow(
                eq(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS),
                eq(10001),
                eq("com.tunnelguard.app")
            )).thenReturn(android.app.AppOpsManager.MODE_ALLOWED)

            assertTrue(config.hasUsageStatsPermission(mockContext))

            // Mock ignored/denied state
            whenever(mockAppOpsManager.checkOpNoThrow(
                eq(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS),
                eq(10001),
                eq("com.tunnelguard.app")
            )).thenReturn(android.app.AppOpsManager.MODE_IGNORED)

            whenever(mockAppOpsManager.unsafeCheckOpNoThrow(
                eq(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS),
                eq(10001),
                eq("com.tunnelguard.app")
            )).thenReturn(android.app.AppOpsManager.MODE_IGNORED)

            assertFalse(config.hasUsageStatsPermission(mockContext))
        } finally {
            mockProcess.close()
        }
    }

    @Test
    fun testForcedUpdatesConfiguration() {
        // Default should be true
        assertTrue(config.isForcedUpdatesEnabled())

        // Toggle to false
        config.setForcedUpdatesEnabled(false)
        assertFalse(config.isForcedUpdatesEnabled())

        // Toggle back to true
        config.setForcedUpdatesEnabled(true)
        assertTrue(config.isForcedUpdatesEnabled())
    }

    @Test
    fun testLastDisconnectReason() {
        // Default should be "None"
        assertEquals("None", config.getLastDisconnectReason())

        // Set reason
        config.setLastDisconnectReason("Loss of network connectivity")
        assertEquals("Loss of network connectivity", config.getLastDisconnectReason())

        // Set another reason
        config.setLastDisconnectReason("User stopped protection")
        assertEquals("User stopped protection", config.getLastDisconnectReason())
    }

    @Test
    fun testConnectionUptimeCalculation() {
        var currentTime = 10000L
        config.elapsedRealtimeProvider = { currentTime }

        config.setVPNState(VPNState.DISCONNECTED)
        assertEquals(0L, config.getConnectionUptimeMillis())

        // Transition to CONNECTED should set start time (10000L)
        config.setVPNState(VPNState.CONNECTED)
        assertEquals(0L, config.getConnectionUptimeMillis())

        // Advance time by 5000ms
        currentTime += 5000L
        assertEquals(5000L, config.getConnectionUptimeMillis())

        // Verify that setting VPNState again does not overwrite start time
        val firstStartTime = prefsStore["vpn_connection_start_time"] as Long
        assertEquals(10000L, firstStartTime)
        config.setVPNState(VPNState.CONNECTED)
        val secondStartTime = prefsStore["vpn_connection_start_time"] as Long
        assertEquals(firstStartTime, secondStartTime)

        // Advance time by another 5000ms (total 10000ms elapsed since start)
        currentTime += 5000L

        // Transition to BLOCKED (TunnelGuard local tunnel active) should maintain start time
        config.setVPNState(VPNState.BLOCKED)
        val blockedStartTime = prefsStore["vpn_connection_start_time"] as Long
        assertEquals(firstStartTime, blockedStartTime)
        assertEquals(10000L, config.getConnectionUptimeMillis())

        // Advance time by another 5000ms (total 15000ms elapsed since start)
        currentTime += 5000L

        // Transition from BLOCKED to PROTECTED should also preserve the original start time
        config.setVPNState(VPNState.PROTECTED)
        val protectedStartTime = prefsStore["vpn_connection_start_time"] as Long
        assertEquals(firstStartTime, protectedStartTime)
        assertEquals(15000L, config.getConnectionUptimeMillis())

        // Transition to DISCONNECTED should reset start time
        config.setVPNState(VPNState.DISCONNECTED)
        assertEquals(0L, config.getConnectionUptimeMillis())

        // Test reboot recovery where SystemClock elapsed realtime is smaller than stored start time
        currentTime = 100000L
        config.setVPNState(VPNState.BLOCKED)
        assertEquals(100000L, prefsStore["vpn_connection_start_time"] as Long)

        // Simulate device reboot (elapsedRealtime Provider resets to a smaller value)
        currentTime = 5000L
        assertEquals(0L, config.getConnectionUptimeMillis())
        assertEquals(0L, prefsStore["vpn_connection_start_time"] as Long)
    }

    @Test
    fun testBackupAndRestoreValidation() {
        config.setSelectedProfileId("custom")
        config.setStartOnBootEnabled(true)
        config.setAppMonitorEnabled(true)
        config.setForcedUpdatesEnabled(false)
        config.setAutoConnectVpnEnabled(false)

        val rawJsonStr = """
            {
                "start_on_boot": false,
                "app_monitor_enabled": false,
                "forced_updates_enabled": true,
                "auto_connect_vpn_enabled": false,
                "selected_profile_id": "custom",
                "protection_profiles": [
                    {
                        "id": "custom",
                        "name": "Custom",
                        "isSystem": true,
                        "apps": ["com.valid.app", "invalid..app", "another.valid.one", ""]
                    }
                ]
            }
        """.trimIndent()

        val success = config.importConfigFromJson(rawJsonStr)
        assertTrue(success)

        assertFalse(config.isStartOnBootEnabled())
        assertFalse(config.isAppMonitorEnabled())
        assertTrue(config.isForcedUpdatesEnabled())
        assertFalse(config.isAutoConnectVpnEnabled())
        assertEquals("custom", config.getSelectedProfileId())

        val protectedApps = config.getProtectedApps()
        assertEquals(2, protectedApps.size)
        assertTrue(protectedApps.contains("com.valid.app"))
        assertTrue(protectedApps.contains("another.valid.one"))
        assertFalse(protectedApps.contains("invalid..app"))
        assertFalse(protectedApps.contains(""))

        val exportedJson = config.exportConfigToJson()
        assertNotNull(exportedJson)
        assertTrue(exportedJson!!.contains("start_on_boot"))
        assertTrue(exportedJson.contains("app_monitor_enabled"))
        assertTrue(exportedJson.contains("auto_connect_vpn_enabled"))
        assertTrue(exportedJson.contains("protection_profiles"))

        // Test fallback when auto_connect_vpn_enabled is absent in JSON
        val jsonWithoutAutoConnect = """
            {
                "start_on_boot": false
            }
        """.trimIndent()
        config.importConfigFromJson(jsonWithoutAutoConnect)
        assertTrue(config.isAutoConnectVpnEnabled())
    }

    @Test
    fun testMultipleVpnNetworksWithCountryMatching() {
        config.setCountryVpnSettingEnabled(true)
        config.setCountryVpnTargetCountry("US")

        val mockCm = mock(ConnectivityManager::class.java)
        val mockNet1 = mock(Network::class.java)
        val mockNet2 = mock(Network::class.java)

        val mockCaps1 = mock(NetworkCapabilities::class.java)
        whenever(mockCaps1.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)

        val mockCaps2 = mock(NetworkCapabilities::class.java)
        whenever(mockCaps2.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)

        whenever(mockCm.allNetworks).thenReturn(arrayOf(mockNet1, mockNet2))
        whenever(mockCm.getNetworkCapabilities(mockNet1)).thenReturn(mockCaps1)
        whenever(mockCm.getNetworkCapabilities(mockNet2)).thenReturn(mockCaps2)

        // Resolver returns "CA" for net1 (mismatch) and "US" for net2 (match)
        val resolver: (Network) -> String? = { net ->
            if (net == mockNet1) "CA" else "US"
        }

        val result = config.detectRealVpnCapabilities(mockCm, networkCountryResolver = resolver)
        assertEquals(VpnDetectionResult.VPN_DETECTED, result)
    }

    @Test
    fun testCountryVpnPreferencesAndMatching() {
        // Defaults
        assertFalse(config.isCountryVpnSettingEnabled())
        assertEquals("US", config.getCountryVpnTargetCountry())
        assertEquals("", config.getActiveVpnCountryCode())

        // Enable setting
        config.setCountryVpnSettingEnabled(true)
        assertTrue(config.isCountryVpnSettingEnabled())

        // Set target country
        config.setCountryVpnTargetCountry("gb")
        assertEquals("GB", config.getCountryVpnTargetCountry())

        // Set active country
        config.setActiveVpnCountryCode("gb")
        assertEquals("GB", config.getActiveVpnCountryCode())

        // Test matching
        assertTrue(config.isCountryVpnMatch("GB"))
        assertTrue(config.isCountryVpnMatch("gb "))
        assertFalse(config.isCountryVpnMatch("US"))
        assertFalse(config.isCountryVpnMatch(""))

        // Test Target ANY
        config.setCountryVpnTargetCountry("ANY")
        assertTrue(config.isCountryVpnMatch("CA"))

        // Test setting disabled returns true regardless of country
        config.setCountryVpnSettingEnabled(false)
        config.setCountryVpnTargetCountry("DE")
        assertTrue(config.isCountryVpnMatch("JP"))
    }

    @Test
    fun testOnboardingConfigPersistence() {
        assertFalse(config.isOnboardingCompleted())
        config.setOnboardingCompleted(true)
        assertTrue(config.isOnboardingCompleted())
    }

    @Test
    fun testIpv6ProtectionStatePersistence() {
        assertFalse(config.isIpv6ProtectionActive())
        config.setIpv6ProtectionActive(true)
        assertTrue(config.isIpv6ProtectionActive())
    }

    @Test
    fun testDeterministicSecurityStateMachine() {
        val mockConnectivity = mock(ConnectivityManager::class.java)
        val mockVpnStatic: org.mockito.MockedStatic<VpnService> = org.mockito.Mockito.mockStatic(VpnService::class.java)
        try {
            mockVpnStatic.`when`<Intent> { VpnService.prepare(any()) }.thenReturn(null)

            config.setProtectionEnabled(false)
            config.setEmergencyLockEnabled(false)
            var state = SecurityStateMachine.getSecurityState(mockContext, config, false, false, false, mockConnectivity)
            assertEquals(SecurityState.INACTIVE, state)

            config.setEmergencyLockEnabled(true)
            config.setVPNState(VPNState.BLOCKED)
            state = SecurityStateMachine.getSecurityState(mockContext, config, true, false, true, mockConnectivity)
            assertEquals(SecurityState.BLOCKING, state)

            state = SecurityStateMachine.getSecurityState(mockContext, config, true, true, false, mockConnectivity)
            assertEquals(SecurityState.CONNECTING, state)

            config.setEmergencyLockEnabled(false)
            config.setProtectionEnabled(true)
            config.setSimulatedVpnEnabled(true)

            config.setVPNState(VPNState.PROTECTED)
            state = SecurityStateMachine.getSecurityState(mockContext, config, true, false, false, mockConnectivity)
            assertEquals(SecurityState.PROTECTED, state)

            config.setVPNState(VPNState.DISCONNECTED)
            state = SecurityStateMachine.getSecurityState(mockContext, config, true, false, false, mockConnectivity)
            assertEquals(SecurityState.BLOCKING, state)

            config.setSimulatedVpnEnabled(false)
            val mockNet = mock(Network::class.java)
            val mockCaps = mock(NetworkCapabilities::class.java)
            val mockLinkProps = mock(LinkProperties::class.java)
            val mockLinkAddr = mock(android.net.LinkAddress::class.java)
            whenever(mockLinkAddr.address).thenReturn(java.net.InetAddress.getByName("10.8.0.2"))
            whenever(mockLinkProps.linkAddresses).thenReturn(listOf(mockLinkAddr))

            whenever(mockConnectivity.allNetworks).thenReturn(arrayOf(mockNet))
            whenever(mockConnectivity.getNetworkCapabilities(mockNet)).thenReturn(mockCaps)
            whenever(mockCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
            whenever(mockConnectivity.getLinkProperties(mockNet)).thenReturn(mockLinkProps)

            state = SecurityStateMachine.getSecurityState(mockContext, config, true, false, false, mockConnectivity)
            assertEquals(SecurityState.PROTECTED, state)
        } finally {
            mockVpnStatic.close()
        }
    }

    @Test
    fun testPendingVpnRedirectTarget_setGetClearAndExpiration() {
        val testPkg = "com.example.targetapp"

        // Test initial state is null
        assertNull(config.getPendingVpnRedirectTarget())

        // Test set and get
        config.setPendingVpnRedirectTarget(testPkg)
        assertEquals(testPkg, config.getPendingVpnRedirectTarget())

        // Test explicit clear
        config.clearPendingVpnRedirectTarget()
        assertNull(config.getPendingVpnRedirectTarget())

        // Test setting null clears
        config.setPendingVpnRedirectTarget(testPkg)
        assertEquals(testPkg, config.getPendingVpnRedirectTarget())
        config.setPendingVpnRedirectTarget(null)
        assertNull(config.getPendingVpnRedirectTarget())

        // Test expiration after timeout (> 180,000 ms)
        val pastTimestamp = System.currentTimeMillis() - 200000L
        mockPrefs.edit()
            .putString("pending_vpn_redirect_target", testPkg)
            .putLong("pending_vpn_redirect_timestamp", pastTimestamp)
            .apply()

        assertNull(config.getPendingVpnRedirectTarget())

        // Test future timestamp invalidation (> now)
        val futureTimestamp = System.currentTimeMillis() + 60000L
        mockPrefs.edit()
            .putString("pending_vpn_redirect_target", testPkg)
            .putLong("pending_vpn_redirect_timestamp", futureTimestamp)
            .apply()

        assertNull(config.getPendingVpnRedirectTarget())

        // Test invalid <= 0 timestamp
        mockPrefs.edit()
            .putString("pending_vpn_redirect_target", testPkg)
            .putLong("pending_vpn_redirect_timestamp", 0L)
            .apply()

        assertNull(config.getPendingVpnRedirectTarget())
    }
}