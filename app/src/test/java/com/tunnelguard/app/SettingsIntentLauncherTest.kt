package com.tunnelguard.app

import android.content.ActivityNotFoundException
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsIntentLauncherTest {
    @Test
    @Config(sdk = [21])
    fun `API 21 uses settings actions available on API 21`() {
        val installIntents = SettingsIntentLauncher.installPermissionIntents("com.example.app")
        val vpnIntents = SettingsIntentLauncher.vpnSettingsIntents()

        assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS), installIntents.map { it.action })
        assertNull(installIntents.first().data)
        assertEquals(listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS), vpnIntents.map { it.action })
    }

    @Test
    @Config(sdk = [23])
    fun `API 23 avoids newer install and VPN settings actions`() {
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, SettingsIntentLauncher.installPermissionIntents("com.example.app").first().action)
        assertEquals(Settings.ACTION_WIRELESS_SETTINGS, SettingsIntentLauncher.vpnSettingsIntents().first().action)
    }

    @Test
    @Config(sdk = [24])
    fun `API 24 uses VPN settings`() {
        assertEquals(Settings.ACTION_VPN_SETTINGS, SettingsIntentLauncher.vpnSettingsIntents().first().action)
    }

    @Test
    @Config(sdk = [26])
    fun `API 26 uses per-package unknown sources settings`() {
        val intent = SettingsIntentLauncher.installPermissionIntents("com.example.app").first()

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:com.example.app", intent.data.toString())
    }

    @Test
    fun `unavailable preferred action launches general settings fallback`() {
        val attempts = mutableListOf<String?>()

        val launched = SettingsIntentLauncher.launch(SettingsIntentLauncher.vpnSettingsIntents()) { intent ->
            attempts += intent.action
            if (attempts.size == 1) throw ActivityNotFoundException()
        }

        assertTrue(launched)
        assertEquals(Settings.ACTION_SETTINGS, attempts.last())
        assertEquals(2, attempts.size)
    }

    @Test
    fun `denied preferred action launches general settings fallback`() {
        val attempts = mutableListOf<String?>()

        val launched = SettingsIntentLauncher.launch(SettingsIntentLauncher.vpnSettingsIntents()) { intent ->
            attempts += intent.action
            if (attempts.size == 1) throw SecurityException()
        }

        assertTrue(launched)
        assertEquals(Settings.ACTION_SETTINGS, attempts.last())
    }

    @Test
    fun `no settings handler does not crash`() {
        var attempts = 0

        val launched = SettingsIntentLauncher.launch(SettingsIntentLauncher.installPermissionIntents("com.example.app")) {
            attempts++
            throw ActivityNotFoundException()
        }

        assertFalse(launched)
        assertEquals(2, attempts)
    }
}
