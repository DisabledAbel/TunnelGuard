package com.tunnelguard.app

import android.os.Build
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LogsDashboardActivityTest {

    private lateinit var context: android.content.Context
    private lateinit var config: TunnelGuardConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = TunnelGuardConfig(context)
        config.clearLogs()
    }

    @Test
    fun testActivityLaunchesAndLoadsAppLogs() {
        // Add some mock app logs
        config.addLog("This is a mock application debug log.")
        config.addLog("Another log line here.")

        val controller = Robolectric.buildActivity(LogsDashboardActivity::class.java)
        val activity = controller.create().start().resume().get()

        val tvActiveTabTitle = activity.findViewById<TextView>(R.id.tv_active_tab_title)
        val rvLogsList = activity.findViewById<RecyclerView>(R.id.rv_logs_list)

        assertNotNull(tvActiveTabTitle)
        assertNotNull(rvLogsList)
        assertEquals("App Debug Logs", tvActiveTabTitle.text.toString())

        val adapter = rvLogsList.adapter
        assertNotNull(adapter)

        // Wait up to 2 seconds for background thread/coroutine to update list
        var attempts = 0
        while (adapter!!.itemCount < 2 && attempts < 20) {
            Thread.sleep(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            attempts++
        }

        // There should be at least the 2 logs we added
        assertTrue("Adapter should have at least 2 logs, but had ${adapter.itemCount}", adapter.itemCount >= 2)
    }

    @Test
    fun testVpnLogFiltering() {
        // Add logs, some containing vpn, some not
        config.addLog("Starting TunnelGuardVpnService")
        config.addLog("Establishing fail-closed block interface")
        config.addLog("User navigated to main screen")
        config.addLog("Another VPN status: CONNECTED")

        val controller = Robolectric.buildActivity(LogsDashboardActivity::class.java)
        val activity = controller.create().start().resume().get()

        val btnTabVpnLogs = activity.findViewById<Button>(R.id.btn_tab_vpn_logs)
        assertNotNull(btnTabVpnLogs)

        // Switch to VPN logs tab
        btnTabVpnLogs.performClick()

        val tvActiveTabTitle = activity.findViewById<TextView>(R.id.tv_active_tab_title)
        assertEquals("VPN Logs", tvActiveTabTitle.text.toString())

        val rvLogsList = activity.findViewById<RecyclerView>(R.id.rv_logs_list)
        val adapter = rvLogsList.adapter
        assertNotNull(adapter)

        // Wait up to 2 seconds for background thread/coroutine to update list
        var attempts = 0
        while (adapter!!.itemCount == 0 && attempts < 20) {
            Thread.sleep(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            attempts++
        }

        // It should contain at least our VPN logs
        assertTrue("Adapter should have at least 1 log, but had ${adapter.itemCount}", adapter.itemCount >= 1)
    }

    @Test
    fun testClearAppLogs() {
        config.addLog("A temporary log entry.")

        val controller = Robolectric.buildActivity(LogsDashboardActivity::class.java)
        val activity = controller.create().start().resume().get()

        val btnClearAppLogs = activity.findViewById<Button>(R.id.btn_clear_app_logs)
        assertNotNull(btnClearAppLogs)

        btnClearAppLogs.performClick()

        // Wait up to 2 seconds for background thread/coroutine to update list
        var attempts = 0
        while (config.getLogs().size != 1 && attempts < 20) {
            Thread.sleep(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            attempts++
        }

        // The app logs should now be cleared
        // Since we add "App debug logs cleared by user." when clearing, the logs size should be exactly 1.
        assertEquals(1, config.getLogs().size)
        assertTrue(config.getLogs()[0].contains("App debug logs cleared by user."))
    }
}
