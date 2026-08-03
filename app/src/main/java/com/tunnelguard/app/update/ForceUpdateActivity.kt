package com.tunnelguard.app.update

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tunnelguard.app.R
import com.tunnelguard.app.TunnelGuardConfig
import com.tunnelguard.app.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ForceUpdateActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private lateinit var updateRepository: UpdateRepository

    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var tvReleaseNotes: TextView
    private lateinit var layoutProgress: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var btnUpdate: Button

    private var latestVersion: String? = null
    private var apkUrl: String? = null
    private var releaseNotes: String? = null

    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_force_update)

        config = TunnelGuardConfig(this)
        updateRepository = UpdateRepository.getInstance(this)

        latestVersion = intent.getStringExtra("latest_version")
        apkUrl = intent.getStringExtra("apk_url")
        releaseNotes = intent.getStringExtra("release_notes")

        if (latestVersion == null) {
            latestVersion = updateRepository.getCachedLatestVersion() ?: "1.0.0"
            apkUrl = updateRepository.getCachedApkUrl()
            releaseNotes = updateRepository.getCachedReleaseNotes()
        }

        tvCurrentVersion = findViewById(R.id.tv_current_version)
        tvLatestVersion = findViewById(R.id.tv_latest_version)
        tvReleaseNotes = findViewById(R.id.tv_release_notes)
        layoutProgress = findViewById(R.id.layout_progress)
        progressBar = findViewById(R.id.progress_bar)
        tvProgressPercent = findViewById(R.id.tv_progress_percent)
        btnUpdate = findViewById(R.id.btn_update)

        tvCurrentVersion.text = "Current: ${config.getAppVersionName()}"
        tvLatestVersion.text = "Latest: $latestVersion"
        if (!releaseNotes.isNullOrBlank()) {
            tvReleaseNotes.text = releaseNotes
        }

        btnUpdate.requestFocus()
        btnUpdate.setOnClickListener {
            checkPermissionAndStartDownload()
        }

        // Disable back button completely via OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing to prevent dismissing the force update screen
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // If the user went to settings to enable "Install Unknown Apps" permission and returned, resume installation
        if (!isDownloading && latestVersion != null && apkUrl != null) {
            val updateMgr = UpdateManager(this, config)
            if (updateMgr.validateVersionName(latestVersion!!)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (packageManager.canRequestPackageInstalls()) {
                        try {
                            val updatesDir = File(cacheDir, "updates").canonicalFile
                            val apkFile = File(updatesDir, "TunnelGuard-v$latestVersion-update.apk").canonicalFile
                            val parentFile = apkFile.parentFile
                            if (parentFile != null && parentFile.canonicalPath == updatesDir.canonicalPath) {
                                if (apkFile.exists() && apkFile.length() > 0) {
                                    // Validate and install if the file is already downloaded successfully
                                    val errorBuilder = StringBuilder()
                                    if (validateApkFile(apkFile, errorBuilder)) {
                                        updateMgr.installApkFile(latestVersion!!)
                                    } else {
                                        val errorMsg = errorBuilder.toString()
                                        androidx.appcompat.app.AlertDialog.Builder(this)
                                            .setTitle("Update Validation Failed")
                                            .setMessage(errorMsg.ifBlank { "Downloaded APK file validation failed." })
                                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                            .show()
                                        if (apkFile.exists()) {
                                            apkFile.delete()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            config.addLog("onResume download-resume check failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStartDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                config.addLog("Install unknown apps permission is NOT granted.")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To install updates, TunnelGuard requires the 'Install unknown apps' permission. Please enable it in the next screen.")
                    .setPositiveButton("Settings") { dialog, _ ->
                        dialog.dismiss()
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(Settings.ACTION_SETTINGS))
                            } catch (ex: Exception) {
                                config.addLog("Failed to open settings: ${ex.message}")
                            }
                        }
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }
        startDownload()
    }

    private fun startDownload() {
        val urlToDownload = apkUrl
        if (urlToDownload == null) {
            Toast.makeText(this, "No download URL available", Toast.LENGTH_LONG).show()
            return
        }

        isDownloading = true
        btnUpdate.isEnabled = false
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgressPercent.text = "0%"

        // Run the background task lifecycle-safely using lifecycleScope
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val updateMgr = UpdateManager(this@ForceUpdateActivity, config)
                if (!updateMgr.validateVersionName(latestVersion!!)) {
                    throw IllegalArgumentException("Invalid version name format: $latestVersion")
                }

                val updatesDir = File(cacheDir, "updates").canonicalFile
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }
                val updateApkFile = File(updatesDir, "TunnelGuard-v$latestVersion-update.apk").canonicalFile
                val parentFile = updateApkFile.parentFile ?: throw IllegalArgumentException("Invalid parent file path")
                if (parentFile.canonicalPath != updatesDir.canonicalPath) {
                    throw IllegalArgumentException("Path traversal detected in version name: $latestVersion")
                }

                withContext(Dispatchers.IO) {
                    downloadUrlWithRedirects(urlToDownload, updateApkFile) { progress, total ->
                        val percentage = if (total > 0) (progress * 100L / total).toInt() else 0
                        // Since callback is triggered from network thread, switch to Main thread safely
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                progressBar.progress = percentage
                                tvProgressPercent.text = "$percentage%"
                            }
                        }
                    }
                }

                if (!isFinishing && !isDestroyed) {
                    isDownloading = false
                    btnUpdate.isEnabled = true

                    // Validate APK before installing
                    val errorBuilder = StringBuilder()
                    if (validateApkFile(updateApkFile, errorBuilder)) {
                        val installSuccess = updateMgr.installApkFile(latestVersion!!)
                        if (!installSuccess) {
                            Toast.makeText(this@ForceUpdateActivity, "Failed to launch package installer", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val errorMsg = errorBuilder.toString()
                        androidx.appcompat.app.AlertDialog.Builder(this@ForceUpdateActivity)
                            .setTitle("Update Validation Failed")
                            .setMessage(errorMsg.ifBlank { "Downloaded APK file validation failed." })
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                        if (updateApkFile.exists()) {
                            updateApkFile.delete()
                        }
                    }
                }

            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    isDownloading = false
                    btnUpdate.isEnabled = true
                    Toast.makeText(this@ForceUpdateActivity, "Failed to download update: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun validateApkFile(apkFile: File, outError: StringBuilder? = null): Boolean {
        val updateMgr = UpdateManager(this, config)
        return updateMgr.validateApkFile(apkFile, outError)
    }

    private fun downloadUrlWithRedirects(urlString: String, outputFile: File, progressUpdate: (Int, Int) -> Unit) {
        if (!urlString.lowercase().startsWith("https://")) {
            throw SecurityException("Insecure initial URL scheme: $urlString")
        }
        val initialUrlObj = URL(urlString)
        val initialHost = initialUrlObj.host.lowercase()
        if (initialHost != "github.com" && !initialHost.endsWith(".github.com")) {
            throw SecurityException("Invalid download source host: $initialHost")
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
                    val resolvedUrlObj = URL(URL(currentUrl), newUrl)
                    if (resolvedUrlObj.protocol.lowercase() != "https") {
                        throw SecurityException("Insecure redirect to non-HTTPS URL: $resolvedUrlObj")
                    }
                    val newUrlString = resolvedUrlObj.toString()
                    val host = resolvedUrlObj.host.lowercase()
                    val isValidRedirectHost = (host == "github.com" || host.endsWith(".github.com") ||
                            host == "githubusercontent.com" || host.endsWith(".githubusercontent.com"))
                    if (!isValidRedirectHost) {
                        throw SecurityException("Insecure redirect host: $host")
                    }
                    currentUrl = newUrlString
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
}
