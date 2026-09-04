package com.tunnelguard.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PerAppCountriesRegressionTest {
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun countryChangeUsesExistingVpnPolicyUpdateAction() {
        val activity = Robolectric.buildActivity(PerAppCountriesActivity::class.java).setup().get()
        TunnelGuardVpnService.isServiceRunning = true
        try {
            activity.applyCountryRequirement("com.example.video", "CA")
            val serviceIntent = shadowOf(activity).nextStartedService

            assertEquals("CA", config.getAppVpnCountry("com.example.video"))
            assertEquals(TunnelGuardVpnService.ACTION_UPDATE, serviceIntent.action)
        } finally {
            TunnelGuardVpnService.isServiceRunning = false
        }
    }
}
