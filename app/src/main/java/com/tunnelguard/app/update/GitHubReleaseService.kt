package com.tunnelguard.app.update

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface GitHubReleaseService {
    @GET("repos/DisabledAbel/TunnelGuard/releases/latest")
    suspend fun getLatestRelease(
        @Header("User-Agent") userAgent: String = "TunnelGuard-App",
        @Header("Accept") accept: String = "application/vnd.github+json",
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<GitHubReleaseResponse>
}

data class GitHubReleaseResponse(
    val tag_name: String?,
    val name: String?,
    val body: String?,
    val html_url: String?,
    val published_at: String?,
    val assets: List<GitHubAsset>?
)

data class GitHubAsset(
    val name: String?,
    val browser_download_url: String?
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkUrl: String?,
    val eTag: String? = null
) {
    val normalizedVersion: String = tagName.trim().removePrefix("v").removePrefix("V")
}
