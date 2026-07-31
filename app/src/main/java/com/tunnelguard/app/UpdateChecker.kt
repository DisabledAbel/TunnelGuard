package com.tunnelguard.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface UpdateChecker {
    fun checkForLatestRelease(
        currentVersion: String,
        onSuccess: (latestVersion: String, apkUrl: String?) -> Unit,
        onFailure: (errorMessage: String) -> Unit
    )
}

class GitHubUpdateChecker : UpdateChecker {
    override fun checkForLatestRelease(
        currentVersion: String,
        onSuccess: (latestVersion: String, apkUrl: String?) -> Unit,
        onFailure: (errorMessage: String) -> Unit
    ) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/DisabledAbel/TunnelGuard/releases/latest")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "TunnelGuard-App")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonObj = JSONObject(jsonStr)
                    val tagName = jsonObj.getString("tag_name")
                    val cleanTagName = tagName.trim().removePrefix("v")

                    var apkUrl: String? = null
                    val assets = jsonObj.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val assetName = asset.getString("name")
                            if (assetName.endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }
                    onSuccess(cleanTagName, apkUrl)
                } else {
                    onFailure("HTTP error code $responseCode")
                }
            } catch (e: Exception) {
                onFailure(e.message ?: "Unknown error")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
