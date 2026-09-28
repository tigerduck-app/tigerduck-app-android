package org.ntust.app.tigerduck.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.ntust.app.tigerduck.data.cache.PortalLinksCache
import org.ntust.app.tigerduck.data.cache.PortalLinksSnapshot
import org.ntust.app.tigerduck.network.model.PortalLink
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

sealed class NtustPortalError : Exception() {
    class NotAuthenticated : NtustPortalError()
    class RedirectedToSSO : NtustPortalError()
    class InvalidResponse : NtustPortalError()
    class ParseFailed : NtustPortalError()
}

/**
 * Fetches NTUST's own student-portal page ("資訊系統" — Curriculum / Person
 * Info / Campus Life / Financial Support / Activities / Resources), parses it
 * into [PortalLink]s, and caches the result for up to 24h. There is no static
 * link list to ship: NTUST's own portal HTML is the only source of truth, so
 * the links are scraped from it live.
 */
@Singleton
class NtustPortalService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val cache: PortalLinksCache,
) {
    suspend fun fetchPortalLinks(
        studentId: String,
        password: String,
        useEnglish: Boolean,
        forceRefresh: Boolean = false,
    ): List<PortalLink> = withContext(Dispatchers.IO) {
        val lang = if (useEnglish) "en" else "zh"

        if (!forceRefresh) {
            val cached = cache.load(lang)
            val cachedLinks = cached?.links
            val cachedAt = cached?.fetchedAt
            if (cachedLinks != null && cachedAt != null &&
                System.currentTimeMillis() - cachedAt.time < CACHE_TTL_MS
            ) return@withContext cachedLinks
        }

        val url = if (useEnglish) PORTAL_URL_EN else PORTAL_URL_ZH

        if (!sessionManager.cookiesValid) {
            val loggedIn = ssoLoginService.ensureServiceLogin(url, studentId, password)
            if (!loggedIn) throw NtustPortalError.NotAuthenticated()
        }

        val html = fetchHtml(url, studentId, password)
        val links = NtustPortalTreeParser.parse(html, url)

        // A 200 that parses to nothing usually means the page was actually
        // the SSO wall in disguise — don't cache an empty portal.
        if (links.isEmpty()) throw NtustPortalError.ParseFailed()

        cache.save(PortalLinksSnapshot(links, Date()), lang)
        links
    }

    /** Every cookie the OkHttp session currently holds, for bridging into the
     *  WebView's own cookie store right before opening a link — see
     *  [NtustSessionManager.allCookiesByHost]. */
    fun allSessionCookies() = sessionManager.allCookiesByHost()

    /**
     * Ensures the OkHttp session holds a valid cookie for [targetUrl]'s own
     * host, right before opening it in a WebView.
     *
     * Deliberately does NOT gate on [NtustSessionManager.cookiesValid]: that
     * flag is one process-wide "is *some* NTUST session warm" bit, set true
     * the moment ANY service login succeeds. NTUST's SSO is per-service —
     * each host (courseselection, stuinfosys, i.ntust.edu.tw, ...) exchanges
     * its own ticket against the shared CAS session — so a warm session
     * elsewhere says nothing about whether *this* link's host has ever been
     * visited. Skipping the call on a "valid" but wrong-host session was
     * exactly the bug: the portal list itself would load (it primes its own
     * host), but a tapped link to any other NTUST host opened signed out.
     *
     * Calling [SsoLoginService.ensureServiceLogin] unconditionally is cheap
     * when a host is already authenticated — it is one GET that doesn't land
     * on the SSO page — and is what actually establishes that host's session
     * cookie via the live CAS ticket exchange when it is not.
     */
    suspend fun ensureWebViewSession(targetUrl: String, studentId: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ssoLoginService.ensureServiceLogin(targetUrl, studentId, password)
            } catch (_: SsoLoginError.InteractiveLoginRequired) {
                // The WebView lands on that same NetIQ page and PortalWebView's auto-fill
                // finishes the login with a real click, so opening it is still correct here.
                true
            }
        }

    private suspend fun fetchHtml(url: String, studentId: String, password: String): String {
        val (html, finalHost) = get(url)
        if (!HtmlParser.isSsoHost(finalHost)) return html

        // SSO bounced us — try one silent re-login and retry once.
        val loggedIn = ssoLoginService.ensureServiceLogin(url, studentId, password)
        if (!loggedIn) throw NtustPortalError.NotAuthenticated()

        val (retryHtml, retryHost) = get(url)
        if (HtmlParser.isSsoHost(retryHost)) throw NtustPortalError.RedirectedToSSO()
        return retryHtml
    }

    private fun get(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).get().build()
        sessionManager.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw NtustPortalError.InvalidResponse()
            val html = response.body.string()
            return html to response.request.url.host
        }
    }

    companion object {
        const val PORTAL_HOST = "i.ntust.edu.tw"
        const val PORTAL_URL_ZH = "https://$PORTAL_HOST/student"
        const val PORTAL_URL_EN = "https://$PORTAL_HOST/EN/student"

        /** 24h — the portal's own service list is near-static; a stale-hit-first
         *  cache matches every other network-backed list in the app. */
        private const val CACHE_TTL_MS: Long = 24 * 60 * 60 * 1000
    }
}
