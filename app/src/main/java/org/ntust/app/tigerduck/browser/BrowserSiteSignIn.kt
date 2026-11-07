package org.ntust.app.tigerduck.browser

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.FAILED
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites
import org.ntust.app.tigerduck.browser.sites.SitePage
import java.security.cert.X509Certificate
import kotlin.coroutines.resume

/**
 * Fills a school site's own sign-in form with the stored school account, on each main-frame page
 * the browser finishes loading at an address [BrowserSchoolSites.signInForm] lists, as the iPhone
 * does: its empty student id and password fields alone. Its CAPTCHA and its submit stay the
 * student's; nothing is submitted.
 *
 * Nothing is filled for the demo account or with none signed in ([signedIn]), while the browser
 * cannot be used ([usable]), or for a page loaded under a browser session a sign-out or an account
 * change has since ended ([generation]).
 *
 * The [BrowserSiteSignInScripts.mark] goes at once: it fills the student id, and marks the page
 * with a random token of its own, which it answers with the page's address. The WebView is not
 * pinned, so the password goes only to the page so marked, and only when its leaf certificate,
 * read as the mark answers, is the one a pinned, credential-less request to the same page met
 * ([pinnedLeaf], [PinnedPageCertificate]): that request's connection met the app's pins and
 * OkHttp's check of the host name. A mark with no token, made at another address than the WebView
 * shows, a failed request, one that took longer than [checkTimeoutMs], no certificate on the page
 * or another leaf leave the student id alone. Before the [BrowserSiteSignInScripts.fill], the
 * WebView must still show that page with that leaf, in the same browser session, still usable and
 * signed in; the fill itself runs only in the document that holds the token, so a page that
 * replaced the marked one at the same address gets nothing. A leaf met is kept for its host while
 * the browser session lasts, so a later page there needs no request, though it is marked and
 * checked all the same; another leaf on the page is checked again, and forgotten when it fails.
 *
 * Neither the scripts, their values nor the token are logged.
 */
class BrowserSiteSignIn(
    /** Whether a school account other than the demo is signed in. */
    private val signedIn: () -> Boolean,
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val usable: () -> Boolean,
    private val generation: () -> Int,
    /** The leaf a pinned request to the page at a URL met, or null when it failed. */
    private val pinnedLeaf: suspend (url: String) -> X509Certificate?,
    private val checkTimeoutMs: Long = CHECK_TIMEOUT_MS,
) {
    /** By host, the page leaf a pinned request met, and the browser session it was met in. */
    private val met = mutableMapOf<String, Pair<Int, ByteArray>>()

    /** Fills the form of [url], the page [web] finished loading in the browser session [session], in [scope]. */
    fun pageFinished(web: WebView, url: String, session: Int?, scope: CoroutineScope) {
        scope.launch { fill(url, session, WebViewPage(web)) }
    }

    /** Fills the form of [url], in the browser session [session], in what [page] shows now. */
    suspend fun fill(url: String, session: Int?, page: ShownPage) {
        val form = BrowserSchoolSites.signInForm(url) ?: return
        val site = BrowserSchoolSites.page(url) ?: return
        if (!isCurrent(session)) return
        val id = studentId()?.takeIf { it.isNotBlank() } ?: return
        val answer = page.run(BrowserSiteSignInScripts.mark(site, form, id))
        if (answer == FAILED) Log.w(TAG, "a school site's sign-in form was not marked")
        if (form.password == null) return
        val marked = BrowserSiteSignInScripts.marked(answer) ?: return
        val token = marked.token ?: return
        if (marked.href != page.url || BrowserSchoolSites.page(marked.href) != site) return
        val leaf = page.certificate?.bytes() ?: return
        if (!wasMet(site.host, leaf, session) && !check(site, leaf, session)) return
        val stillShown = page.url?.let(BrowserSchoolSites::page) == site && page.certificate?.bytes()?.contentEquals(leaf) == true
        if (!stillShown || !isCurrent(session)) return
        val secret = password()?.takeIf { it.isNotEmpty() } ?: return
        if (page.run(BrowserSiteSignInScripts.fill(site, form, id, secret, token)) == FAILED) {
            Log.w(TAG, "a school site's sign-in form was not filled")
        }
    }

    private fun isCurrent(session: Int?): Boolean = session != null && usable() && generation() == session && signedIn()

    private fun wasMet(host: String, leaf: ByteArray, session: Int?): Boolean = synchronized(met) {
        met[host]?.let { (inSession, bytes) -> inSession == session && bytes.contentEquals(leaf) } == true
    }

    /** Whether a pinned request to [site] meets [leaf]; kept for its host when it does, forgotten when not. */
    private suspend fun check(site: SitePage, leaf: ByteArray, session: Int?): Boolean {
        val served = try {
            withTimeoutOrNull(checkTimeoutMs) { pinnedLeaf(site.origin + site.path) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val matches = served?.bytes()?.contentEquals(leaf) == true
        synchronized(met) { if (matches && session != null) met[site.host] = session to leaf else met.remove(site.host) }
        return matches
    }

    private fun X509Certificate.bytes(): ByteArray? = runCatching { encoded }.getOrNull()

    companion object {
        private const val TAG = "BrowserSiteSignIn"

        /** As long as the pinned request's own timeouts. */
        const val CHECK_TIMEOUT_MS = PinnedPageCertificate.TIMEOUT_SECONDS * 1_000
    }
}

/** A page as the WebView shows it now: its address, the leaf certificate it came with, and a script run in it. */
interface ShownPage {
    val url: String?
    val certificate: X509Certificate?

    /** Runs [script] in the page, answering its result as JSON, or null when none came. */
    suspend fun run(script: String): String?
}

/** [web]'s page, read on the main thread as it is at each ask. */
private class WebViewPage(private val web: WebView) : ShownPage {
    override val url: String? get() = web.url
    override val certificate: X509Certificate? get() = web.certificate?.x509Certificate

    override suspend fun run(script: String): String? = suspendCancellableCoroutine { answer ->
        web.evaluateJavascript(script) { result -> if (answer.isActive) answer.resume(result) }
    }
}
