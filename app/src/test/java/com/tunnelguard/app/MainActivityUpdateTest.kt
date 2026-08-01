package com.tunnelguard.app

import android.content.Intent
import android.os.Build
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

        override fun checkForLatestRelease(
            onSuccess: (latestVersion: String, apkUrl: String?) -> Unit,
            onFailure: (errorMessage: String) -> Unit
        ) {
            invocationsCount++
            // Immediate stub callback to avoid any real HttpURLConnection or network call
            onSuccess("1.0.0", null)
        }
    }

    private val originalChecker = UpdateChecker.instance

    @Before
    fun setUp() {
        val stub = StubUpdateChecker()
        MainActivity.updateChecker = stub
        UpdateChecker.instance = stub

        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("tunnel_guard_update_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        MainActivity.updateChecker = originalChecker
        UpdateChecker.instance = originalChecker
        UpdateManager.isUpdateInProgress.set(false)
    }

    @Test
    fun testUpdateCheckTriggeredOnActivityLaunch() {
        val stub = MainActivity.updateChecker as StubUpdateChecker
        org.junit.Assert.assertEquals(0, stub.invocationsCount)

        // Launch MainActivity using Robolectric
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()

        // Verify that checkForLatestRelease got invoked
        org.junit.Assert.assertEquals(1, stub.invocationsCount)
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
            com.tunnelguard.app.update.ForceUpdateActivity::class.java.name,
            startedIntent.component?.className
        )
        assertTrue("MainActivity should have finished", activity.isFinishing)
    }
}
