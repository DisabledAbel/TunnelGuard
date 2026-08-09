package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class VpnLifecycleTest {

    private lateinit var context: Context
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = TunnelGuardConfig(context)
        // Reset config SharedPreferences
        val prefs = context.getSharedPreferences("tunnel_guard_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit().putBoolean("onboarding_completed", true).commit()
        // Mock VPN permission as already prepared/granted for boot receiver tests
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
    }

    @Test
    fun testBootReceiverStartsServiceWhenEnabled() {
        config.setStartOnBootEnabled(true)
        config.setProtectionEnabled(true)

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        val shadowApp = shadowOf(context as android.app.Application)
        while (shadowApp.nextStartedService != null) { } // clear queue

        receiver.onReceive(context, intent)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNotNull("Service should start on boot completed", startedServiceIntent)
        assertEquals(TunnelGuardVpnService.ACTION_START, startedServiceIntent?.action)
    }

    @Test
    fun testBootReceiverDoesNotStartServiceWhenDisabled() {
        config.setStartOnBootEnabled(false)
        config.setProtectionEnabled(true)

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        val shadowApp = shadowOf(context as android.app.Application)
        while (shadowApp.nextStartedService != null) { } // clear queue

        receiver.onReceive(context, intent)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNull("Service should NOT start when boot receiver is disabled in config", startedServiceIntent)
    }

    @Test
    fun testEmergencyLockEnforcesBlockingState() {
        config.setEmergencyLockEnabled(false)
        assertFalse(config.isEmergencyLockEnabled())
        assertEquals(ProtectionState.INACTIVE, config.getProtectionState())

        config.setEmergencyLockEnabled(true)
        assertTrue(config.isEmergencyLockEnabled())
        assertEquals(ProtectionState.BLOCKING, config.getProtectionState())
    }

    @Test
    fun testSettingsPersistence() {
        config.setStartOnBootEnabled(true)
        assertTrue(config.isStartOnBootEnabled())

        config.setSimulatedVpnEnabled(true)
        assertTrue(config.isSimulatedVpnEnabled())

        config.setForcedUpdatesEnabled(false)
        assertFalse(config.isForcedUpdatesEnabled())
    }
}
