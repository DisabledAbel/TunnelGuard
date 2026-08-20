package com.tunnelguard.app.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val currentVersion: String = "",
    val latestVersion: String = "",
    val releaseName: String = "TunnelGuard update",
    val releaseNotes: String = "No release notes provided.",
    val releaseUrl: String = "",
    val publishedAt: String = "",
    val apkUrl: String? = null,
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val isSignatureMismatch: Boolean = false
)

class UpdateViewModel(
    private val downloadManager: DownloadManager = DownloadManager()
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var initialized = false

    fun initialize(currentVersion: String, result: UpdateCheckResult.UpdateAvailable) {
        if (initialized) return
        initialized = true
        _state.value = UpdateUiState(
            currentVersion = currentVersion,
            latestVersion = result.latestVersion,
            releaseName = result.releaseName ?: "TunnelGuard v${result.latestVersion}",
            releaseNotes = result.releaseNotes?.ifBlank { "No release notes provided." } ?: "No release notes provided.",
            releaseUrl = result.releaseUrl.orEmpty(),
            publishedAt = result.publishedAt.orEmpty(),
            apkUrl = result.apkUrl
        )
    }

    fun setError(message: String?, isSignatureMismatch: Boolean = false) {
        _state.value = _state.value.copy(errorMessage = message, isSignatureMismatch = isSignatureMismatch)
    }

    fun download(apkFile: File, onDownloaded: (File) -> Unit) {
        val url = _state.value.apkUrl
        if (url.isNullOrBlank()) { setError("No APK download asset was found in the latest GitHub release."); return }
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isDownloading = true, progress = 0, errorMessage = null)
                val file = downloadManager.downloadApk(url, apkFile) { pct -> _state.value = _state.value.copy(progress = pct) }
                _state.value = _state.value.copy(isDownloading = false, progress = 100)
                onDownloaded(file)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isDownloading = false, errorMessage = "Failed to download update: ${e.message}")
            }
        }
    }
}
