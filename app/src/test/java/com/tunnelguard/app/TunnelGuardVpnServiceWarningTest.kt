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

        // Simulate having two chronologically ordered ACTIVITY_RESUMED events (older and newer)
        var eventCount = 0
        whenever(mockEvents.hasNextEvent()).thenAnswer { eventCount < 2 }

        doAnswer { invocation ->
            val outEvent = invocation.getArgument<UsageEvents.Event>(0)
            val pkgField = UsageEvents.Event::class.java.getDeclaredField("mPackage")
            pkgField.isAccessible = true

            val typeField = UsageEvents.Event::class.java.getDeclaredField("mEventType")
            typeField.isAccessible = true

            if (eventCount == 0) {
                pkgField.set(outEvent, "com.older.app")
                typeField.set(outEvent, UsageEvents.Event.ACTIVITY_RESUMED)
            } else {
                pkgField.set(outEvent, "com.newer.app")
                typeField.set(outEvent, UsageEvents.Event.ACTIVITY_RESUMED)
            }
            eventCount++
            null
        }.whenever(mockEvents).getNextEvent(any())

        val foregroundPkg = config.getForegroundPackageName(spyContext)
        assertEquals("com.newer.app", foregroundPkg)
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

        // Mock queryUsageStats to return multiple UsageStats objects in non-chronological order
        val statsOlder = mock(UsageStats::class.java)
        whenever(statsOlder.packageName).thenReturn("com.older.fallback")
        whenever(statsOlder.lastTimeUsed).thenReturn(50000L)

        val statsNewer = mock(UsageStats::class.java)
        whenever(statsNewer.packageName).thenReturn("com.newer.fallback")
        whenever(statsNewer.lastTimeUsed).thenReturn(150000L)

        val statsMiddle = mock(UsageStats::class.java)
        whenever(statsMiddle.packageName).thenReturn("com.middle.fallback")
        whenever(statsMiddle.lastTimeUsed).thenReturn(100000L)

        whenever(mockUsageStatsManager.queryUsageStats(any(), any(), any()))
            .thenReturn(listOf(statsOlder, statsNewer, statsMiddle))

        val foregroundPkg = config.getForegroundPackageName(spyContext)
        assertEquals("com.newer.fallback", foregroundPkg)
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

        // Case 1: App opened when VPN is already OFF (currentApp != lastForegroundApp), not suppressed
        assertTrue(TunnelGuardVpnService.shouldTriggerWarning(
            currentApp = currentApp,
            lastForegroundApp = lastForegroundAppDiff,
            isVpnOn = false,
            wasVpnOn = false,
            isSuppressed = false
        ))
        assertTrue(TunnelGuardVpnService.shouldTriggerWarning(
            currentApp = currentApp,
            lastForegroundApp = lastForegroundAppNull,
            isVpnOn = false,
            wasVpnOn = false,
            isSuppressed = false
        ))

        // Case 2: App opened when VPN is ON -> No Trigger
        assertFalse(TunnelGuardVpnService.shouldTriggerWarning(
            currentApp = currentApp,
            lastForegroundApp = lastForegroundAppDiff,
            isVpnOn = true,
            wasVpnOn = true,
            isSuppressed = false
        ))

        // Case 3: Inside protected app, VPN drops (currentApp == lastForegroundApp, wasVpnOn == true)
        assertTrue(TunnelGuardVpnService.shouldTriggerWarning(
            currentApp = currentApp,
            lastForegroundApp = lastForegroundAppSame,
            isVpnOn = false,
            wasVpnOn = true,
            isSuppressed = false
        ))

        // Case 4: Inside protected app, VPN remains OFF after warning triggered once (currentApp == lastForegroundApp, wasVpnOn == false) -> No Trigger (prevent warning loop)
        assertFalse(TunnelGuardVpnService.shouldTriggerWarning(
            currentApp = currentApp,
            lastForegroundApp = lastForegroundAppSame,
            isVpnOn = false,
            wasVpnOn = false,
            isSuppressed = false
        ))

        // Case 5: App opened when VPN is OFF, but package warning is suppressed -> No Trigger
        assertFalse(TunnelGuardVpnService.shouldTriggerWarning(
            currentApp = currentApp,
            lastForegroundApp = lastForegroundAppDiff,
            isVpnOn = false,
            wasVpnOn = false,
            isSuppressed = true
        ))
    }
}
