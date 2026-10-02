package org.ntust.app.tigerduck.ui.util

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Opens a URL the way the rest of the app does: Custom Tabs for the "inApp"
 * browser preference, the system browser otherwise. Shared by every feature
 * that hands a URL off to the OS rather than rendering it in an embedded
 * `WebView` — [org.ntust.app.tigerduck.ui.screen.mail.openMailLink] delegates
 * here so mail and the information-system browser can't drift apart.
 */
fun openExternalLink(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    runCatching {
        if (browserPreference == "inApp") {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
