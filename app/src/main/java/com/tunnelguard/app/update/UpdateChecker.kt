package com.tunnelguard.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URI

interface UpdateChecker {
    suspend fun checkForLatestRelease(ifNoneMatch: String? = null): UpdateCheckResult
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val apkUrl: String?,
        val releaseNotes: String?,
        val eTag: String? = null,
        val releaseName: String? = null,
        val releaseUrl: String? = null,
        val publishedAt: String? = null
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
            if (response.code() == 304) return@withContext UpdateCheckResult.NotModified
            if (!response.isSuccessful) return@withContext UpdateCheckResult.Failure("HTTP error code ${response.code()}")

            val body = response.body() ?: return@withContext UpdateCheckResult.Failure("Empty response body")
            val tagName = body.tag_name?.trim().orEmpty()
            val htmlUrl = body.html_url?.trim().orEmpty()
            if (tagName.isBlank()) return@withContext UpdateCheckResult.Failure("Release is missing tag_name")
            if (!isOfficialReleasePage(htmlUrl)) return@withContext UpdateCheckResult.Failure("Release URL is not the official TunnelGuard GitHub Releases page")

            val apkUrl = body.assets
                ?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true && isTrustedGitHubDownloadUrl(it.browser_download_url) }
                ?.browser_download_url

            UpdateCheckResult.UpdateAvailable(
                latestVersion = tagName.removePrefix("v").removePrefix("V"),
                apkUrl = apkUrl,
                releaseNotes = body.body.orEmpty(),
                eTag = response.headers()["ETag"],
                releaseName = body.name?.takeIf { it.isNotBlank() } ?: tagName,
                releaseUrl = htmlUrl,
                publishedAt = body.published_at.orEmpty()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheckResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun isOfficialReleasePage(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            uri.path.startsWith("/DisabledAbel/TunnelGuard/releases/")
    } catch (_: Exception) { false }

    private fun isTrustedGitHubDownloadUrl(url: String?): Boolean = try {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && (uri.host.equals("github.com", true) || uri.host.endsWith(".github.com", true)) &&
            uri.path.startsWith("/DisabledAbel/TunnelGuard/releases/download/")
    } catch (_: Exception) { false }
}
