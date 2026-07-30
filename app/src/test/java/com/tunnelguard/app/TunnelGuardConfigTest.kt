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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.kotlin.*

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

        config = TunnelGuardConfig(mockContext)
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
}