package com.tunnelguard.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class UpdateManager(
    private val activity: Activity,
    private val config: TunnelGuardConfig
) {

    companion object {
        val isUpdateInProgress = AtomicBoolean(false)
    }

    fun showUpdateAvailableDialog(latestVersion: String, apkUrl: String?, isFromSettings: Boolean) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("New Update Available")
            .setMessage("A new version of TunnelGuard (v$latestVersion) is available.\n\nWould you like to download and install this update now?")
            .setPositiveButton("Download") { dialog, _ ->
                dialog.dismiss()
                if (apkUrl != null) {
                    checkPermissionAndDownloadUpdate(latestVersion, apkUrl)
                } else {
                    if (isFromSettings) {
                        config.addLog("Error: No APK file found in GitHub release assets.")
                    } else {
                        config.addLog("Main Update Check Error: No APK file found in GitHub release assets.")
                    }
                    showUpdateErrorDialog("No APK asset found in the latest GitHub release.")
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun checkPermissionAndDownloadUpdate(latestVersion: String, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                config.addLog("Install unknown apps permission is NOT granted.")
                showPermissionRequiredDialog(
                    "Permission Required",
                    "To automatically download and install updates, TunnelGuard requires the 'Install unknown apps' permission.\n\nPlease enable 'Allow from this source' on the next screen, then try checking for updates again.",
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    },
                    Intent(Settings.ACTION_SETTINGS),
                    "ACTION_MANAGE_UNKNOWN_APP_SOURCES"
                )
                return
            }
        }
        downloadAndInstallUpdate(latestVersion, apkUrl)
    }

    fun showPermissionRequiredDialog(
        title: String,
        message: String,
        primaryIntent: Intent,
        fallbackIntent: Intent,
        logErrorTag: String
    ) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog.dismiss()
                try {
                    activity.startActivity(primaryIntent)
                } catch (e: Exception) {
                    config.addLog("Failed to launch $logErrorTag: ${e.message}")
                    try {
                        activity.startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        config.addLog("Failed to launch fallback settings: ${ex.message}")
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun validateVersionName(versionName: String): Boolean {
        return versionName.matches(Regex("^[0-9A-Za-z._-]+$"))
    }

    fun downloadAndInstallUpdate(latestVersion: String, downloadUrl: String) {
        if (!isUpdateInProgress.compareAndSet(false, true)) {
            config.addLog("Update/download already in progress. Ignoring duplicate request.")
            return
        }

        val downloadBuilder = androidx.appcompat.app.AlertDialog.Builder(activity)
        downloadBuilder.setTitle("Downloading Update")
        downloadBuilder.setMessage("Downloading TunnelGuard v$latestVersion...\n0%")
        downloadBuilder.setCancelable(false)
        val downloadDialog = downloadBuilder.create()
        downloadDialog.show()

        Thread {
            try {
                if (!validateVersionName(latestVersion)) {
                    throw IllegalArgumentException("Invalid version name format: $latestVersion")
                }

                val updatesDir = File(activity.cacheDir, "updates").canonicalFile
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }
                val updateApkFile = File(updatesDir, "TunnelGuard-v$latestVersion-update.apk").canonicalFile
                val parentFile = updateApkFile.parentFile ?: throw IllegalArgumentException("Invalid parent file path")
                if (parentFile.canonicalPath != updatesDir.canonicalPath) {
                    throw IllegalArgumentException("Path traversal detected in version name: $latestVersion")
                }

                downloadUrlWithRedirects(downloadUrl, updateApkFile) { progress, total ->
                    val percentage = if (total > 0) (progress * 100L / total).toInt() else 0
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            downloadDialog.setMessage("Downloading TunnelGuard v$latestVersion...\n$percentage%")
                        }
                    }
                }

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        isUpdateInProgress.set(false)
                        return@runOnUiThread
                    }
                    downloadDialog.dismiss()

                    // Install the APK and report back
                    val installSuccess = installApkFile(latestVersion)
                    if (installSuccess) {
                        // Show success dialog ONLY when installation reports success
                        androidx.appcompat.app.AlertDialog.Builder(activity)
                            .setTitle("Update Downloaded")
                            .setMessage("TunnelGuard has successfully downloaded version $latestVersion.\n\nThe package installer intent has been launched to complete the update.")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        showUpdateErrorDialog("Failed to initialize or launch package installer intent.")
                    }
                    isUpdateInProgress.set(false)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        isUpdateInProgress.set(false)
                        return@runOnUiThread
                    }
                    downloadDialog.dismiss()
                    config.addLog("Failed to download update: ${e.message}")
                    showUpdateErrorDialog("Failed to download update: ${e.message}")
                    isUpdateInProgress.set(false)
                }
            }
        }.start()
    }

    private fun downloadUrlWithRedirects(urlString: String, outputFile: File, progressUpdate: (Int, Int) -> Unit) {
        var currentUrl = urlString
        var redirectCount = 0
        val maxRedirects = 5

        while (redirectCount < maxRedirects) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "TunnelGuard-App")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308) {

                    val newUrl = conn.getHeaderField("Location") ?: throw IOException("Redirect with empty Location header.")
                    // Resolve relative redirect against base currentUrl
                    val resolvedUrlObj = URL(URL(currentUrl), newUrl)
                    if (resolvedUrlObj.protocol.lowercase() != "https") {
                        throw SecurityException("Insecure redirect to non-HTTPS URL: $resolvedUrlObj")
                    }
                    currentUrl = resolvedUrlObj.toString()
                    redirectCount++
                    continue
                }

                if (status == HttpURLConnection.HTTP_OK) {
                    val contentLength = conn.contentLength
                    conn.inputStream.use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            var totalBytesRead = 0
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                progressUpdate(totalBytesRead, contentLength)
                            }
                        }
                    }
                    return
                } else {
                    throw IOException("Server returned HTTP $status")
                }
            } finally {
                conn?.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    fun installApkFile(versionName: String): Boolean {
        return try {
            if (!validateVersionName(versionName)) {
                throw IllegalArgumentException("Invalid version name format: $versionName")
            }

            val updatesDir = File(activity.cacheDir, "updates").canonicalFile
            val updateApkFile = File(updatesDir, "TunnelGuard-v$versionName-update.apk").canonicalFile

            val parentFile = updateApkFile.parentFile ?: throw IllegalArgumentException("Invalid parent file path")
            if (parentFile.canonicalPath != updatesDir.canonicalPath) {
                throw IllegalArgumentException("Path traversal detected in version name: $versionName")
            }

            if (!updateApkFile.exists() || updateApkFile.length() == 0L) {
                config.addLog("Install failed: update APK file does not exist or is empty.")
                return false
            }

            config.addLog("Preparing to install downloaded APK: ${updateApkFile.absolutePath}")

            // Generate content URI using FileProvider
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                updateApkFile
            )

            // Package installer intent
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            config.addLog("Launching package installer intent for URI: $apkUri")
            activity.startActivity(installIntent)
            true
        } catch (e: Exception) {
            config.addLog("Failed to auto-install APK: ${e.message}")
            false
        }
    }

    fun showUpdateErrorDialog(errorMessage: String) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Update Check Failed")
            .setMessage("Could not check for updates or download update.\n\nDetails: $errorMessage")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
