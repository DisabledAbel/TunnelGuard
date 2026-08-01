package com.tunnelguard.app.update

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface GitHubReleaseService {
    @GET("repos/DisabledAbel/TunnelGuard/releases/latest")
    suspend fun getLatestRelease(
        @Header("User-Agent") userAgent: String = "TunnelGuard-App",
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<GitHubReleaseResponse>
}

data class GitHubReleaseResponse(
    val tag_name: String,
    val body: String?,
    val assets: List<GitHubAsset>?
)

data class GitHubAsset(
    val name: String,
    val browser_download_url: String
)
