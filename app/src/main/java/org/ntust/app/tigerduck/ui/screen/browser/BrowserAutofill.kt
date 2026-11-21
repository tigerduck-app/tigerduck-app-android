package org.ntust.app.tigerduck.ui.screen.browser

import android.view.View

/**
 * Keeps the phone's autofill, a password manager's among it, out of [web] and every field in it in a
 * browser opened at risk: its pages came over a certificate the browser refused and may be an
 * impostor's, which must not be offered saved credentials, as the app's own fill is not. This goes
 * beyond the iPhone's. Any other browser's WebView is left as the platform makes it.
 */
internal fun keepAutofillOutAtRisk(web: View, atRisk: Boolean) {
    if (atRisk) web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
}
