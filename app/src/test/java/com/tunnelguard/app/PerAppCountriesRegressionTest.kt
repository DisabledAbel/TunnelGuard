package com.tunnelguard.app

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class PerAppCountriesRegressionTest {
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tunnel_guard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        config = TunnelGuardConfig(context)
        config.setSelectedProfileId("custom")
    }

    @Test
    fun existingSelectionsRemainVisibleThroughCountryScreenModel() {
        config.setAppVpnCountry("com.example.video", "JP")

        assertEquals("JP", config.getAppVpnCountry("com.example.video"))
        assertEquals("Japan (JP)", PerAppCountriesActivity.countryLabel(config.getAppVpnCountry("com.example.video")))
        config.setAppVpnCountry("com.example.legacy", "BR")
        assertEquals("BR", PerAppCountriesActivity.countryLabel(config.getAppVpnCountry("com.example.legacy")))
    }

    @Test
    fun selectingAndClearingPreservesNormalProtection() {
        config.setAppProtected("com.example.video", true)
        config.setAppVpnCountry("com.example.video", "US")
        assertEquals("US", config.getAppVpnCountry("com.example.video"))

        config.setAppVpnCountry("com.example.video", null)

        assertNull(config.getAppVpnCountry("com.example.video"))
        assertTrue(config.isAppProtected("com.example.video"))
        assertEquals("ANY", config.getForegroundVpnPolicy("com.example.video").requiredCountry)
    }

    @Test
    fun differentAppsKeepIndependentRequirements() {
        config.setAppVpnCountry("com.example.one", "US")
        config.setAppVpnCountry("com.example.two", "GB")

        assertEquals("US", config.getAppVpnCountry("com.example.one"))
        assertEquals("GB", config.getAppVpnCountry("com.example.two"))
    }

    @Test
    fun manageAppsContainsNoCountryEditingControl() {
        val source = File("src/main/java/com/tunnelguard/app/AppsActivity.kt").readText()
        val itemLayout = File("src/main/res/layout/item_app_toggle.xml").readText()

        assertFalse(source.contains("showCountryDialog"))
        assertFalse(source.contains("setAppVpnCountry"))
        assertFalse(itemLayout.contains("tv_app_country"))
    }

    @Test
    fun countryScreenIsLabelledAndScrollableForTvUsers() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val manifestActivity = manifest.getElementsByTagName("activity").elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NAMESPACE, "name") == ".PerAppCountriesActivity" }
        assertNotNull("PerAppCountriesActivity must be declared", manifestActivity)
        assertEquals("@string/per_app_countries_title", manifestActivity!!.getAttributeNS(ANDROID_NAMESPACE, "label"))

        val screenLayout = parseXml(File("src/main/res/layout/activity_per_app_countries.xml"))
        val countryList = screenLayout.getElementsByTagName("androidx.recyclerview.widget.RecyclerView").elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NAMESPACE, "id") == "@+id/rv_per_app_countries" }
        assertNotNull("The per-app countries list must exist", countryList)
        assertEquals("vertical", countryList!!.getAttributeNS(ANDROID_NAMESPACE, "scrollbars"))
        assertEquals("false", countryList.getAttributeNS(ANDROID_NAMESPACE, "fadeScrollbars"))
        assertEquals(
            "@string/per_app_countries_list_accessibility",
            countryList.getAttributeNS(ANDROID_NAMESPACE, "contentDescription")
        )
    }

    @Test
    fun countryChangeUsesExistingVpnPolicyUpdateAction() {
        // Only onCreate is needed for this policy-dispatch test; avoid starting the async app list load.
        val activityController = Robolectric.buildActivity(PerAppCountriesActivity::class.java).create()
        val activity = activityController.get()
        val serviceController = Robolectric.buildService(TunnelGuardVpnService::class.java).create()
        try {
            activity.applyCountryRequirement("com.example.video", "CA")
            val serviceIntent = shadowOf(activity).nextStartedService

            assertEquals("CA", config.getAppVpnCountry("com.example.video"))
            assertNotNull("Country changes should notify the running VPN service", serviceIntent)
            assertEquals(TunnelGuardVpnService.ACTION_UPDATE, serviceIntent?.action)
            assertEquals(TunnelGuardVpnService::class.java.name, serviceIntent?.component?.className)
        } finally {
            serviceController.destroy()
            activityController.destroy()
        }
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { item(it) as Element }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
