package com.tunnelguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives coarse alarm and wall-clock changes, then feeds them through the shared evaluator. */
class ProfileScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val config = TunnelGuardConfig(context.applicationContext)
        when (intent.action) {
            ProfileScheduleManager.ACTION_BOUNDARY -> config.addLog("Schedule boundary reached; re-evaluating profile automation")
            Intent.ACTION_TIMEZONE_CHANGED -> config.addLog("System timezone changed; rescheduling profile automation")
            Intent.ACTION_TIME_CHANGED -> config.addLog("System time changed; rescheduling profile automation")
            Intent.ACTION_BOOT_COMPLETED -> config.addLog("Boot completed; evaluating scheduled profile automation")
        }
        ProfileAutomationManager.resume()
        ProfileAutomationManager.onNetworkChanged(context.applicationContext, immediate = true)
        ProfileScheduleManager.schedule(context.applicationContext)
    }
}
