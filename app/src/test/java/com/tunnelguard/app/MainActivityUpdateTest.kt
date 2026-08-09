package com.tunnelguard.app

import android.content.Intent
import android.os.Build
import com.tunnelguard.app.update.ForceUpdateActivity
import com.tunnelguard.app.update.UpdateCheckResult
import com.tunnelguard.app.update.UpdateChecker
import com.tunnelguard.app.update.UpdateRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MainActivityUpdateTest {

    private class StubUpdateChecker : UpdateChecker {
        var invocationsCount = 0

        override suspend fun checkForLatestRelease(ifNoneMatch: String?): UpdateCheckResult {
            invocationsCount++
            return UpdateCheckResult.NoUpdate
        }
    }

    private lateinit var currentStub: StubUpdateChecker

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("tunnel_guard_update_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val mainPrefs = context.getSharedPreferences("tunnel_guard_prefs", android.content.Context.MODE_PRIVATE)
        mainPrefs.edit().clear().commit()
        mainPrefs.edit().putBoolean("onboarding_completed", true).commit()

        currentStub = StubUpdateChecker()
        val repo = UpdateRepository(context, currentStub)
        UpdateRepository.setInstance(repo)
    }

    @After
    fun tearDown() {
        UpdateRepository.setInstance(null)
        UpdateManager.isUpdateInProgress.set(false)
    }

    @Test
    fun testUpdateCheckTriggeredOnActivityLaunch() {
        assertEquals(0, currentStub.invocationsCount)

        // Launch MainActivity using Robolectric
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()

        // Idle the main looper to execute the pending lifecycleScope.launch coroutine
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Verify that checkForLatestRelease got invoked
        assertEquals(1, currentStub.invocationsCount)
    }

    @Test
    fun testVersionComparatorDetectsNewerVersion() {
        // Assert some key comparisons in our update path
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(VersionComparator.isNewerVersion("1.0", "1.0.1"))
        assertTrue(VersionComparator.isNewerVersion("1.0.1", "1.1.0"))
        assertTrue(VersionComparator.isNewerVersion("0.9.9", "1.0.0"))
    }

    @Test
    fun testForceUpdateRedirectsAndFinishesMainActivity() {
        val context = RuntimeEnvironment.getApplication()
        val repo = UpdateRepository.getInstance(context)

        // Setup a mock update available in repo
        repo.setUpdateDetectedInSession(true)
        repo.cacheUpdateInfo("2.0.0", "https://github.com/DisabledAbel/TunnelGuard/releases/download/v2.0.0/TunnelGuard.apk", "Mandatory release notes")

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()

        // Check if ForceUpdateActivity was started
        val shadowActivity = shadowOf(activity)
        val startedIntent = shadowActivity.nextStartedActivity

        assertNotNull("ForceUpdateActivity should have been started", startedIntent)
        assertEquals(
            ForceUpdateActivity::class.java.name,
            startedIntent.component?.className
        )
        assertTrue("MainActivity should have finished", activity.isFinishing)
    }

    @Test
    fun testPermissionApprovalFollowedByResume() {
        val context = RuntimeEnvironment.getApplication()
        val config = TunnelGuardConfig(context)
        config.setProtectionEnabled(true)

        TunnelGuardVpnService.isServiceStarting = false

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()

        val shadowApp = shadowOf(activity.applicationContext as android.app.Application)
        // Clear any previous started services in the queue first
        while (shadowApp.nextStartedService != null) { }

        activity.onActivityResult(2001, android.app.Activity.RESULT_OK, null)

        assertTrue(TunnelGuardVpnService.isServiceStarting)
        val firstIntent = shadowApp.nextStartedService
        assertNotNull(firstIntent)
        assertEquals(TunnelGuardVpnService.ACTION_START, firstIntent?.action)
        assertNull(shadowApp.nextStartedService)

        controller.resume()

        assertTrue(TunnelGuardVpnService.isServiceStarting)
        assertNull(shadowApp.nextStartedService)

        TunnelGuardVpnService.isServiceStarting = false
    }
}
