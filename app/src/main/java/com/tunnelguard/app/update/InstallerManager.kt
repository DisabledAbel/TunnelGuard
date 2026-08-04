package com.tunnelguard.app.update

import android.app.Activity
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
        try { activity.startActivity(primary) } catch (_: Exception) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun validate(apkFile: File, outError: StringBuilder): Boolean = UpdateManager(activity, config).validateApkFile(apkFile, outError)

    fun install(versionName: String): Boolean = UpdateManager(activity, config).installApkFile(versionName)
}
