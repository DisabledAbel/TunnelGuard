package com.tunnelguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = TunnelGuardConfig(context)
            if (config.isStartOnBootEnabled() && config.isProtectionEnabled()) {
                config.addLog("Boot completed: starting TunnelGuard protection service.")
                val serviceIntent = Intent(context, TunnelGuardVpnService::class.java).apply {
                    action = TunnelGuardVpnService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
