package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import com.tunnelguard.app.vpnprovider.VpnLaunchRequest
import com.tunnelguard.app.vpnprovider.VpnLaunchReason
import com.tunnelguard.app.vpnprovider.VpnLaunchResult
import com.tunnelguard.app.vpnprovider.VpnProviderRegistry

/** Collects current facts and delegates the actual outcome to [PolicyDecisionResolver]. */
class PolicyTester(private val context: Context, private val config: TunnelGuardConfig = TunnelGuardConfig(context)) {
    fun evaluate(packageName: String, now: Long = System.currentTimeMillis()): PolicyTestResult {
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
        val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: packageName
        val profiles = runCatching { config.getProfiles() }.getOrDefault(emptyList())
        val profile = profiles.find { it.id == config.getSelectedProfileId() }
        val protected = appInfo != null && runCatching { config.isAppProtected(packageName) }.getOrDefault(false)
        val emergency = config.isEmergencyLockEnabled()
        val storedOverride = TemporaryOverrideManager.snapshot(context).find { it.packageName == packageName }
        val activeOverride = storedOverride != null && !emergency
        val policy = runCatching { config.resolveEffectiveVpnPolicy(packageName, emergency) }.getOrNull()
        val provider = policy?.providerPackage?.let(VpnProviderRegistry::resolve)
        val providerName = provider?.let { runCatching { it.getDisplayName(context) }.getOrNull() }
        val providerInstalled = policy?.providerPackage?.let { runCatching { pm.getApplicationInfo(it, 0); true }.getOrDefault(false) }
        val providerLaunchable = provider?.let {
            it.buildLaunchRequest(context, VpnLaunchRequest(packageName,
                policy.requiredCountryCode.takeUnless { country -> country == "ANY" }, VpnLaunchReason.MANUAL_RECOVERY)) is VpnLaunchResult.Ready
        }
        val upstream = policy?.let {
            if (!it.requireVpn) UpstreamVpnEvaluation.Valid() else config.evaluateUpstreamVpn(
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager,
                requiredCountryCode = it.requiredCountryCode.takeIf { country -> it.requireCountryMatch && country != "ANY" })
        } ?: UpstreamVpnEvaluation.Unknown
        val resolved = PolicyDecisionResolver.resolve(PolicyDecisionInput(protected, policy, upstream,
            emergency, activeOverride, if (policy?.providerPackage == null) null else providerLaunchable))
        return PolicyTestResult(packageName, label, appInfo != null, protected, profile?.id, profile?.name,
            config.getProfileSelectionSource(), profile?.let { if (it.id == "everything") config.getAllLauncherApps().size else it.appPackages.size } ?: 0,
            emergency, storedOverride, emergency && storedOverride != null, policy, providerName, providerInstalled,
            providerLaunchable, upstream, resolved.decision, resolved.reason, now,
            if (appInfo == null) listOf("The selected package is no longer installed.") else emptyList())
    }
}
