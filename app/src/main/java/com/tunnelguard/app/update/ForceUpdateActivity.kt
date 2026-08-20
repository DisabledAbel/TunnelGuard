package com.tunnelguard.app.update

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunnelguard.app.TunnelGuardConfig
import com.tunnelguard.app.VersionComparator
import java.io.File

class ForceUpdateActivity : ComponentActivity() {
    private lateinit var config: TunnelGuardConfig
    private lateinit var updateRepository: UpdateRepository
    private lateinit var installerManager: InstallerManager
    private val viewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = TunnelGuardConfig(this)
        updateRepository = UpdateRepository.getInstance(this)
        installerManager = InstallerManager(this, config)

        val latestVersion = intent.getStringExtra(EXTRA_LATEST_VERSION) ?: updateRepository.getCachedLatestVersion() ?: config.getAppVersionName()
        viewModel.initialize(
            currentVersion = VersionComparator.validateAndNormalizeVersion(config.getAppVersionName()),
            result = UpdateCheckResult.UpdateAvailable(
                latestVersion = latestVersion,
                apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: updateRepository.getCachedApkUrl(),
                releaseNotes = intent.getStringExtra(EXTRA_RELEASE_NOTES) ?: updateRepository.getCachedReleaseNotes(),
                releaseName = intent.getStringExtra(EXTRA_RELEASE_NAME) ?: updateRepository.getCachedReleaseName(),
                releaseUrl = intent.getStringExtra(EXTRA_RELEASE_URL) ?: updateRepository.getCachedReleaseUrl(),
                publishedAt = intent.getStringExtra(EXTRA_PUBLISHED_AT) ?: updateRepository.getCachedPublishedAt()
            )
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })

        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            UpdateDialogScreen(
                state = state,
                onUpdateNow = { startUpdate(state) },
                onUninstallApp = { installerManager.uninstallCurrentVersion() },
                onExitApp = { finishAffinity() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (installerManager.canInstallPackages()) {
            val state = viewModel.state.value
            if (state.isDownloading) return
            val apkFile = updateApkFile(state.latestVersion) ?: return
            if (apkFile.exists() && apkFile.length() > 0) installIfValid(apkFile, state.latestVersion)
        }
    }

    private fun startUpdate(state: UpdateUiState) {
        if (!installerManager.canInstallPackages()) {
            Toast.makeText(this, "Enable Install unknown apps permission to install the update.", Toast.LENGTH_LONG).show()
            installerManager.openInstallPermissionSettings()
            return
        }
        if (!state.latestVersion.matches(SAFE_VERSION)) {
            viewModel.setError("The latest release version is malformed and cannot be installed safely.")
            return
        }
        viewModel.download(updateApkFile(state.latestVersion)!!) { apkFile -> installIfValid(apkFile, state.latestVersion) }
    }

    private fun updateApkFile(versionName: String): File? {
        if (!versionName.matches(SAFE_VERSION)) return null
        val updatesDir = File(cacheDir, "updates").canonicalFile
        val file = File(updatesDir, "TunnelGuard-v$versionName-update.apk").canonicalFile
        require(file.parentFile?.canonicalPath == updatesDir.canonicalPath) { "Invalid update file path" }
        return file
    }

    private fun installIfValid(apkFile: File, versionName: String) {
        val error = StringBuilder()
        if (installerManager.validate(apkFile, error)) {
            if (!installerManager.install(versionName)) viewModel.setError("Failed to launch the Android package installer.")
        } else {
            apkFile.delete()
            val errorMsg = error.toString().ifBlank { "Downloaded APK file validation failed." }
            val isMismatch = errorMsg.contains("Signature mismatch", ignoreCase = true)
            viewModel.setError(errorMsg, isSignatureMismatch = isMismatch)
        }
    }

    companion object {
        const val EXTRA_LATEST_VERSION = "latest_version"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_RELEASE_NOTES = "release_notes"
        const val EXTRA_RELEASE_NAME = "release_name"
        const val EXTRA_RELEASE_URL = "release_url"
        const val EXTRA_PUBLISHED_AT = "published_at"
        private val SAFE_VERSION = Regex("^[0-9A-Za-z._-]+$")
    }
}
