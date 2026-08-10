package com.tunnelguard.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
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
class TunnelGuardVpnServiceWarningTest {

    private lateinit var context: Context
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = TunnelGuardConfig(context)
    }

    @Test
    fun testGetForegroundPackageNameWithUsageEvents() {
        val spyContext = spy(context)
        val mockUsageStatsManager = mock(UsageStatsManager::class.java)
        whenever(spyContext.getSystemService(Context.USAGE_STATS_SERVICE)).thenReturn(mockUsageStatsManager)

        // Mock UsageEvents
        val mockEvents = mock(UsageEvents::class.java)
        whenever(mockUsageStatsManager.queryEvents(any(), any())).thenReturn(mockEvents)

        // Simulate having one ACTIVITY_RESUMED event
        whenever(mockEvents.hasNextEvent()).thenReturn(true, false)

        doAnswer { invocation ->
            val outEvent = invocation.getArgument<UsageEvents.Event>(0)
            val pkgField = UsageEvents.Event::class.java.getDeclaredField("mPackage")
            pkgField.isAccessible = true
            pkgField.set(outEvent, "com.target.testapp")

            val typeField = UsageEvents.Event::class.java.getDeclaredField("mEventType")
            typeField.isAccessible = true
            typeField.set(outEvent, UsageEvents.Event.ACTIVITY_RESUMED)
            null
        }.whenever(mockEvents).getNextEvent(any())

        val foregroundPkg = config.getForegroundPackageName(spyContext)
        assertEquals("com.target.testapp", foregroundPkg)
    }

    @Test
    fun testGetForegroundPackageNameFallbackToQueryUsageStats() {
        val spyContext = spy(context)
        val mockUsageStatsManager = mock(UsageStatsManager::class.java)
        whenever(spyContext.getSystemService(Context.USAGE_STATS_SERVICE)).thenReturn(mockUsageStatsManager)

        // Mock UsageEvents to return empty / no resume event
        val mockEvents = mock(UsageEvents::class.java)
        whenever(mockUsageStatsManager.queryEvents(any(), any())).thenReturn(mockEvents)
        whenever(mockEvents.hasNextEvent()).thenReturn(false)

        // Mock queryUsageStats to return a list of UsageStats
        val mockUsageStats = mock(UsageStats::class.java)
        whenever(mockUsageStats.packageName).thenReturn("com.fallback.testapp")
        whenever(mockUsageStats.lastTimeUsed).thenReturn(100000L)

        whenever(mockUsageStatsManager.queryUsageStats(any(), any(), any())).thenReturn(listOf(mockUsageStats))

        val foregroundPkg = config.getForegroundPackageName(spyContext)
        assertEquals("com.fallback.testapp", foregroundPkg)
    }

    @Test
    fun testWarningTriggerStateEvaluation() {
        // Test variables reflecting startMonitoring's transition states
        val currentApp = "com.protected.app"
        val lastForegroundAppNull: String? = null
        val lastForegroundAppSame = "com.protected.app"
        val lastForegroundAppDiff = "com.launcher.app"

        // Setup config rules
        val activeProfile = "custom"
        config.setSelectedProfileId(activeProfile)
        config.setProtectedApps(setOf("com.protected.app"))

        assertTrue(config.isAppProtected(currentApp))

        // Trigger condition helper mimicking our startMonitoring() implementation:
        // shouldTrigger = !isVpnOn && !isPackageSuppressed && (currentApp != lastForegroundApp || wasVpnOn == true)
        fun checkShouldTrigger(isVpnOn: Boolean, lastForegroundApp: String?, wasVpnOn: Boolean?): Boolean {
            val isProtected = config.isAppProtected(currentApp) && currentApp != context.packageName
            if (!isProtected) return false
            return !isVpnOn && (currentApp != lastForegroundApp || wasVpnOn == true)
        }

        // Case 1: App opened when VPN is already OFF (currentApp != lastForegroundApp)
        assertTrue(checkShouldTrigger(isVpnOn = false, lastForegroundApp = lastForegroundAppDiff, wasVpnOn = false))
        assertTrue(checkShouldTrigger(isVpnOn = false, lastForegroundApp = lastForegroundAppNull, wasVpnOn = false))

        // Case 2: App opened when VPN is ON -> No Trigger
        assertFalse(checkShouldTrigger(isVpnOn = true, lastForegroundApp = lastForegroundAppDiff, wasVpnOn = true))

        // Case 3: Inside protected app, VPN drops (currentApp == lastForegroundApp, wasVpnOn == true)
        assertTrue(checkShouldTrigger(isVpnOn = false, lastForegroundApp = lastForegroundAppSame, wasVpnOn = true))

        // Case 4: Inside protected app, VPN remains OFF after warning triggered once (currentApp == lastForegroundApp, wasVpnOn == false) -> No Trigger (prevent warning loop)
        assertFalse(checkShouldTrigger(isVpnOn = false, lastForegroundApp = lastForegroundAppSame, wasVpnOn = false))
    }
}
