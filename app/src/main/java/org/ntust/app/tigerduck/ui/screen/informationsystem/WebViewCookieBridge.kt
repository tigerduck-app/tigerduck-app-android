package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.webkit.CookieManager
import okhttp3.Cookie
import org.ntust.app.tigerduck.network.HtmlParser

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
    // Prune whatever this host already has in the WebView's own CookieManager first — a
    // disk-backed singleton that outlives every PortalWebView instance and that OkHttp's
    // session-scoped jar never touches. NTUST's stuinfosys OIDC challenge (ssoam2's
    // /connect/authorize flow) sets a uniquely-named correlation cookie on every attempt and
    // never clears the previous one; left to accumulate across retries this eventually grows
    // the Cookie header past the server's limit and every request comes back plain
    // "400 Bad Request" — a header-size failure, not a login failure. ssoam/ssoam2 are pruned
    // unconditionally (not just when cookiesByHost happens to mention them) because that is
    // exactly where this pile-up happens, whether or not OkHttp's own jar currently holds
    // anything for either host.
    (cookiesByHost.keys + HtmlParser.ssoHosts).forEach { host ->
        val originUrl = "https://$host/"
        manager.getCookie(originUrl)?.split(";")?.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) manager.setCookie(originUrl, "$name=; Max-Age=0")
        }
    }
    cookiesByHost.forEach { (host, cookies) ->
        val originUrl = "https://$host/"
        cookies.forEach { cookie -> manager.setCookie(originUrl, cookie.toString()) }
    }
    manager.flush()
}
