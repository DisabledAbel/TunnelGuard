package com.tunnelguard.app.update

import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class UpdateRepositoryTest {

    private class MockUpdateChecker(
        private val mockTagName: String,
        private val mockApkUrl: String?,
        private val mockBody: String?,
        private val shouldFail: Boolean = false
    ) : UpdateChecker {
        var callCount = 0

        override suspend fun checkForLatestRelease(ifNoneMatch: String?): UpdateCheckResult {
            callCount++
            return if (shouldFail) {
                UpdateCheckResult.Failure("Mock network error")
            } else {
                UpdateCheckResult.UpdateAvailable(mockTagName, mockApkUrl, mockBody, "mock-etag")
            }
        }
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("tunnel_guard_update_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        UpdateRepository.setInstance(null)
    }

    @Test
    fun testEveryLaunchPerformsFreshCheck() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val mockChecker = MockUpdateChecker("1.0.0", null, null)
        val repository = UpdateRepository(context, mockChecker)

        // First check - should call the mock checker
        val result1 = repository.checkForUpdate(currentVersion = "1.0.0")

        assertEquals(1, mockChecker.callCount)
        assertTrue(result1 is UpdateCheckResult.NoUpdate)

        // Second check should still call GitHub because update checks run on every launch.
        val result2 = repository.checkForUpdate(currentVersion = "1.0.0")

        assertEquals(2, mockChecker.callCount)
        assertTrue(result2 is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun testSessionLevelPersistenceOnSuccess() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val mockChecker = MockUpdateChecker("1.1.0", "https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.1.0/TunnelGuard.apk", "Notes")
        val repository = UpdateRepository(context, mockChecker)

        assertFalse(repository.isUpdateDetectedInSession())

        repository.checkForUpdate(currentVersion = "1.0.0")

        assertTrue(repository.isUpdateDetectedInSession())
    }

    @Test
    fun testOfflineSessionLevelPersistenceFallback() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val mockChecker = MockUpdateChecker("1.2.0", null, null, shouldFail = true)
        val repository = UpdateRepository(context, mockChecker)

        // Step 1: Set update detected in session manually
        repository.setUpdateDetectedInSession(true)
        repository.cacheUpdateInfo("1.2.0", "https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.2.0/TunnelGuard.apk", "Some notes")

        // Trigger checkForUpdate while "offline". It should fallback to the cached/detected session update.
        val result = repository.checkForUpdate(currentVersion = "1.0.0")

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val details = result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.2.0", details.latestVersion)
        assertEquals("https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.2.0/TunnelGuard.apk", details.apkUrl)
        assertEquals("Some notes", details.releaseNotes)

        // Fresh checks are still attempted, then cached release data is used as a fallback.
        assertEquals(1, mockChecker.callCount)
    }

    @Test
    fun testFailurePathWithNoSessionUpdate() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val mockChecker = MockUpdateChecker("1.2.0", null, null, shouldFail = true)
        val repository = UpdateRepository(context, mockChecker)

        // isUpdateDetectedInSession() is initially false
        assertFalse(repository.isUpdateDetectedInSession())

        // Check for update - should invoke the mock checker and fail, exercising the Failure path
        val result = repository.checkForUpdate(currentVersion = "1.0.0")

        assertEquals(1, mockChecker.callCount)
        assertTrue(result is UpdateCheckResult.Failure)
        assertEquals("Mock network error", (result as UpdateCheckResult.Failure).errorMessage)
    }
}
