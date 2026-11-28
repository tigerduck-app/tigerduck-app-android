package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.content.Context
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser

/**
 * The portal's links open with no allowlist of their own, with the browser's school SSO sign-in: the
 * school's pages and the link itself load, and another site's page asks first ([InAppBrowser.open]).
 */
internal fun openInformationSystemLink(context: Context, url: String, browserPreference: () -> String) {
    if (!InAppBrowser.open(context, url, allowlist = null)) openLink(context, url, browserPreference())
}
