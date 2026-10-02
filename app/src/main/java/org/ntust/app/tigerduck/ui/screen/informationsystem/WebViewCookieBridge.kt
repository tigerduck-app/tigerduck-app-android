package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import kotlin.coroutines.resume

private const val TAG = "WebViewCookieBridge"
private const val CLEAR_TIMEOUT_MS = 5_000L

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
 *
 * Empties the whole WebView cookie store first, so the WebView holds exactly the current jar.
 * The store is a disk-backed singleton that outlives every PortalWebView and that the OkHttp jar
 * never touches, and the portal WebView is the app's only authenticated WebView. Pruning only the
 * hosts the jar mentions left two things behind: a previous account's cookies for a service the
 * new account's jar hasn't visited — still on disk if logout's wipe failed to persist — and the
 * uniquely-named OIDC correlation cookies ssoam2's /connect/authorize flow piles up across
 * retries, which eventually grow the Cookie header past the server's limit ("400 Bad Request").
 * TAT's in-app browser clears the same way before setting its cookies.
 *
 * Returns false — having installed nothing — when the store could not be confirmed empty; the
 * caller must not open the page then. Must run on a thread with a Looper, which the removal
 * callback is posted to; openLink calls it from the main thread.
 */
suspend fun syncCookiesToWebView(cookiesByHost: Map<String, List<Cookie>>): Boolean {
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    // removeAllCookies is asynchronous; a cookie set before it finishes could be erased by it.
    // The callback only fails to arrive if the WebView provider is broken. That case fails
    // closed: installing anyway could hand a previous account's surviving cookie to the page,
    // and a clear that lands later would erase this session.
    val cleared = withTimeoutOrNull(CLEAR_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont -> manager.removeAllCookies { cont.resume(Unit) } }
    }
    if (cleared == null) {
        Log.w(TAG, "WebView cookie clear did not finish; not opening the portal page")
        return false
    }
    cookiesByHost.forEach { (host, cookies) ->
        val originUrl = "https://$host/"
        cookies.forEach { cookie -> manager.setCookie(originUrl, cookie.toString()) }
    }
    manager.flush()
    return true
}
