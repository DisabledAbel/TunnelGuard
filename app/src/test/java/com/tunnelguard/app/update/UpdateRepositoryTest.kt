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

    private lateinit var repository: UpdateRepository

    private class MockUpdateChecker(
        private val mockTagName: String,
        private val mockApkUrl: String?,
        private val mockBody: String?,
        private val shouldFail: Boolean = false
    ) : UpdateChecker {
        var callCount = 0

        override suspend fun checkForLatestRelease(): UpdateCheckResult {
            callCount++
            return if (shouldFail) {
                UpdateCheckResult.Failure("Mock network error")
            } else {
                UpdateCheckResult.UpdateAvailable(mockTagName, mockApkUrl, mockBody)
            }
        }
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        repository = UpdateRepository(context)
        val prefs = context.getSharedPreferences("tunnel_guard_update_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testCacheValidationAndExpiry() = runBlocking {
        val mockChecker = MockUpdateChecker("1.1.0", "https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.1.0/TunnelGuard.apk", "Awesome features")
        repository.updateChecker = mockChecker

        // First check - should call the mock checker
        val result1 = repository.checkForUpdate(currentVersion = "1.0.0")

        assertEquals(1, mockChecker.callCount)
        assertTrue(result1 is UpdateCheckResult.UpdateAvailable)
        assertEquals("1.1.0", (result1 as UpdateCheckResult.UpdateAvailable).latestVersion)

        // Second check within 5 minutes - should use the cached value (no call to mock checker)
        val result2 = repository.checkForUpdate(currentVersion = "1.0.0")

        assertEquals(1, mockChecker.callCount) // callCount stays 1!
        assertTrue(result2 is UpdateCheckResult.UpdateAvailable)
        assertEquals("1.1.0", (result2 as UpdateCheckResult.UpdateAvailable).latestVersion)
    }

    @Test
    fun testSessionLevelPersistenceOnSuccess() = runBlocking {
        val mockChecker = MockUpdateChecker("1.1.0", "https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.1.0/TunnelGuard.apk", "Notes")
        repository.updateChecker = mockChecker

        assertFalse(repository.isUpdateDetectedInSession())

        repository.checkForUpdate(currentVersion = "1.0.0")

        assertTrue(repository.isUpdateDetectedInSession())
    }

    @Test
    fun testOfflineSessionLevelPersistenceFallback() = runBlocking {
        // Step 1: Set update detected in session manually
        repository.setUpdateDetectedInSession(true)
        repository.cacheUpdateInfo("1.2.0", "https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.2.0/TunnelGuard.apk", "Some notes")

        // Step 2: Set the checker to fail (simulating offline/no network)
        val mockChecker = MockUpdateChecker("1.2.0", null, null, shouldFail = true)
        repository.updateChecker = mockChecker

        // Trigger checkForUpdate while "offline". It should fallback to the cached/detected session update.
        val result = repository.checkForUpdate(currentVersion = "1.0.0")

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val details = result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.2.0", details.latestVersion)
        assertEquals("https://github.com/DisabledAbel/TunnelGuard/releases/download/v1.2.0/TunnelGuard.apk", details.apkUrl)
        assertEquals("Some notes", details.releaseNotes)
    }
}
