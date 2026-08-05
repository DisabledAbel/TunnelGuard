package com.tunnelguard.app.update

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class DownloadManager {
    suspend fun downloadApk(urlString: String, outputFile: File, progressUpdate: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            try {
                return@withContext performDownload(urlString, outputFile, progressUpdate)
            } catch (e: Exception) {
                lastException = e
                outputFile.delete()
                if (attempt < maxRetries) {
                    val delayMs = attempt * 2000L
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastException ?: IOException("Failed to download APK after $maxRetries attempts")
    }

    private suspend fun performDownload(urlString: String, outputFile: File, progressUpdate: (Int) -> Unit): File {
        validateInitialUrl(urlString)
        outputFile.parentFile?.mkdirs()
        var currentUrl = urlString
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "TunnelGuard-App")
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                when (val status = conn.responseCode) {
                    HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                        val location = conn.getHeaderField("Location") ?: throw IOException("Redirect with empty Location header")
                        val redirected = URL(URL(currentUrl), location)
                        validateRedirectUrl(redirected.toString())
                        currentUrl = redirected.toString()
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            conn.contentLengthLong
                        } else {
                            conn.contentLength.toLong()
                        }
                        var readTotal = 0L
                        conn.inputStream.use { input -> FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                readTotal += read
                                if (total > 0) progressUpdate(((readTotal * 100L) / total).toInt().coerceIn(0, 100))
                            }
                        } }
                        progressUpdate(100)
                        return outputFile
                    }
                    else -> throw IOException("Server returned HTTP $status")
                }
            } finally { conn.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    private fun validateInitialUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https") { "APK URL must use HTTPS" }
        require(uri.host.equals("github.com", true) || uri.host.endsWith(".github.com", true)) { "APK URL must belong to GitHub" }
        require(uri.path.startsWith("/DisabledAbel/TunnelGuard/releases/download/")) { "APK must come from the official TunnelGuard release assets" }
    }

    private fun validateRedirectUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https") { "Redirect must use HTTPS" }
        val host = uri.host.lowercase()
        require(host == "github.com" || host.endsWith(".github.com") || host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")) { "Redirect host is not trusted" }
    }
}
