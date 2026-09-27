package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.webkit.CookieManager
import okhttp3.Cookie

/**
 * Bridges every cookie OkHttp's SSO session currently holds (see
 * [org.ntust.app.tigerduck.network.NtustSessionManager.allCookiesByHost]) into
 * `android.webkit.CookieManager`, so a [PortalWebView] loads already signed in instead of
 * showing NTUST's own login page inside the embedded browser.
 *
 * Bridges by each cookie's *own* originating host, not the link the user is about to open:
 * NTUST's SSO is per-service, so a page can redirect through or pull sub-resources from a host
 * other than the one the user tapped, and copying the whole jar — scoped exactly as OkHttp saw
 * it — covers that without having to track which hosts a given navigation touches.
 * `CookieManager.setCookie` still keys each cookie to the host it's set against, so a cookie for
 * a host the WebView never visits is simply inert, not a broadened attack surface.
 */
fun syncCookiesToWebView(cookiesByHost: Map<String, List<Cookie>>) {
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    cookiesByHost.forEach { (host, cookies) ->
        val originUrl = "https://$host/"
        cookies.forEach { cookie -> manager.setCookie(originUrl, cookie.toString()) }
    }
    manager.flush()
}
