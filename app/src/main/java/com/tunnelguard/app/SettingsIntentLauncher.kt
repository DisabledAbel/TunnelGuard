package com.tunnelguard.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Builds and safely launches version-compatible Android settings intents. */
internal object SettingsIntentLauncher {
    fun installPermissionIntents(packageName: String): List<Intent> {
        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        return listOf(preferred, Intent(Settings.ACTION_SETTINGS))
    }

    fun vpnSettingsIntents(): List<Intent> {
        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_VPN_SETTINGS)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        return listOf(preferred, Intent(Settings.ACTION_SETTINGS))
    }

    fun launch(intents: List<Intent>, startActivity: (Intent) -> Unit): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next, more general settings destination.
            } catch (_: SecurityException) {
                // The device denied this destination; try the safe fallback.
            }
        }
        return false
    }
}
