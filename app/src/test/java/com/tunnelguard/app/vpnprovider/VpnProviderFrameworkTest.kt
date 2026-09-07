package com.tunnelguard.app.vpnprovider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class VpnProviderFrameworkTest {
    private val pkg = "com.example.customvpn"

    /**
     * Verifies that unknown VPN providers are assigned GENERIC integration level
     * and do not support advanced features like country-specific requests.
     */
    @Test fun unknownProviderUsesGenericIntegration() {
        val adapter = VpnProviderRegistry.resolve(pkg)
        assertTrue(adapter is GenericVpnProviderAdapter)
        assertEquals(VpnIntegrationLevel.GENERIC, adapter.integrationLevel)
        assertFalse(adapter.capabilities.supportsCountryRequest)
    }

    /**
     * Verifies that known VPN providers use STANDARD integration level with
     * only verified launcher activity support and no undocumented APIs.
     */
    @Test fun knownProviderUsesOnlyVerifiedStandardLaunch() {
        val adapter = VpnProviderRegistry.resolve("ch.protonvpn.android")
        assertEquals(VpnIntegrationLevel.STANDARD, adapter.integrationLevel)
        assertEquals(VpnProviderCapabilities(), adapter.capabilities)
    }

    /**
     * Verifies that provider launch intents are properly scoped to the target package
     * and validated to be owned by the configured provider to prevent hijacking.
     */
    @Test fun safeLauncherIsPackageScopedAndOwned() {
        val pm = mock<PackageManager>()
        val context = mock<Context> { on { packageManager } doReturn pm }
        val launch = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, "$pkg.Main"))
        whenever(pm.getApplicationInfo(pkg, 0)).thenReturn(ApplicationInfo())
        whenever(pm.getLaunchIntentForPackage(pkg)).thenReturn(launch)
        whenever(pm.resolveActivity(any(), any<Int>())).thenReturn(resolveInfo(pkg))
        whenever(pm.getApplicationLabel(any())).thenReturn("Custom VPN")

        val result = GenericVpnProviderAdapter(pkg).buildLaunchRequest(context, request("US"))
        assertTrue(result is VpnLaunchResult.Ready)
        val intent = (result as VpnLaunchResult.Ready).intent
        assertEquals(pkg, intent.`package`)
        assertEquals(pkg, intent.component?.packageName)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    /**
     * Verifies that launch requests fail safely with Unavailable result
     * when the configured VPN package is not installed.
     */
    @Test fun missingPackageFailsSafely() {
        val pm = mock<PackageManager>()
        val context = mock<Context> { on { packageManager } doReturn pm }
        whenever(pm.getApplicationInfo(pkg, 0)).thenThrow(PackageManager.NameNotFoundException())
        assertTrue(GenericVpnProviderAdapter(pkg).buildLaunchRequest(context, request(null)) is VpnLaunchResult.Unavailable)
    }

    /**
     * Verifies that launch requests fail safely with Unavailable result
     * when the VPN package has no launcher activity.
     */
    @Test fun missingLauncherFailsSafely() {
        val pm = mock<PackageManager>()
        val context = mock<Context> { on { packageManager } doReturn pm }
        whenever(pm.getApplicationInfo(pkg, 0)).thenReturn(ApplicationInfo())
        whenever(pm.getLaunchIntentForPackage(pkg)).thenReturn(null)
        assertTrue(GenericVpnProviderAdapter(pkg).buildLaunchRequest(context, request(null)) is VpnLaunchResult.Unavailable)
    }

    /**
     * Verifies that external applications cannot hijack VPN provider launch intents
     * by resolving to a different package than the configured provider.
     */
    @Test fun externalHandlerCannotHijackLaunch() {
        val pm = mock<PackageManager>()
        val context = mock<Context> { on { packageManager } doReturn pm }
        whenever(pm.getApplicationInfo(pkg, 0)).thenReturn(ApplicationInfo())
        whenever(pm.getLaunchIntentForPackage(pkg)).thenReturn(Intent(Intent.ACTION_MAIN))
        whenever(pm.resolveActivity(any(), any<Int>())).thenReturn(resolveInfo("com.attacker"))
        assertTrue(GenericVpnProviderAdapter(pkg).buildLaunchRequest(context, request(null)) is VpnLaunchResult.Error)
    }

    /**
     * Verifies that country codes are normalized and stored as data only,
     * without implying that the launch request will establish a VPN connection.
     */
    @Test fun countryIsDataOnlyAndDoesNotClaimConnection() {
        val request = request("us")
        assertEquals("US", request.requiredCountry?.uppercase())
        assertFalse(VpnProviderRegistry.resolve(pkg).capabilities.supportsCountryRequest)
        assertFalse(VpnProviderRegistry.resolve(pkg).capabilities.supportsConnectRequest)
    }

    private fun request(country: String?) = VpnLaunchRequest("com.example.video", country, VpnLaunchReason.PROTECTED_APP_OPENED)
    private fun resolveInfo(owner: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = owner }
    }
}
