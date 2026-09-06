package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager

/** Android-facing observation layer. No method here starts or stops protection. */
class ProtectionHealthCollector(private val context: Context, private val config: TunnelGuardConfig) {
    fun collect(): ProtectionHealthReport {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val security = SecurityStateMachine.getSecurityState(
            context, config, TunnelGuardVpnService.isServiceRunning,
            TunnelGuardVpnService.isServiceStarting, TunnelGuardVpnService.isTunnelEstablished, connectivity
        )
        val profileId = config.getSelectedProfileId()
        val profileName = config.getProfiles().firstOrNull { it.id == profileId }?.name ?: profileId
        val apps = config.getProtectedApps()
        val vpnPackage = config.getVpnAppOfChoice()
        val foreground = config.getForegroundPackageName(context)
        val foregroundPolicy = config.getForegroundVpnPolicy(foreground)
        val requiredCountry = foregroundPolicy.requiredCountry
        val country = when {
            foregroundPolicy is ForegroundVpnPolicy.Unknown && foregroundPolicy.hasProtectedCountryOverrides -> CountryHealth.UNKNOWN
            requiredCountry == "ANY" -> CountryHealth.NOT_REQUIRED
            security != SecurityState.PROTECTED -> CountryHealth.UNKNOWN
            config.getActiveVpnCountryCode().isBlank() -> CountryHealth.UNKNOWN
            config.getActiveVpnCountryCode().equals(requiredCountry, true) -> CountryHealth.MATCH
            else -> CountryHealth.MISMATCH
        }
        val installGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
        val batteryRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isIgnoringBatteryOptimizations(context.packageName)?.not()
        } else null
        val snapshot = ProtectionHealthSnapshot(
            config.isProtectionEnabled(), VpnService.prepare(context) == null, security,
            TunnelGuardVpnService.isServiceRunning, TunnelGuardVpnService.isServiceStarting,
            TunnelGuardVpnService.isTunnelEstablished, profileName, apps.size, profileId == "everything",
            config.isAppMonitorEnabled(), config.hasUsageStatsPermission(context),
            config.hasSystemAlertWindowPermission(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            config.hasNotificationPermission(), config.isIpv6ProtectionActive(),
            config.detectDnsStatus(connectivity, TunnelGuardVpnService.isServiceRunning),
            config.isStartOnBootEnabled(), config.getLastBootFailure(), config.isAutoConnectVpnEnabled(),
            !vpnPackage.isNullOrBlank(), vpnPackage?.let(::isInstalled) ?: false, country,
            config.getAppVersionName().isNotBlank(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            installGranted, batteryRestricted
        )
        return ProtectionHealthChecker.evaluate(snapshot)
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: Exception) { false }
}
