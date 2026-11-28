package org.ntust.app.tigerduck.ui.screen.browser

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import org.ntust.app.tigerduck.browser.BrowserLinksProfile
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.browser.DownloadCookies

/**
 * What a browser may use of the app's, by how it was opened. One the app opens itself
 * ([InAppBrowser.open]: Information System, Moodle's pages, the profile pencil) loads in the app's own
 * WebView profile, which holds the student's school sessions, and signs in there. A link the app was
 * handed ([InAppBrowser.openLink]) loads in the links profile ([BrowserLinksProfile]), and a page opened
 * at risk in a profile of its own ([InAppBrowser.openAtRisk]), which takes the place of the links one
 * for a browser opened at risk from it. Neither is ever the app's own, and nothing of the app's goes in
 * either: no automatic sign-in, no fill of a school site's form, no cookie the app readies.
 */
internal class BrowserOpening(private val riskProfile: String?, val links: Boolean) {
    /** The named profile the browser loads in, or null for the app's own. */
    val profile: String? = riskProfile ?: BrowserLinksProfile.NAME.takeIf { links }

    /** Whether the app signs the browser in, fills the school sites' forms and readies their cookies: in its own profile alone. */
    val signsIn: Boolean get() = profile == null

    /** The sign-ins the browser runs: [app]'s in the app's own profile, none in any other. */
    fun signIns(app: BrowserSignIns): BrowserSignIns = if (signsIn) app else BrowserSignIns.NONE

    /** [web]'s cookies: its profile's ([ofProfile]) in a named one, the app's own only in the app's. */
    fun cookieManager(web: WebView, ofProfile: (WebView) -> CookieManager = PROFILE_COOKIES): CookieManager =
        if (profile != null) ofProfile(web) else CookieManager.getInstance()

    /**
     * The cookies a download from [web] carries and keeps: the links profile's own in the links
     * browser; null for the app's own in the app's, and at risk, where it carries none.
     */
    fun downloadCookies(web: WebView, ofProfile: (WebView) -> CookieManager = PROFILE_COOKIES): DownloadCookies? =
        if (links && riskProfile == null) ProfileCookies(cookieManager(web, ofProfile)) else null

    private class ProfileCookies(private val cookies: CookieManager) : DownloadCookies {
        override fun cookieFor(url: String): String? = cookies.getCookie(url)

        override fun keep(url: String, setCookie: String) = cookies.setCookie(url, setCookie)
    }

    companion object {
        private val PROFILE_COOKIES: (WebView) -> CookieManager = { WebViewCompat.getProfile(it).cookieManager }

        fun of(intent: Intent) = BrowserOpening(InAppBrowser.riskProfile(intent), InAppBrowser.opensLinks(intent))
    }
}
