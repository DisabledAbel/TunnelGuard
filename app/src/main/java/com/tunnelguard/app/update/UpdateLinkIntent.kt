package com.tunnelguard.app.update

import android.content.Intent
import android.net.Uri

/** Creates a chooser so Android TV users can select an installed downloader or browser. */
object UpdateLinkIntent {
    fun isValid(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    fun preferredUrl(apkUrl: String?, releaseUrl: String?): String? =
        apkUrl?.takeIf(::isValid) ?: releaseUrl

    fun create(url: String): Intent {
        require(isValid(url)) {
            "Update link must be a valid HTTPS URL"
        }
        val uri = Uri.parse(url)

        val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return Intent.createChooser(viewIntent, "Open update with")
    }
}
