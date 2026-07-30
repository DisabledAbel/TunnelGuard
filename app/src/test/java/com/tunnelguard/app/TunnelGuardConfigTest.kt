package com.tunnelguard.app

import android.content.Context
import android.content.SharedPreferences
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
        whenever(mockEditor.putString(anyString(), anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String>(1)
            prefsStore[key] = value
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

        // Mock SharedPreferences get returns
        whenever(mockPrefs.getString(anyString(), anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<String>(1)
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
        // Initially empty
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
        // Default should be false
        assertFalse(config.isStartOnBootEnabled())

        config.setStartOnBootEnabled(true)
        assertTrue(config.isStartOnBootEnabled())

        config.setStartOnBootEnabled(false)
        assertFalse(config.isStartOnBootEnabled())
    }

    @Test
    fun testVpnSimulationToggle() {
        // Default is false
        assertFalse(config.isSimulatedVpnEnabled())

        config.setSimulatedVpnEnabled(true)
        assertTrue(config.isSimulatedVpnEnabled())
    }

    @Test
    fun testProtectionStateTransitions() {
        // Initially disabled (INACTIVE)
        config.setProtectionEnabled(false)
        assertEquals(ProtectionState.INACTIVE, config.getProtectionState())

        // Enable master protection switch
        config.setProtectionEnabled(true)

        // Case 1: VPN State is CONNECTED -> Protection state is ACTIVE (not blocking)
        config.setVPNState(VPNState.CONNECTED)
        assertEquals(ProtectionState.ACTIVE, config.getProtectionState())

        // Case 2: VPN State is DISCONNECTED -> Protection state shifts to BLOCKING
        config.setVPNState(VPNState.DISCONNECTED)
        assertEquals(ProtectionState.BLOCKING, config.getProtectionState())

        // Case 3: VPN State is ERROR -> Protection state is BLOCKING
        config.setVPNState(VPNState.ERROR)
        assertEquals(ProtectionState.BLOCKING, config.getProtectionState())

        // Case 4: VPN State is PROTECTED -> Protection state is ACTIVE
        config.setVPNState(VPNState.PROTECTED)
        assertEquals(ProtectionState.ACTIVE, config.getProtectionState())
    }

    @Test
    fun testLoggingOperations() {
        assertTrue(config.getLogs().isEmpty())

        config.addLog("Test log entry 1")
        config.addLog("Test log entry 2")

        val logs = config.getLogs()
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("Test log entry 2")) // Newest log entry at index 0

        config.clearLogs()
        assertTrue(config.getLogs().isEmpty())
    }

    @Test
    fun testAppVersionName() {
        // Mock packageName, packageManager, and getPackageInfo successfully returning PackageInfo with versionName "1.0.0"
        whenever(mockContext.packageName).thenReturn("com.tunnelguard.app")

        val mockPackageManager = mock(android.content.pm.PackageManager::class.java)
        val mockPackageInfo = android.content.pm.PackageInfo().apply {
            versionName = "1.0.0"
        }
        whenever(mockContext.packageManager).thenReturn(mockPackageManager)
        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(0))).thenReturn(mockPackageInfo)

        whenever(mockPrefs.getString(eq("override_version_name"), eq(null))).thenAnswer {
            prefsStore["override_version_name"] as? String
        }

        // Initially no override exists, so it should return the fallback / default version from PackageManager lookup
        assertEquals("1.0.0", config.getAppVersionName())

        // Set an override version name
        config.setAppVersionName("1.1.0")
        assertEquals("1.1.0", config.getAppVersionName())

        // Override with another version
        config.setAppVersionName("1.2.0")
        assertEquals("1.2.0", config.getAppVersionName())
    }

    @Test
    fun testTunnelAddressIsCorrect() {
        assertEquals("10.0.0.1", TunnelGuardConfig.TUNNEL_ADDRESS)
    }
}
