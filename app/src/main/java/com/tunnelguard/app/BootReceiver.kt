package com.tunnelguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = TunnelGuardConfig(context)
            if (config.isStartOnBootEnabled() && config.isProtectionEnabled()) {
                config.addLog("Boot completed: evaluating TunnelGuard start-on-boot configuration.")
                try {
                    // Check VPN permission first to prevent crashing or illegal states on boot
                    val vpnPrepared = VpnService.prepare(context) == null
                    if (!vpnPrepared) {
                        config.addLog("Boot completed: Cannot start TunnelGuard because VPN permission is not granted.", "ERROR")
                        config.setLastBootFailure("VPN permission not granted.")
                        return
                    }

                    // Clear previous boot failure since we are attempting start
                    config.setLastBootFailure(null)
                    config.addLog("Boot completed: starting TunnelGuard protection service.")

                    val serviceIntent = Intent(context, TunnelGuardVpnService::class.java).apply {
                        action = TunnelGuardVpnService.ACTION_START
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    config.addLog("Boot completed exception during startup: ${e.message}", "ERROR")
                    config.setLastBootFailure(e.message ?: "Unknown BootReceiver exception")
                }
            }
        }
    }
}
