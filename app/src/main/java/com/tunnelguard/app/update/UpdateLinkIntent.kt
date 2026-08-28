package com.tunnelguard.app.update

import android.content.Intent
import android.net.Uri

/** Creates a chooser so Android TV users can select an installed downloader or browser. */
object UpdateLinkIntent {
    fun create(url: String): Intent {
        val uri = Uri.parse(url)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Update link must be a valid HTTPS URL"
        }

        val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return Intent.createChooser(viewIntent, "Open update with")
    }
}
