package com.tunnelguard.app

import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.json.JSONObject
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

    private fun isViewVisible(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("view.visibility == View.VISIBLE")
            }

            override fun matchesSafely(view: View): Boolean {
                return view.visibility == View.VISIBLE
            }
        }
    }

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

        // Verify onboarding title and Get Started button are visible using view IDs
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
        config.setForcedUpdatesEnabled(false)

        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Verify main title and core dashboard elements are present and VISIBLE
        onView(withId(R.id.title_tunnel_guard)).check(matches(isDisplayed()))
        onView(withId(R.id.tv_protection_status)).check(matches(isViewVisible()))
        onView(withId(R.id.btn_toggle_protection)).check(matches(isViewVisible()))
        onView(withId(R.id.btn_toggle_emergency)).check(matches(isViewVisible()))
        onView(withId(R.id.btn_logs_dashboard)).check(matches(isViewVisible()))
        onView(withId(R.id.btn_settings)).check(matches(isViewVisible()))

        scenario.close()
    }

    @Test
    fun testSettingsActivityPreferencesToggle() {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)

        // Verify Settings activity header is displayed using view ID
        onView(withId(R.id.tv_settings_header)).check(matches(isDisplayed()))

        val initialAutoConnect = config.isAutoConnectVpnEnabled()

        // Exercise real UI switch row in SettingsActivity with scrollTo() for ScrollView compatibility
        onView(withId(R.id.layout_pref_auto_connect)).perform(scrollTo(), click())
        assertEquals("Auto connect VPN preference should toggle after UI row click", !initialAutoConnect, config.isAutoConnectVpnEnabled())

        // Toggle back via UI interaction
        onView(withId(R.id.layout_pref_auto_connect)).perform(scrollTo(), click())
        assertEquals("Auto connect VPN preference should return to initial state after second click", initialAutoConnect, config.isAutoConnectVpnEnabled())

        scenario.close()
    }

    @Test
    fun testDiagnosticsActivityReportAndActions() {
        val scenario = ActivityScenario.launch(DiagnosticsActivity::class.java)

        // Verify Diagnostics header and cards are displayed using view IDs
        onView(withId(R.id.tv_diagnostics_header)).check(matches(isDisplayed()))
        onView(withId(R.id.diag_vpn_state)).check(matches(isViewVisible()))
        onView(withId(R.id.diag_protection_state)).check(matches(isViewVisible()))
        onView(withId(R.id.diag_android_version)).check(matches(isViewVisible()))

        // Scroll to refresh button and click
        onView(withId(R.id.btn_refresh_diag)).perform(scrollTo(), click())

        scenario.close()
    }

    @Test
    fun testLogsDashboardActivityDisplay() {
        val scenario = ActivityScenario.launch(LogsDashboardActivity::class.java)

        // Verify Logs Dashboard header and action buttons exist using view IDs
        onView(withId(R.id.tv_logs_header)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_clear_app_logs)).check(matches(isViewVisible()))

        scenario.close()
    }

    @Test
    fun testTunnelGuardConfigAndSecurityStateMachineSystem() {
        // Test TunnelGuardConfig serialization and backup/restore on device
        config.setProtectionEnabled(true)
        config.setEmergencyLockEnabled(false)
        config.setAppProtected("com.example.testapp", true)

        val jsonStr = config.exportConfigToJson()
        assertNotNull("Exported config JSON should not be null", jsonStr)

        // Parse exported JSON structure and inspect streaming profile's app packages
        val exportedJson = JSONObject(jsonStr!!)
        val profilesArr = exportedJson.getJSONArray("protection_profiles")
        var foundProtectedAppInProfile = false
        for (i in 0 until profilesArr.length()) {
            val profileObj = profilesArr.getJSONObject(i)
            if (profileObj.getString("id") == config.getSelectedProfileId()) {
                val appsArr = profileObj.getJSONArray("apps")
                for (j in 0 until appsArr.length()) {
                    if (appsArr.getString(j) == "com.example.testapp") {
                        foundProtectedAppInProfile = true
                    }
                }
            }
        }
        assertTrue("Exported JSON profile apps array should contain 'com.example.testapp'", foundProtectedAppInProfile)

        // Reset config and restore
        config.setProtectedApps(emptySet())
        assertFalse("Protected apps should be empty after clear", config.isAppProtected("com.example.testapp"))

        val importSuccess = config.importConfigFromJson(jsonStr)
        assertTrue("Config import should succeed", importSuccess)
        assertTrue("Protected apps should include restored package", config.isAppProtected("com.example.testapp"))

        // Explicitly set protection disabled to verify INACTIVE security state computation precondition
        config.setProtectionEnabled(false)
        val state = SecurityStateMachine.getSecurityState(
            context = context,
            config = config,
            isServiceRunning = false,
            isServiceStarting = false,
            isTunnelEstablished = false,
            connectivityManager = null
        )
        assertEquals("Inactive security state should equal INACTIVE when protection is disabled", SecurityState.INACTIVE, state)
    }
}
