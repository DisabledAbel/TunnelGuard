package com.tunnelguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionHealthCheckerTest {
    private fun healthy() = ProtectionHealthSnapshot(
        protectionEnabled = true, vpnPermissionGranted = true, securityState = SecurityState.PROTECTED,
        serviceRunning = false, serviceStarting = false, tunnelEstablished = false,
        profileName = "Streaming", protectedAppCount = 2, everythingProfile = false,
        appMonitorEnabled = true, usageAccessGranted = true, overlayGranted = true,
        notificationPermissionApplicable = true, notificationGranted = true, ipv6Active = true,
        dnsStatus = DNSStatus.PROTECTED, startOnBoot = true, lastBootFailure = null,
        autoConnect = true, vpnAppConfigured = true, vpnAppInstalled = true,
        countryHealth = CountryHealth.MATCH, appVersionKnown = true,
        installPermissionApplicable = true, installPermissionGranted = true, batteryRestricted = false
    )

    private fun check(snapshot: ProtectionHealthSnapshot, id: String) =
        ProtectionHealthChecker.evaluate(snapshot).checks.first { it.id == id }

    @Test fun allCriticalChecksPassIsHealthy() = assertEquals(OverallHealthStatus.HEALTHY, ProtectionHealthChecker.evaluate(healthy()).overall)
    @Test fun protectedIsSecure() = assertEquals(HealthCheckStatus.PASS, check(healthy(), "security_state").status)
    @Test fun blockingWithTunnelIsSecure() {
        val report = ProtectionHealthChecker.evaluate(healthy().copy(securityState = SecurityState.BLOCKING, tunnelEstablished = true, ipv6Active = true))
        assertEquals(HealthCheckStatus.PASS, report.checks.first { it.id == "security_state" }.status)
        assertNotEquals(OverallHealthStatus.UNPROTECTED, report.overall)
    }
    @Test fun faultIsUnprotected() = assertEquals(OverallHealthStatus.UNPROTECTED, ProtectionHealthChecker.evaluate(healthy().copy(securityState = SecurityState.UNPROTECTED_FAULT)).overall)
    @Test fun missingVpnPermissionFails() = assertEquals(HealthCheckStatus.FAIL, check(healthy().copy(vpnPermissionGranted = false), "vpn_permission").status)
    @Test fun usageOnlyRequiredForMonitor() {
        assertEquals(HealthCheckStatus.FAIL, check(healthy().copy(usageAccessGranted = false), "usage_access").status)
        assertEquals(HealthCheckStatus.NOT_APPLICABLE, check(healthy().copy(appMonitorEnabled = false, usageAccessGranted = false), "usage_access").status)
    }
    @Test fun overlayOnlyRequiredForMonitor() {
        assertEquals(HealthCheckStatus.FAIL, check(healthy().copy(overlayGranted = false), "overlay").status)
        assertEquals(HealthCheckStatus.NOT_APPLICABLE, check(healthy().copy(appMonitorEnabled = false, overlayGranted = false), "overlay").status)
    }
    @Test fun emptyAppsWarnButEverythingDoesNot() {
        assertEquals(HealthCheckStatus.WARNING, check(healthy().copy(protectedAppCount = 0), "protected_apps").status)
        assertEquals(HealthCheckStatus.PASS, check(healthy().copy(protectedAppCount = 0, everythingProfile = true), "protected_apps").status)
    }
    @Test fun unsupportedIpv6NeverPasses() = assertNotEquals(HealthCheckStatus.PASS, check(healthy().copy(securityState = SecurityState.BLOCKING, tunnelEstablished = true, ipv6Active = false), "ipv6").status)
    @Test fun unknownDnsNeverPassesOrMakesHealthy() {
        val report = ProtectionHealthChecker.evaluate(healthy().copy(dnsStatus = DNSStatus.UNKNOWN))
        assertEquals(HealthCheckStatus.UNKNOWN, report.checks.first { it.id == "dns" }.status)
        assertEquals(OverallHealthStatus.UNKNOWN, report.overall)
    }
    @Test fun bootFailureDetected() = assertEquals(HealthCheckStatus.FAIL, check(healthy().copy(lastBootFailure = "permission revoked"), "boot").status)
    @Test fun countryMismatchDetected() = assertEquals(HealthCheckStatus.FAIL, check(healthy().copy(countryHealth = CountryHealth.MISMATCH), "country").status)
    @Test fun autoConnectWithoutProviderWarnsWithAction() {
        val value = check(healthy().copy(vpnAppConfigured = false), "auto_connect")
        assertEquals(HealthCheckStatus.WARNING, value.status)
        assertEquals(HealthCheckAction.SELECT_VPN_APP, value.action)
    }
    @Test fun missingInstallPermissionWarnsWithAction() {
        val value = check(healthy().copy(installPermissionGranted = false), "updater")
        assertEquals(HealthCheckStatus.WARNING, value.status)
        assertEquals(HealthCheckAction.OPEN_INSTALL_PERMISSION, value.action)
    }
    @Test fun criticalUnknownCannotBeHealthy() {
        val result = ProtectionHealthChecker.overall(listOf(HealthCheckResult("x", "x", HealthCheckStatus.UNKNOWN, "x", securityCritical = true)))
        assertEquals(OverallHealthStatus.UNKNOWN, result)
    }
    @Test fun permissionFixActionsAreExplicit() {
        assertEquals(HealthCheckAction.GRANT_VPN_PERMISSION, check(healthy().copy(vpnPermissionGranted = false), "vpn_permission").action)
        assertEquals(HealthCheckAction.GRANT_USAGE_ACCESS, check(healthy().copy(usageAccessGranted = false), "usage_access").action)
        assertEquals(HealthCheckAction.GRANT_OVERLAY_PERMISSION, check(healthy().copy(overlayGranted = false), "overlay").action)
    }
}
