package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.content.Context
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser

/** The portal's links open unrestricted, with the browser's school SSO sign-in. */
internal fun openInformationSystemLink(context: Context, url: String, browserPreference: () -> String) {
    if (!InAppBrowser.open(context, url, allowlist = null)) openLink(context, url, browserPreference())
}
