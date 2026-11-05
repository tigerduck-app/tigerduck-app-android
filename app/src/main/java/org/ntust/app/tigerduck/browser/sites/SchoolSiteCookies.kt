package org.ntust.app.tigerduck.browser.sites

import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Deletes only the opening host's cookies; a parent domain's SSO session stays. */
object SchoolSiteCookies {
    fun prepare(opening: String, sessionCleared: () -> Unit, ready: () -> Unit) {
        val url = opening.toHttpUrlOrNull()
        if (url == null || !SchoolSiteCookieBudget.appliesTo(opening) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            ready()
            return
        }
        val manager = CookieManager.getInstance()
        val cookies = CookieManagerCompat.getCookieInfo(manager, opening).mapNotNull { Cookie.parse(url, it) }
        val drop = SchoolSiteCookieBudget.cookiesToDrop(cookies, opening)
        if (drop.isEmpty()) {
            ready()
            return
        }
        var left = drop.size
        drop.forEach { cookie ->
            val domain = if (cookie.hostOnly) "" else "; Domain=${cookie.domain}"
            val flags = (if (cookie.secure) "; Secure" else "") + (if (cookie.httpOnly) "; HttpOnly" else "")
            manager.setCookie(opening, "${cookie.name}=; Max-Age=0; Path=${cookie.path}$domain$flags") {
                if (--left == 0) {
                    manager.flush()
                    sessionCleared()
                    ready()
                }
            }
        }
    }
}
