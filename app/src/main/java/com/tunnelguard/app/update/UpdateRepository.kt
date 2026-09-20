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
    @Volatile private var updateDetectedInSession = false

    init {
        // This key was written by older versions even though it described process state.
        // Remove only the obsolete flag; cached release metadata remains available for
        // conditional requests and the explicit offline fallback.
        prefs.edit().remove(KEY_UPDATE_DETECTED_SESSION).apply()
    }

    companion object {
        private const val PREFS_NAME = "tunnel_guard_update_prefs"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_APK_URL = "apk_url"
        private const val KEY_RELEASE_NOTES = "release_notes"
        private const val KEY_RELEASE_NAME = "release_name"
        private const val KEY_RELEASE_URL = "release_url"
        private const val KEY_PUBLISHED_AT = "published_at"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_UPDATE_DETECTED_SESSION = "update_detected_session"
        private const val KEY_LATEST_ETAG = "latest_etag"

        @Volatile private var instance: UpdateRepository? = null
        fun getInstance(context: Context): UpdateRepository = instance ?: synchronized(this) {
            instance ?: UpdateRepository(context.applicationContext).also { instance = it }
        }
        @androidx.annotation.VisibleForTesting fun setInstance(repo: UpdateRepository?) { instance = repo }
    }

    fun setUpdateDetectedInSession(detected: Boolean) {
        updateDetectedInSession = detected
    }

    fun isUpdateDetectedInSession(): Boolean = updateDetectedInSession
    fun getCachedLatestVersion(): String? = prefs.getString(KEY_LATEST_VERSION, null)
    fun getCachedApkUrl(): String? = prefs.getString(KEY_APK_URL, null)
    fun getCachedReleaseNotes(): String? = prefs.getString(KEY_RELEASE_NOTES, null)
    fun getCachedReleaseName(): String? = prefs.getString(KEY_RELEASE_NAME, null)
    fun getCachedReleaseUrl(): String? = prefs.getString(KEY_RELEASE_URL, null)
    fun getCachedPublishedAt(): String? = prefs.getString(KEY_PUBLISHED_AT, null)
    fun getCachedETag(): String? = prefs.getString(KEY_LATEST_ETAG, null)
    fun cacheETag(eTag: String?) = prefs.edit().putString(KEY_LATEST_ETAG, eTag).apply()
    fun forceSetLastCheckTime(time: Long) = prefs.edit().putLong(KEY_LAST_CHECK_TIME, time).apply()

    fun cacheUpdateInfo(
        versionName: String,
        apkUrl: String?,
        releaseNotes: String?,
        releaseName: String? = null,
        releaseUrl: String? = null,
        publishedAt: String? = null
    ) {
        prefs.edit()
            .putString(KEY_LATEST_VERSION, versionName)
            .putString(KEY_APK_URL, apkUrl)
            .putString(KEY_RELEASE_NOTES, releaseNotes)
            .putString(KEY_RELEASE_NAME, releaseName)
            .putString(KEY_RELEASE_URL, releaseUrl)
            .putString(KEY_PUBLISHED_AT, publishedAt)
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val storedETag = getCachedETag()
        when (val result = updateChecker.checkForLatestRelease(ifNoneMatch = storedETag)) {
            is UpdateCheckResult.NotModified -> fromCache(currentVersion, storedETag, markDetected = true)
                ?: UpdateCheckResult.NoUpdate.also { setUpdateDetectedInSession(false) }
            is UpdateCheckResult.UpdateAvailable -> {
                cacheUpdateInfo(result.latestVersion, result.apkUrl, result.releaseNotes, result.releaseName, result.releaseUrl, result.publishedAt)
                cacheETag(result.eTag)
                if (VersionComparator.isNewerVersion(currentVersion, result.latestVersion)) {
                    setUpdateDetectedInSession(true); result
                } else { setUpdateDetectedInSession(false); UpdateCheckResult.NoUpdate }
            }
            is UpdateCheckResult.Failure -> {
                // An offline fallback can block this attempt, but it is not a fresh
                // confirmation and must not become process-session authority.
                fromCache(currentVersion, storedETag, markDetected = false) ?: result
            }
            is UpdateCheckResult.NoUpdate -> {
                clearCachedReleaseInfo()
                setUpdateDetectedInSession(false)
                result
            }
        }
    }

    private fun fromCache(
        currentVersion: String,
        eTag: String?,
        markDetected: Boolean
    ): UpdateCheckResult.UpdateAvailable? {
        val cachedVer = getCachedLatestVersion() ?: return null
        return if (VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
            if (markDetected) setUpdateDetectedInSession(true)
            UpdateCheckResult.UpdateAvailable(
                latestVersion = cachedVer,
                apkUrl = getCachedApkUrl(),
                releaseNotes = getCachedReleaseNotes(),
                eTag = eTag,
                releaseName = getCachedReleaseName(),
                releaseUrl = getCachedReleaseUrl(),
                publishedAt = getCachedPublishedAt()
            )
        } else {
            if (markDetected) setUpdateDetectedInSession(false)
            null
        }
    }

    private fun clearCachedReleaseInfo() {
        prefs.edit()
            .remove(KEY_LATEST_VERSION)
            .remove(KEY_APK_URL)
            .remove(KEY_RELEASE_NOTES)
            .remove(KEY_RELEASE_NAME)
            .remove(KEY_RELEASE_URL)
            .remove(KEY_PUBLISHED_AT)
            .remove(KEY_LATEST_ETAG)
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }
}
