package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected Android Instrumentation Test Suite for Android Emulator Verification.
 * Verifies core application activities, navigation, settings, diagnostics, and security state machine logic.
 */
@RunWith(AndroidJUnit4::class)
class FeatureVerificationTest {

    private lateinit var context: Context
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        config = TunnelGuardConfig(context)

        // Ensure clean preference state before each test
        context.getSharedPreferences("tunnel_guard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tunnel_guard_update_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        // Clean up preference state
        context.getSharedPreferences("tunnel_guard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testOnboardingActivityFlow() {
        // Set onboarding incomplete
        config.setOnboardingCompleted(false)
        assertFalse("Onboarding should initially be incomplete", config.isOnboardingCompleted())

        val scenario = ActivityScenario.launch(OnboardingActivity::class.java)

        // Verify onboarding title and Get Started button are visible
        onView(withId(R.id.tv_onboarding_title)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_get_started)).check(matches(isDisplayed()))

        // Click Get Started button
        onView(withId(R.id.btn_get_started)).perform(click())

        // Verify that completing onboarding updates the preference
        assertTrue("Onboarding should be completed after clicking Get Started", config.isOnboardingCompleted())

        scenario.close()
    }

    @Test
    fun testMainActivityUiAndElements() {
        config.setOnboardingCompleted(true)

        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Verify main title and core dashboard elements are displayed
        onView(withId(R.id.title_tunnel_guard)).check(matches(isDisplayed()))
        onView(withId(R.id.tv_protection_status)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_toggle_protection)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_toggle_emergency)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_settings)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_logs_dashboard)).check(matches(isDisplayed()))

        scenario.close()
    }

    @Test
    fun testSettingsActivityPreferencesToggle() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)

        // Verify Settings activity opens and shows title
        onView(withText("TunnelGuard Settings")).check(matches(isDisplayed()))

        // Verify preference setters & getters on config
        config.setAutoConnectVpnEnabled(true)
        assertTrue("Auto connect VPN should be enabled", config.isAutoConnectVpnEnabled())

        config.setAutoConnectVpnEnabled(false)
        assertFalse("Auto connect VPN should be disabled after setting to false", config.isAutoConnectVpnEnabled())

        config.setCountryVpnSettingEnabled(true)
        assertTrue("Country VPN setting should be enabled", config.isCountryVpnSettingEnabled())

        config.setForcedUpdatesEnabled(true)
        assertTrue("Forced updates should be enabled", config.isForcedUpdatesEnabled())

        scenario.close()
    }

    @Test
    fun testDiagnosticsActivityReportAndActions() {
        val scenario = ActivityScenario.launch(DiagnosticsActivity::class.java)

        // Verify Diagnostics header and cards are displayed
        onView(withId(R.id.tv_diagnostics_header)).check(matches(isDisplayed()))
        onView(withId(R.id.diag_vpn_state)).check(matches(isDisplayed()))
        onView(withId(R.id.diag_protection_state)).check(matches(isDisplayed()))
        onView(withId(R.id.diag_android_version)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_refresh_diag)).check(matches(isDisplayed()))

        // Perform refresh click
        onView(withId(R.id.btn_refresh_diag)).perform(click())

        scenario.close()
    }

    @Test
    fun testLogsDashboardActivityDisplay() {
        val scenario = ActivityScenario.launch(LogsDashboardActivity::class.java)

        // Verify Logs Dashboard header and action buttons exist
        onView(withText("TunnelGuard Logs Dashboard")).check(matches(isDisplayed()))
        onView(withText("Clear Logs")).check(matches(isDisplayed()))

        scenario.close()
    }

    @Test
    fun testTunnelGuardConfigAndSecurityStateMachineSystem() {
        // Test TunnelGuardConfig serialization and backup/restore on device
        config.setProtectionEnabled(true)
        config.setEmergencyLockEnabled(false)
        config.setAppProtected("com.example.testapp", true)

        val json = config.exportConfigToJson()
        assertNotNull("Exported config JSON should not be null", json)
        assertTrue("JSON should contain test package name", json!!.contains("com.example.testapp"))

        // Reset config and restore
        config.setProtectedApps(emptySet())
        assertFalse("Protected apps should be empty after clear", config.isAppProtected("com.example.testapp"))

        val importSuccess = config.importConfigFromJson(json)
        assertTrue("Config import should succeed", importSuccess)
        assertTrue("Protected apps should include restored package", config.isAppProtected("com.example.testapp"))

        // Test Security State Machine calculation on device
        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = false,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = null
        )
        assertEquals("Inactive security state should equal INACTIVE", SecurityState.INACTIVE, state)
    }
}
