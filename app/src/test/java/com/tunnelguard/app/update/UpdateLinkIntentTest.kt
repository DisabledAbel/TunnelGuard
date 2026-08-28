package com.tunnelguard.app.update

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateLinkIntentTest {
    @Test
    fun `creates chooser containing browsable release intent`() {
        val url = "https://github.com/DisabledAbel/TunnelGuard/releases/download/v2.0.0/TunnelGuard.apk"

        val chooser = UpdateLinkIntent.create(url)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Open update with", chooser.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(url, target.dataString)
        assertTrue(target.categories.contains(Intent.CATEGORY_BROWSABLE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-https update links`() {
        UpdateLinkIntent.create("file:///data/local/update.apk")
    }

    @Test
    fun `validates update links before fallback selection`() {
        val apkUrl = "https://github.com/releases/update.apk"
        val releaseUrl = "https://github.com/releases/latest"

        assertTrue(UpdateLinkIntent.isValid(apkUrl))
        assertFalse(UpdateLinkIntent.isValid("http://github.com/releases/update.apk"))
        assertFalse(UpdateLinkIntent.isValid("https:///releases/update.apk"))
        assertFalse(UpdateLinkIntent.isValid("not a URL"))
        assertEquals(apkUrl, UpdateLinkIntent.preferredUrl(apkUrl, releaseUrl))
        assertEquals(releaseUrl, UpdateLinkIntent.preferredUrl("http://invalid/update.apk", releaseUrl))
        assertEquals(apkUrl, UpdateLinkIntent.preferredUrl(apkUrl, "http://invalid/release"))
        assertNull(UpdateLinkIntent.preferredUrl("http://invalid/update.apk", "http://invalid/release"))
    }
}
