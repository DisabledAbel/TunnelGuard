package com.tunnelguard.app

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionMonitorServiceLifecycleTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun reset() {
        TunnelGuardConfig(context).setProtectionEnabled(false)
        ProtectionMonitorService.observerRegistrationCount = 0
    }

    @Test
    fun monitorHasOneObserverAcrossRepeatedStartsAndHandoffs() {
        TunnelGuardConfig(context).setProtectionEnabled(true)
        val controller = Robolectric.buildService(ProtectionMonitorService::class.java).create()
        val service = controller.get()

        repeat(4) {
            service.onStartCommand(Intent(context, ProtectionMonitorService::class.java).setAction(ProtectionMonitorService.ACTION_START), 0, it)
        }

        assertTrue(ProtectionMonitorService.isMonitoringRunning)
        assertEquals(1, ProtectionMonitorService.observerRegistrationCount)
        controller.destroy()
        assertFalse(ProtectionMonitorService.isMonitoringRunning)
        assertEquals(0, ProtectionMonitorService.observerRegistrationCount)
    }

    @Test
    fun explicitStopWinsOverPendingRecoveryLifecycle() {
        TunnelGuardConfig(context).setProtectionEnabled(true)
        ShadowVpnService.setPrepareResult(null)
        val appShadow = shadowOf(context)
        while (appShadow.nextStartedService != null) { }
        val controller = Robolectric.buildService(ProtectionMonitorService::class.java).create()
        val service = controller.get()
        TunnelGuardConfig(context).setProtectionEnabled(false)

        val result = service.onStartCommand(
            Intent(context, ProtectionMonitorService::class.java).setAction(ProtectionMonitorService.ACTION_STOP),
            0,
            2
        )

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse(generateSequence { appShadow.nextStartedService }.any {
            it.action == TunnelGuardVpnService.ACTION_RECOVER
        })
        controller.destroy()
        assertFalse(ProtectionMonitorService.isMonitoringRunning)
        assertEquals(0, ProtectionMonitorService.observerRegistrationCount)
    }

    @Test
    fun vpnLossRecoversOnceWhenPermissionRemainsAvailable() {
        TunnelGuardConfig(context).setProtectionEnabled(true)
        ShadowVpnService.setPrepareResult(null)
        val appShadow = shadowOf(context)
        while (appShadow.nextStartedService != null) { }
        val controller = Robolectric.buildService(ProtectionMonitorService::class.java).create()

        repeat(3) {
            controller.get().onStartCommand(Intent().setAction(ProtectionMonitorService.ACTION_START), 0, it)
        }

        val starts = generateSequence { appShadow.nextStartedService }.toList()
            .filter { it.action == TunnelGuardVpnService.ACTION_RECOVER }
        assertEquals(1, starts.size)
        assertEquals(ServiceState.TUNNELGUARD_STARTING, TunnelGuardVpnService.currentServiceState)
        controller.destroy()
    }

    @Test
    fun vpnLossWithRevokedPermissionReportsFaultAndDoesNotRequestConsent() {
        TunnelGuardConfig(context).setProtectionEnabled(true)
        ShadowVpnService.setPrepareResult(Intent("android.net.VpnService.PREPARE"))
        val appShadow = shadowOf(context)
        while (appShadow.nextStartedService != null) { }
        val controller = Robolectric.buildService(ProtectionMonitorService::class.java).create()

        controller.get().onStartCommand(Intent().setAction(ProtectionMonitorService.ACTION_START), 0, 1)

        val starts = generateSequence { appShadow.nextStartedService }.toList()
        assertFalse(starts.any { it.action == TunnelGuardVpnService.ACTION_RECOVER })
        assertEquals(ServiceState.PERMISSION_REQUIRED, TunnelGuardVpnService.currentServiceState)
        assertEquals(VPNState.ERROR, TunnelGuardConfig(context).getVPNState())
        controller.destroy()
    }

    @Test
    fun revokedVpnCanBeDestroyedWithoutStoppingIndependentMonitoringRequest() {
        TunnelGuardConfig(context).setProtectionEnabled(true)
        val appShadow = shadowOf(context)
        while (appShadow.nextStartedService != null) { /* clear starts from other lifecycle tests */ }
        val controller = Robolectric.buildService(TunnelGuardVpnService::class.java).create()

        controller.get().onRevoke()
        controller.destroy()

        val starts = generateSequence { appShadow.nextStartedService }.toList()
        assertTrue(starts.any {
            it.component?.className == ProtectionMonitorService::class.java.name &&
                it.action == ProtectionMonitorService.ACTION_START
        })
        assertEquals("Local VPN control revoked", TunnelGuardConfig(context).getLastDisconnectReason())
        assertFalse(TunnelGuardVpnService.isTunnelEstablished)
    }
}
