package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.webkit.CookieManager
import okhttp3.Cookie

/**
 * Bridges OkHttp's in-memory SSO cookies (see [org.ntust.app.tigerduck.network.NtustSessionManager])
 * into `android.webkit.CookieManager` so a [PortalWebView] opening [url] loads already signed in,
 * instead of showing NTUST's own login page inside the embedded browser.
 *
 * No separate "priming" login call is needed to get these cookies: fetching the portal's link
 * list (`NtustPortalService.fetchPortalLinks`) already performs the SSO login against the portal
 * host as a side effect, so by the time there are links to tap, [cookies] already holds a valid
 * session for that host — this just has to move them from one cookie store to the other.
 */
fun syncCookiesToWebView(url: String, cookies: List<Cookie>) {
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    cookies.forEach { cookie -> manager.setCookie(url, cookie.toString()) }
    manager.flush()
}
