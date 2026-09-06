package com.tunnelguard.app

/** Stable identifiers used by the UI to offer an explicit repair operation. */
enum class HealthCheckAction {
    GRANT_VPN_PERMISSION, ENABLE_PROTECTION, MANAGE_PROTECTED_APPS,
    GRANT_USAGE_ACCESS, GRANT_OVERLAY_PERMISSION, GRANT_NOTIFICATIONS,
    ENABLE_START_ON_BOOT, SELECT_VPN_APP, OPEN_INSTALL_PERMISSION, OPEN_BATTERY_SETTINGS
}

enum class HealthCheckStatus { PASS, WARNING, FAIL, NOT_APPLICABLE, UNKNOWN }
enum class OverallHealthStatus { HEALTHY, ATTENTION_REQUIRED, UNPROTECTED, UNKNOWN }

data class HealthCheckResult(
    val id: String,
    val title: String,
    val status: HealthCheckStatus,
    val summary: String,
    val details: String? = null,
    val action: HealthCheckAction? = null,
    val securityCritical: Boolean = false
)

data class ProtectionHealthReport(
    val overall: OverallHealthStatus,
    val checks: List<HealthCheckResult>
) {
    fun diagnosticsText(): String = buildString {
        appendLine("=== PROTECTION HEALTH ===")
        appendLine("Overall: $overall")
        checks.forEach { appendLine("${it.title}: ${it.status} - ${it.summary}") }
    }.trimEnd()
}

enum class CountryHealth { NOT_REQUIRED, MATCH, MISMATCH, UNKNOWN }

/** Framework-independent snapshot. Keeping policy here makes every decision unit-testable. */
data class ProtectionHealthSnapshot(
    val protectionEnabled: Boolean,
    val vpnPermissionGranted: Boolean,
    val securityState: SecurityState,
    val serviceRunning: Boolean,
    val serviceStarting: Boolean,
    val tunnelEstablished: Boolean,
    val profileName: String,
    val protectedAppCount: Int,
    val everythingProfile: Boolean,
    val appMonitorEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val overlayGranted: Boolean,
    val notificationPermissionApplicable: Boolean,
    val notificationGranted: Boolean,
    val ipv6Active: Boolean,
    val dnsStatus: DNSStatus,
    val startOnBoot: Boolean,
    val lastBootFailure: String?,
    val autoConnect: Boolean,
    val vpnAppConfigured: Boolean,
    val vpnAppInstalled: Boolean,
    val countryHealth: CountryHealth,
    val appVersionKnown: Boolean,
    val installPermissionApplicable: Boolean,
    val installPermissionGranted: Boolean,
    val batteryRestricted: Boolean?
)

/** Pure, fail-closed protection health policy. It never changes VPN or configuration state. */
object ProtectionHealthChecker {
    fun evaluate(s: ProtectionHealthSnapshot): ProtectionHealthReport {
        val checks = listOf(
            vpnPermission(s),
            result("protection", "Protection", if (s.protectionEnabled) HealthCheckStatus.PASS else HealthCheckStatus.WARNING,
                if (s.protectionEnabled) "Protection is enabled." else "Protection is intentionally disabled; apps are not being enforced.", HealthCheckAction.ENABLE_PROTECTION.takeUnless { s.protectionEnabled }),
            security(s.securityState, s.protectionEnabled),
            service(s),
            protectedApps(s),
            permission("usage_access", "Usage Access", s.appMonitorEnabled, s.usageAccessGranted, HealthCheckAction.GRANT_USAGE_ACCESS),
            permission("overlay", "Overlay Permission", s.appMonitorEnabled, s.overlayGranted, HealthCheckAction.GRANT_OVERLAY_PERMISSION),
            notification(s),
            ipv4(s.securityState),
            ipv6(s),
            dns(s.dnsStatus),
            boot(s),
            monitor(s),
            autoConnect(s),
            country(s.countryHealth),
            updater(s),
            battery(s.batteryRestricted)
        )
        return ProtectionHealthReport(overall(checks), checks)
    }

    fun overall(checks: List<HealthCheckResult>): OverallHealthStatus = when {
        checks.any { it.securityCritical && it.status == HealthCheckStatus.FAIL } -> OverallHealthStatus.UNPROTECTED
        checks.any { it.securityCritical && it.status == HealthCheckStatus.UNKNOWN } -> OverallHealthStatus.UNKNOWN
        checks.any { it.status == HealthCheckStatus.FAIL || it.status == HealthCheckStatus.WARNING || it.status == HealthCheckStatus.UNKNOWN } -> OverallHealthStatus.ATTENTION_REQUIRED
        else -> OverallHealthStatus.HEALTHY
    }

    private fun vpnPermission(s: ProtectionHealthSnapshot) = when {
        !s.protectionEnabled && s.securityState == SecurityState.INACTIVE -> result("vpn_permission", "VPN Permission", HealthCheckStatus.NOT_APPLICABLE, "Protection is intentionally disabled.")
        s.vpnPermissionGranted -> result("vpn_permission", "VPN Permission", HealthCheckStatus.PASS, "Granted.", critical = true)
        else -> result("vpn_permission", "VPN Permission", HealthCheckStatus.FAIL, "Permission is required for fail-closed protection.", HealthCheckAction.GRANT_VPN_PERMISSION, true)
    }

    private fun security(state: SecurityState, enabled: Boolean) = when (state) {
        SecurityState.PROTECTED -> result("security_state", "Security State", HealthCheckStatus.PASS, "Protected by the active upstream VPN.", critical = true)
        SecurityState.BLOCKING -> result("security_state", "Security State", HealthCheckStatus.PASS, "Fail-closed blocking is active.", critical = true)
        SecurityState.CONNECTING -> result("security_state", "Security State", HealthCheckStatus.WARNING, "Protection is being established.", critical = true)
        SecurityState.INACTIVE -> result("security_state", "Security State", if (enabled) HealthCheckStatus.FAIL else HealthCheckStatus.WARNING, "Protection is inactive.", critical = enabled)
        SecurityState.ERROR -> result("security_state", "Security State", HealthCheckStatus.FAIL, "Protection encountered an error.", critical = true)
        SecurityState.UNPROTECTED_FAULT -> result("security_state", "Security State", HealthCheckStatus.FAIL, "Protection is expected but traffic is not secured.", critical = true)
    }

    private fun service(s: ProtectionHealthSnapshot) = when (s.securityState) {
        SecurityState.PROTECTED -> result("vpn_service", "Fail-Closed Service", HealthCheckStatus.PASS, "Not required while a valid upstream VPN is active.", critical = true)
        SecurityState.BLOCKING -> if (s.tunnelEstablished) result("vpn_service", "Fail-Closed Service", HealthCheckStatus.PASS, "Local blocking tunnel is established.", critical = true)
            else result("vpn_service", "Fail-Closed Service", HealthCheckStatus.FAIL, "Blocking is required but its tunnel is absent.", critical = true)
        SecurityState.CONNECTING -> result("vpn_service", "Fail-Closed Service", HealthCheckStatus.WARNING,
            if (s.serviceRunning || s.serviceStarting) "Service is starting." else "Service has not started.", critical = true)
        SecurityState.INACTIVE -> result("vpn_service", "Fail-Closed Service", HealthCheckStatus.NOT_APPLICABLE, "Protection is disabled.")
        else -> result("vpn_service", "Fail-Closed Service", HealthCheckStatus.FAIL, "The required protection service is unavailable.", critical = true)
    }

    private fun protectedApps(s: ProtectionHealthSnapshot): HealthCheckResult {
        val populated = s.everythingProfile || s.protectedAppCount > 0
        val count = if (s.everythingProfile) "all launcher apps (dynamic)" else "${s.protectedAppCount} app(s)"
        return result("protected_apps", "Protected Apps", if (populated) HealthCheckStatus.PASS else HealthCheckStatus.WARNING,
            "${s.profileName}: $count protected.", HealthCheckAction.MANAGE_PROTECTED_APPS.takeUnless { populated })
    }

    private fun permission(id: String, title: String, required: Boolean, granted: Boolean, action: HealthCheckAction) = when {
        !required -> result(id, title, HealthCheckStatus.NOT_APPLICABLE, "App Monitor is disabled.")
        granted -> result(id, title, HealthCheckStatus.PASS, "Granted.", critical = true)
        else -> result(id, title, HealthCheckStatus.FAIL, "Required by App Monitor.", action, true)
    }

    private fun notification(s: ProtectionHealthSnapshot) = when {
        !s.notificationPermissionApplicable -> result("notifications", "Notification Permission", HealthCheckStatus.NOT_APPLICABLE, "No runtime permission is required on this Android version.")
        s.notificationGranted -> result("notifications", "Notification Permission", HealthCheckStatus.PASS, "Granted.")
        else -> result("notifications", "Notification Permission", HealthCheckStatus.WARNING, "Notifications may be hidden; Android still permits protection to run.", HealthCheckAction.GRANT_NOTIFICATIONS)
    }

    private fun ipv4(state: SecurityState) = when (state) {
        SecurityState.PROTECTED -> result("ipv4", "IPv4 Protection", HealthCheckStatus.PASS, "IPv4 traffic is routed through the active VPN.", critical = true)
        SecurityState.BLOCKING -> result("ipv4", "IPv4 Protection", HealthCheckStatus.PASS, "IPv4 traffic is blocked safely by TunnelGuard.", critical = true)
        SecurityState.CONNECTING -> result("ipv4", "IPv4 Protection", HealthCheckStatus.UNKNOWN, "IPv4 protection is still being established.", critical = true)
        SecurityState.INACTIVE -> result("ipv4", "IPv4 Protection", HealthCheckStatus.NOT_APPLICABLE, "Protection is disabled.")
        else -> result("ipv4", "IPv4 Protection", HealthCheckStatus.FAIL, "IPv4 is not protected as configured.", critical = true)
    }

    private fun ipv6(s: ProtectionHealthSnapshot) = when (s.securityState) {
        SecurityState.PROTECTED -> result("ipv6", "IPv6 Protection", HealthCheckStatus.PASS, "IPv6 is routed through the active VPN.", critical = true)
        SecurityState.BLOCKING -> if (s.ipv6Active) result("ipv6", "IPv6 Protection", HealthCheckStatus.PASS, "IPv6 is captured by the fail-closed tunnel.", critical = true)
            else result("ipv6", "IPv6 Protection", HealthCheckStatus.WARNING, "IPv6 fail-closed interception is unsupported or could not be established.", critical = true)
        SecurityState.CONNECTING -> result("ipv6", "IPv6 Protection", HealthCheckStatus.UNKNOWN, "IPv6 protection cannot yet be verified.", critical = true)
        SecurityState.INACTIVE -> result("ipv6", "IPv6 Protection", HealthCheckStatus.NOT_APPLICABLE, "Protection is disabled.")
        else -> result("ipv6", "IPv6 Protection", HealthCheckStatus.FAIL, "IPv6 is known to be unprotected.", critical = true)
    }

    private fun dns(status: DNSStatus) = when (status) {
        DNSStatus.PROTECTED -> result("dns", "DNS Protection", HealthCheckStatus.PASS, "DNS uses protected routing.", critical = true)
        DNSStatus.WARNING -> result("dns", "DNS Protection", HealthCheckStatus.WARNING, "DNS protection may be incomplete.", critical = true)
        DNSStatus.UNKNOWN -> result("dns", "DNS Protection", HealthCheckStatus.UNKNOWN, "DNS protection could not be verified.", critical = true)
    }

    private fun boot(s: ProtectionHealthSnapshot) = when {
        !s.startOnBoot -> result("boot", "Start on Boot", HealthCheckStatus.WARNING, "Disabled; protection will not resume automatically.", HealthCheckAction.ENABLE_START_ON_BOOT)
        s.lastBootFailure != null -> result("boot", "Start on Boot", HealthCheckStatus.FAIL, "Most recent activation failed.", details = s.lastBootFailure)
        else -> result("boot", "Start on Boot", HealthCheckStatus.PASS, "Enabled with no known boot failure.")
    }

    private fun monitor(s: ProtectionHealthSnapshot) = when {
        !s.appMonitorEnabled -> result("app_monitor", "App Monitor", HealthCheckStatus.WARNING, "Disabled; foreground protected apps are not monitored.")
        !s.usageAccessGranted || !s.overlayGranted -> result("app_monitor", "App Monitor", HealthCheckStatus.FAIL,
            "Required permissions are missing.", critical = true)
        else -> result("app_monitor", "App Monitor", HealthCheckStatus.PASS, "Enabled and required permissions are granted.", critical = true)
    }

    private fun autoConnect(s: ProtectionHealthSnapshot) = when {
        !s.autoConnect -> result("auto_connect", "Auto-Connect", HealthCheckStatus.NOT_APPLICABLE, "Disabled.")
        !s.vpnAppConfigured -> result("auto_connect", "Auto-Connect", HealthCheckStatus.WARNING, "Enabled but no VPN application is selected.", HealthCheckAction.SELECT_VPN_APP)
        !s.vpnAppInstalled -> result("auto_connect", "Auto-Connect", HealthCheckStatus.WARNING, "The selected VPN application is no longer installed.", HealthCheckAction.SELECT_VPN_APP)
        else -> result("auto_connect", "Auto-Connect", HealthCheckStatus.PASS, "VPN application is configured and installed.")
    }

    private fun country(value: CountryHealth) = when (value) {
        CountryHealth.NOT_REQUIRED -> result("country", "Country Policy", HealthCheckStatus.NOT_APPLICABLE, "No country restriction applies.")
        CountryHealth.MATCH -> result("country", "Country Policy", HealthCheckStatus.PASS, "The active VPN satisfies the current country requirement.", critical = true)
        CountryHealth.MISMATCH -> result("country", "Country Policy", HealthCheckStatus.FAIL, "The active VPN is in the wrong required country.", critical = true)
        CountryHealth.UNKNOWN -> result("country", "Country Policy", HealthCheckStatus.UNKNOWN, "The VPN exit country could not be resolved.", critical = true)
    }

    private fun updater(s: ProtectionHealthSnapshot) = when {
        !s.appVersionKnown -> result("updater", "Updater Readiness", HealthCheckStatus.WARNING, "The installed app version cannot be determined.")
        s.installPermissionApplicable && !s.installPermissionGranted -> result("updater", "Updater Readiness", HealthCheckStatus.WARNING, "TunnelGuard can download updates, but Android does not allow APK installation.", HealthCheckAction.OPEN_INSTALL_PERMISSION)
        else -> result("updater", "Updater Readiness", HealthCheckStatus.PASS, "Local update components are ready.")
    }

    private fun battery(value: Boolean?) = when (value) {
        false -> result("battery", "Battery / Background", HealthCheckStatus.PASS, "Android reports no battery optimization for TunnelGuard.")
        true -> result("battery", "Battery / Background", HealthCheckStatus.WARNING, "Android may restrict background reliability.", HealthCheckAction.OPEN_BATTERY_SETTINGS)
        null -> result("battery", "Battery / Background", HealthCheckStatus.UNKNOWN, "This Android version does not expose a reliable status.")
    }

    private fun result(id: String, title: String, status: HealthCheckStatus, summary: String,
                       action: HealthCheckAction? = null, critical: Boolean = false, details: String? = null) =
        HealthCheckResult(id, title, status, summary, details, action, critical)
}
