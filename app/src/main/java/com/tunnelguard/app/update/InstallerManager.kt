package com.tunnelguard.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tunnelguard.app.TunnelGuardConfig
import com.tunnelguard.app.UpdateManager
import java.io.File

class InstallerManager(private val activity: Activity, private val config: TunnelGuardConfig) {
    fun canInstallPackages(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val primary = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { data = Uri.parse("package:${activity.packageName}") }
        try {
            activity.startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Both intents failed, nothing more we can do
            }
        }
    }

    fun validate(apkFile: File, outError: StringBuilder): Boolean = UpdateManager(activity, config).validateApkFile(apkFile, outError)

    fun validateWithResult(apkFile: File, outError: StringBuilder): com.tunnelguard.app.ApkValidationResult = UpdateManager(activity, config).validateApkFileWithResult(apkFile, outError)

    fun install(versionName: String): Boolean = UpdateManager(activity, config).installApkFile(versionName)

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
}
