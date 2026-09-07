package com.tunnelguard.app.vpnprovider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Safe launcher-only integration. It never changes or reports VPN connection state. */
open class GenericVpnProviderAdapter(
    final override val packageName: String,
    private val knownName: String? = null
) : VpnProviderAdapter {
    override val integrationLevel = if (knownName == null) VpnIntegrationLevel.GENERIC else VpnIntegrationLevel.STANDARD
    override val capabilities = VpnProviderCapabilities()

    override fun getDisplayName(context: Context): String = knownName ?: try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) { packageName }

    override fun buildLaunchRequest(context: Context, request: VpnLaunchRequest): VpnLaunchResult {
        val pm = context.packageManager
        try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return VpnLaunchResult.Unavailable("configured VPN application is not installed")
        } catch (e: RuntimeException) {
            return VpnLaunchResult.Error(e.message ?: "package inspection failed")
        }

        val intent = try {
            pm.getLaunchIntentForPackage(packageName)
        } catch (e: RuntimeException) {
            return VpnLaunchResult.Error(e.message ?: "launcher lookup failed")
        } ?: return VpnLaunchResult.Unavailable("launcher activity unavailable")

        // Package scoping plus ownership validation prevents another application from handling it.
        intent.setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return VpnLaunchResult.Unavailable("launcher activity unavailable")
        if (resolved.activityInfo?.packageName != packageName ||
            intent.component?.packageName?.let { it != packageName } == true) {
            return VpnLaunchResult.Error("launcher activity is not owned by configured provider")
        }
        return VpnLaunchResult.Ready(intent, getDisplayName(context))
    }
}
