package org.ntust.app.tigerduck.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import org.ntust.app.tigerduck.R

/** The values Settings > "Open links with" stores in [AppState.browserPreference]. */
object BrowserPreference {
    const val SYSTEM = "system"
    const val IN_APP = "inApp"
}

/**
 * Opens [url] the way "Open links with" says: a Custom Tab for
 * [BrowserPreference.IN_APP], the default browser otherwise. A `mailto:` link
 * becomes a new message in the mail app, as a mail link does in the message
 * screen. The one opener every link in the app goes through.
 *
 * Any other scheme is refused, whoever wrote the link: `file:`, `content:`,
 * `intent:` or `javascript:` from a bulletin or a web page would hand another
 * app something no web link should.
 *
 * Returns false rather than crashing when the link cannot be opened: some work
 * profiles, kiosk and de-Googled ROMs have no browser at all, and the platform
 * can refuse a launch outright. The user is told so with a short toast, where
 * the tap used to do nothing at all (tigerduck-app-android#249).
 */
fun openLink(context: Context, url: String, browserPreference: String): Boolean =
    launchLink(context, url, browserPreference).also { opened -> if (!opened) sayNoAppOpens(context) }

private fun launchLink(context: Context, url: String, browserPreference: String): Boolean {
    // Android matches intent schemes case-sensitively, so "HTTPS:" would find no browser.
    val uri = url.toUri().normalizeScheme()
    // Outside an Activity there is no task to put the browser in.
    fun Intent.inATask() = apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return try {
        when (uri.scheme) {
            "http", "https" -> if (browserPreference == BrowserPreference.IN_APP) {
                CustomTabsIntent.Builder().build().apply { intent.inATask() }.launchUrl(context, uri)
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).inATask())
            }
            "mailto" -> context.startActivity(Intent(Intent.ACTION_SENDTO, uri).inATask())
            else -> return false
        }
        true
    } catch (_: RuntimeException) {
        // No app for it (ActivityNotFoundException), one not exported to us
        // (SecurityException), or a launch the platform refuses: each as
        // unopenable as the others, and none worth a crash.
        false
    }
}

/** The toast for a link no app can open; posted to the main thread from any other. */
private fun sayNoAppOpens(context: Context) {
    val show = { Toast.makeText(context, R.string.error_link_no_app, Toast.LENGTH_SHORT).show() }
    if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
}
