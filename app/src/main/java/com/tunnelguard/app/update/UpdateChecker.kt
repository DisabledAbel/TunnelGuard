package com.tunnelguard.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

interface UpdateChecker {
    suspend fun checkForLatestRelease(ifNoneMatch: String? = null): UpdateCheckResult
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val apkUrl: String?,
        val releaseNotes: String?,
        val eTag: String? = null
    ) : UpdateCheckResult()
    object NoUpdate : UpdateCheckResult()
    object NotModified : UpdateCheckResult()
    data class Failure(val errorMessage: String) : UpdateCheckResult()
}

class GitHubUpdateCheckerImpl(
    private val apiBaseUrl: String = "https://api.github.com/"
) : UpdateChecker {

    private val service: GitHubReleaseService by lazy {
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubReleaseService::class.java)
    }

    override suspend fun checkForLatestRelease(ifNoneMatch: String?): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val response = service.getLatestRelease(ifNoneMatch = ifNoneMatch)
            if (response.code() == 304) {
                return@withContext UpdateCheckResult.NotModified
            }
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    val cleanTagName = body.tag_name.trim().removePrefix("v")
                    // Explicit first-match policy for APK selection
                    val apkUrl = body.assets?.firstOrNull { it.name.endsWith(".apk") }?.browser_download_url
                    val eTag = response.headers().get("ETag")
                    UpdateCheckResult.UpdateAvailable(cleanTagName, apkUrl, body.body, eTag)
                } else {
                    UpdateCheckResult.Failure("Empty response body")
                }
            } else {
                UpdateCheckResult.Failure("HTTP error code ${response.code()}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheckResult.Failure(e.message ?: "Unknown error")
        }
    }
}
