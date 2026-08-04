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

    fun setUpdateDetectedInSession(detected: Boolean) = prefs.edit().putBoolean(KEY_UPDATE_DETECTED_SESSION, detected).apply()
    fun isUpdateDetectedInSession(): Boolean = prefs.getBoolean(KEY_UPDATE_DETECTED_SESSION, false)
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
            is UpdateCheckResult.NotModified -> fromCache(currentVersion, storedETag) ?: UpdateCheckResult.NoUpdate
            is UpdateCheckResult.UpdateAvailable -> {
                cacheUpdateInfo(result.latestVersion, result.apkUrl, result.releaseNotes, result.releaseName, result.releaseUrl, result.publishedAt)
                cacheETag(result.eTag)
                if (VersionComparator.isNewerVersion(currentVersion, result.latestVersion)) {
                    setUpdateDetectedInSession(true); result
                } else { setUpdateDetectedInSession(false); UpdateCheckResult.NoUpdate }
            }
            is UpdateCheckResult.Failure -> fromCache(currentVersion, storedETag) ?: result
            else -> result
        }
    }

    private fun fromCache(currentVersion: String, eTag: String?): UpdateCheckResult.UpdateAvailable? {
        val cachedVer = getCachedLatestVersion() ?: return null
        return if (VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
            setUpdateDetectedInSession(true)
            UpdateCheckResult.UpdateAvailable(
                latestVersion = cachedVer,
                apkUrl = getCachedApkUrl(),
                releaseNotes = getCachedReleaseNotes(),
                eTag = eTag,
                releaseName = getCachedReleaseName(),
                releaseUrl = getCachedReleaseUrl(),
                publishedAt = getCachedPublishedAt()
            )
        } else null
    }
}
