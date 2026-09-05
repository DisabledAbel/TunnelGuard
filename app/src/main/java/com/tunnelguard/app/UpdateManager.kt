package com.tunnelguard.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tunnelguard.app.update.UpdateRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class ApkValidationResult {
    SUCCESS,
    FILE_NOT_FOUND_OR_EMPTY,
    PACKAGE_INFO_NULL,
    PACKAGE_NAME_MISMATCH,
    SIGNING_INFO_MISSING,
    SIGNATURE_MISMATCH,
    SIGNING_LINEAGE_INVALID,
    FILE_CHANGED,
    ERROR
}

class UpdateManager(
    private val activity: Activity,
    private val config: TunnelGuardConfig
) {

    private var lastInstallFailureMessage: String? = null
    private var lastInstallValidationResult: ApkValidationResult? = null

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

                var downloadSuccess = false
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        downloadUrlWithRedirects(downloadUrl, updateApkFile) { progress, total ->
                            val percentage = if (total > 0) (progress * 100L / total).toInt() else 0
                            activity.runOnUiThread {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    downloadDialog.setMessage("Downloading TunnelGuard v$latestVersion...\n$percentage% (Attempt $attempt of 3)")
                                }
                            }
                        }
                        downloadSuccess = true
                        break
                    } catch (e: Exception) {
                        lastError = e
                        if (updateApkFile.exists()) {
                            updateApkFile.delete()
                        }
                        if (attempt < 3) {
                            Thread.sleep(attempt * 2000L)
                        }
                    }
                }
                if (!downloadSuccess) {
                    throw lastError ?: IOException("Failed to download APK after 3 attempts")
                }

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        isUpdateInProgress.set(false)
                        return@runOnUiThread
                    }
                    downloadDialog.dismiss()

                    val errorBuilder = StringBuilder()
                    val validationResult = validateApkFileWithResult(updateApkFile, errorBuilder)
                    if (validationResult == ApkValidationResult.SUCCESS) {
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
                            showUpdateErrorDialog(lastInstallFailureMessage
                                ?: "Failed to initialize or launch package installer intent.",
                                lastInstallValidationResult)
                        }
                    } else {
                        val errorMsg = errorBuilder.toString()
                        showUpdateErrorDialog(
                            errorMsg.ifBlank { "Downloaded APK file validation failed." },
                            validationResult
                        )
                        if (updateApkFile.exists()) {
                            updateApkFile.delete()
                        }
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
        if (!urlString.lowercase().startsWith("https://")) {
            throw SecurityException("Insecure initial URL scheme: $urlString")
        }
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

    fun validateApkFile(apkFile: File, outError: StringBuilder? = null): Boolean {
        return validateApkFileWithResult(apkFile, outError) == ApkValidationResult.SUCCESS
    }

    fun validateApkFileWithResult(apkFile: File, outError: StringBuilder? = null): ApkValidationResult {
        config.addLog("APK validation started: ${apkFile.name}")
        return try {
            val pm = activity.packageManager

            // Check if file exists and is not empty
            if (!apkFile.exists() || apkFile.length() == 0L) {
                outError?.append("Downloaded APK file does not exist or is empty.")
                config.addLog("validateApkFile: APK file does not exist or is empty.")
                return ApkValidationResult.FILE_NOT_FOUND_OR_EMPTY
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // On Android 28+, retrieve signing certificates via GET_SIGNING_CERTIFICATES and GET_SIGNATURES combined
                // to work around the Android OS bug where signingInfo is otherwise returned as null for local APK packages.
                val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
                if (packageInfo == null) {
                    outError?.append("Failed to read package info from downloaded APK. The file might be corrupted.")
                    config.addLog("validateApkFile: PackageInfo is null for ${apkFile.name}")
                    return ApkValidationResult.PACKAGE_INFO_NULL
                }

                // 1. Verify Package Name
                if (packageInfo.packageName != activity.packageName) {
                    outError?.append("Package name mismatch.\n\nDownloaded package: ${packageInfo.packageName}\nInstalled package: ${activity.packageName}")
                    config.addLog("validateApkFile: Package name mismatch: ${packageInfo.packageName}")
                    return ApkValidationResult.PACKAGE_NAME_MISMATCH
                }
                config.addLog("APK package identity verified: ${activity.packageName}")

                val currentPackageInfo = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

                val archiveSigningInfo = packageInfo.signingInfo
                val currentSigningInfo = currentPackageInfo.signingInfo

                logSignerFingerprints("Current", currentSigningInfo?.apkContentsSigners)
                logSignerFingerprints("Downloaded", archiveSigningInfo?.apkContentsSigners)
                when (ApkSigningCertificateVerifier.verify(archiveSigningInfo, currentSigningInfo)) {
                    SignerVerificationResult.SUCCESS -> config.addLog("APK signer verification passed.")
                    SignerVerificationResult.SIGNING_INFO_MISSING -> {
                        outError?.append("Update blocked — signing information is missing.")
                        config.addLog("APK signer verification failed: signing information missing.")
                        return ApkValidationResult.SIGNING_INFO_MISSING
                    }
                    SignerVerificationResult.SIGNING_LINEAGE_INVALID -> {
                        appendSignatureSecurityError(outError)
                        config.addLog("APK signer verification failed: invalid signing lineage.")
                        return ApkValidationResult.SIGNING_LINEAGE_INVALID
                    }
                    SignerVerificationResult.SIGNATURE_MISMATCH -> {
                        appendSignatureSecurityError(outError)
                        config.addLog("APK signer verification failed: certificate mismatch.")
                        return ApkValidationResult.SIGNATURE_MISMATCH
                    }
                }
            } else {
                // Legacy handling on older versions (< API 28)
                val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                if (packageInfo == null) {
                    outError?.append("Failed to read package info from downloaded APK. The file might be corrupted.")
                    config.addLog("validateApkFile: PackageInfo is null for ${apkFile.name}")
                    return ApkValidationResult.PACKAGE_INFO_NULL
                }

                // 1. Verify Package Name
                if (packageInfo.packageName != activity.packageName) {
                    outError?.append("Package name mismatch.\n\nDownloaded package: ${packageInfo.packageName}\nInstalled package: ${activity.packageName}")
                    config.addLog("validateApkFile: Package name mismatch: ${packageInfo.packageName}")
                    return ApkValidationResult.PACKAGE_NAME_MISMATCH
                }
                config.addLog("APK package identity verified: ${activity.packageName}")

                val archiveSignatures = packageInfo.signatures
                val currentPackageInfo = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
                val currentSignatures = currentPackageInfo.signatures

                if (archiveSignatures.isNullOrEmpty() || currentSignatures.isNullOrEmpty()) {
                    outError?.append("Signing signatures are missing.")
                    config.addLog("validateApkFile: Signatures are null or empty.")
                    return ApkValidationResult.SIGNING_INFO_MISSING
                }

                logSignerFingerprints("Current", currentSignatures)
                logSignerFingerprints("Downloaded", archiveSignatures)

                val archiveSigSet = archiveSignatures.toSet()
                val currentSigSet = currentSignatures.toSet()

                if (archiveSigSet != currentSigSet) {
                    appendSignatureSecurityError(outError)
                    config.addLog("APK signer verification failed: certificate mismatch.")
                    return ApkValidationResult.SIGNATURE_MISMATCH
                }
                config.addLog("APK signer verification passed.")
            }

            ApkValidationResult.SUCCESS
        } catch (e: Exception) {
            outError?.append("Error during APK validation: ${e.message}")
            config.addLog("validateApkFile failed: ${e.message}")
            ApkValidationResult.ERROR
        }
    }

    private fun appendSignatureSecurityError(outError: StringBuilder?) {
        outError?.append("Update blocked — invalid signature.\n\nThe downloaded APK is not signed with a certificate trusted for this TunnelGuard installation. The update will not be installed.")
    }

    private fun logSignerFingerprints(label: String, signatures: Array<android.content.pm.Signature>?) {
        signatures?.forEach {
            config.addLog("$label signer SHA-256: ${ApkSigningCertificateVerifier.sha256Fingerprint(it)}")
        }
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest()
    }

    fun uninstallCurrentVersion(): Boolean {
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:${activity.packageName}")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        return try {
            activity.startActivity(uninstallIntent)
            true
        } catch (e: Exception) {
            config.addLog("Failed to launch uninstall intent: ${e.message}", "ERROR")
            false
        }
    }

    fun installApkFile(versionName: String): Boolean {
        lastInstallFailureMessage = null
        lastInstallValidationResult = null
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
                lastInstallFailureMessage = "Downloaded APK file does not exist or is empty."
                lastInstallValidationResult = ApkValidationResult.FILE_NOT_FOUND_OR_EMPTY
                return false
            }

            config.addLog("Preparing to install downloaded APK: ${updateApkFile.absolutePath}")

            // This is deliberately the final gate. Earlier post-download validation is
            // useful feedback, but can never authorize a later installer launch.
            val hashBeforeValidation = sha256(updateApkFile)
            val validationError = StringBuilder()
            val validationResult = validateApkFileWithResult(updateApkFile, validationError)
            if (validationResult != ApkValidationResult.SUCCESS) {
                config.addLog("Installation blocked: final APK validation returned $validationResult.")
                lastInstallFailureMessage = validationError.toString().ifBlank {
                    "Update blocked because the downloaded APK could not be positively verified."
                }
                lastInstallValidationResult = validationResult
                if (updateApkFile.exists() && !updateApkFile.delete()) {
                    config.addLog("Installation blocked APK could not be deleted: ${updateApkFile.name}")
                }
                return false
            }
            val hashAfterValidation = sha256(updateApkFile)
            if (!hashBeforeValidation.contentEquals(hashAfterValidation)) {
                config.addLog("Installation blocked: APK changed during final validation.")
                lastInstallFailureMessage = "Update blocked because the downloaded APK changed during final security validation."
                lastInstallValidationResult = ApkValidationResult.FILE_CHANGED
                updateApkFile.delete()
                return false
            }
            config.addLog("Installation validation passed; APK SHA-256: ${hashAfterValidation.joinToString("") { "%02x".format(it) }}")

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
            lastInstallFailureMessage = "Failed to initialize or launch package installer intent."
            false
        }
    }

    fun showUpdateErrorDialog(
        errorMessage: String,
        validationResult: ApkValidationResult? = null
    ) {
        val repo = UpdateRepository.getInstance(activity)
        val targetUrl = com.tunnelguard.app.update.UpdateLinkIntent.preferredUrl(
            repo.getCachedApkUrl(),
            repo.getCachedReleaseUrl()
        )

        val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Update Check Failed")
            .setMessage("Could not check for updates or download update.\n\nDetails: $errorMessage")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }

        // A concrete validation failure is a trust decision, not a download
        // inconvenience. Do not offer an alternate APK path around that decision.
        val isTrustFailure = validationResult != null && validationResult != ApkValidationResult.SUCCESS
        if (!isTrustFailure && !targetUrl.isNullOrBlank()) {
            builder.setNeutralButton("Open Download Link") { dialog, _ ->
                dialog.dismiss()
                try {
                    activity.startActivity(com.tunnelguard.app.update.UpdateLinkIntent.create(targetUrl))
                } catch (e: Exception) {
                    config.addLog("Failed to open update link: ${e.message}")
                }
            }
        }
        builder.show()
    }
}
