package com.tunnelguard.app.update

import android.content.Context
import android.content.SharedPreferences
import com.tunnelguard.app.VersionComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateRepository(
    private val context: Context,
    private val updateChecker: UpdateChecker = GitHubUpdateCheckerImpl()
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "tunnel_guard_update_prefs"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_APK_URL = "apk_url"
        private const val KEY_RELEASE_NOTES = "release_notes"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_UPDATE_DETECTED_SESSION = "update_detected_session"
        private const val KEY_LATEST_ETAG = "latest_etag"

        // Cache duration is 5 minutes (300,000 milliseconds)
        private const val CACHE_DURATION_MS = 5 * 60 * 1000

        @Volatile
        private var instance: UpdateRepository? = null

        fun getInstance(context: Context): UpdateRepository {
            return instance ?: synchronized(this) {
                instance ?: UpdateRepository(context.applicationContext).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setInstance(repo: UpdateRepository?) {
            instance = repo
        }
    }

    fun setUpdateDetectedInSession(detected: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_DETECTED_SESSION, detected).apply()
    }

    fun isUpdateDetectedInSession(): Boolean {
        return prefs.getBoolean(KEY_UPDATE_DETECTED_SESSION, false)
    }

    fun getCachedLatestVersion(): String? {
        return prefs.getString(KEY_LATEST_VERSION, null)
    }

    fun getCachedApkUrl(): String? {
        return prefs.getString(KEY_APK_URL, null)
    }

    fun getCachedReleaseNotes(): String? {
        return prefs.getString(KEY_RELEASE_NOTES, null)
    }

    fun getCachedETag(): String? {
        return prefs.getString(KEY_LATEST_ETAG, null)
    }

    fun cacheUpdateInfo(versionName: String, apkUrl: String?, releaseNotes: String?) {
        prefs.edit()
            .putString(KEY_LATEST_VERSION, versionName)
            .putString(KEY_APK_URL, apkUrl)
            .putString(KEY_RELEASE_NOTES, releaseNotes)
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }

    fun cacheETag(eTag: String?) {
        prefs.edit().putString(KEY_LATEST_ETAG, eTag).apply()
    }

    fun forceSetLastCheckTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, time).apply()
    }

    private fun isCacheValid(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        return (System.currentTimeMillis() - lastCheck) < CACHE_DURATION_MS
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (isUpdateDetectedInSession()) {
            val cachedVer = getCachedLatestVersion()
            if (cachedVer != null && VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
                return@withContext UpdateCheckResult.UpdateAvailable(cachedVer, getCachedApkUrl(), getCachedReleaseNotes(), getCachedETag())
            }
        }

        if (isCacheValid()) {
            val cachedVer = getCachedLatestVersion()
            if (cachedVer != null) {
                return@withContext if (VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
                    setUpdateDetectedInSession(true)
                    UpdateCheckResult.UpdateAvailable(cachedVer, getCachedApkUrl(), getCachedReleaseNotes(), getCachedETag())
                } else {
                    UpdateCheckResult.NoUpdate
                }
            }
        }

        val storedETag = getCachedETag()
        val result = updateChecker.checkForLatestRelease(ifNoneMatch = storedETag)

        when (result) {
            is UpdateCheckResult.NotModified -> {
                val cachedVer = getCachedLatestVersion()
                if (cachedVer != null) {
                    if (VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
                        setUpdateDetectedInSession(true)
                        UpdateCheckResult.UpdateAvailable(cachedVer, getCachedApkUrl(), getCachedReleaseNotes(), storedETag)
                    } else {
                        UpdateCheckResult.NoUpdate
                    }
                } else {
                    UpdateCheckResult.NoUpdate
                }
            }
            is UpdateCheckResult.UpdateAvailable -> {
                cacheUpdateInfo(result.latestVersion, result.apkUrl, result.releaseNotes)
                cacheETag(result.eTag)
                if (VersionComparator.isNewerVersion(currentVersion, result.latestVersion)) {
                    setUpdateDetectedInSession(true)
                    result
                } else {
                    UpdateCheckResult.NoUpdate
                }
            }
            else -> {
                result
            }
        }
    }
}
