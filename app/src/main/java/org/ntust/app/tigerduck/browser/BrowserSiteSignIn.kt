package org.ntust.app.tigerduck.browser

import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites
import org.ntust.app.tigerduck.browser.sites.SiteSignInForm
import org.ntust.app.tigerduck.browser.sites.SitePage
import java.security.cert.X509Certificate

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
 * The student id goes at once. The WebView is not pinned, so the password goes only to a page
 * whose own leaf certificate is the one a pinned, credential-less request to the same page met
 * ([pinnedLeaf], [PinnedPageCertificate]): that request's connection met the app's pins and
 * OkHttp's check of the host name. A failed request, one that took longer than [checkTimeoutMs],
 * no certificate on the page or another leaf leave the student id alone. Once back on the main
 * thread, the password goes only if the WebView still shows that page with that leaf, in the
 * same browser session, still usable and signed in. A leaf met is kept for its host while the
 * browser session lasts, so a later page there needs no request; another leaf on the page is
 * checked again, and forgotten when it fails.
 *
 * evaluateJavascript takes no arguments: the values go into the script as JSON string literals,
 * which no quote, backslash or line separator in them can end. The script runs in the page's own
 * world and checks the page's origin and path again as it fills, since the page may have moved
 * on. Neither the script nor its values are logged.
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
        scope.launch {
            fill(url, session, shownUrl = { web.url }, shownCertificate = { web.certificate?.x509Certificate }) { script ->
                web.evaluateJavascript(script) { result ->
                    if (result == FAILED) Log.w(TAG, "a school site's sign-in form was not filled")
                }
            }
        }
    }

    /**
     * Fills the form of [url] in the browser session [session], through [run], which runs a script
     * in the page that [shownUrl] and [shownCertificate] tell of as it is now.
     */
    suspend fun fill(
        url: String,
        session: Int?,
        shownUrl: () -> String?,
        shownCertificate: () -> X509Certificate?,
        run: (String) -> Unit,
    ) {
        val form = BrowserSchoolSites.signInForm(url) ?: return
        val page = BrowserSchoolSites.page(url) ?: return
        if (!isCurrent(session)) return
        val id = studentId()?.takeIf { it.isNotBlank() } ?: return
        val leaf = form.password?.let { shownCertificate()?.bytes() }
        if (leaf != null && wasMet(page.host, leaf, session)) return run(script(page, form, id, storedPassword()))
        run(script(page, form, id, secret = null))
        if (leaf == null || !check(page, leaf, session)) return
        val stillShown = shownUrl()?.let(BrowserSchoolSites::page) == page && shownCertificate()?.bytes()?.contentEquals(leaf) == true
        if (!stillShown || !isCurrent(session)) return
        storedPassword()?.let { run(script(page, form, id, it)) }
    }

    private fun isCurrent(session: Int?): Boolean = session != null && usable() && generation() == session && signedIn()

    private fun storedPassword(): String? = password()?.takeIf { it.isNotEmpty() }

    private fun wasMet(host: String, leaf: ByteArray, session: Int?): Boolean = synchronized(met) {
        met[host]?.let { (inSession, bytes) -> inSession == session && bytes.contentEquals(leaf) } == true
    }

    /** Whether a pinned request to [page] meets [leaf]; kept for its host when it does, forgotten when not. */
    private suspend fun check(page: SitePage, leaf: ByteArray, session: Int?): Boolean {
        val served = try {
            withTimeoutOrNull(checkTimeoutMs) { pinnedLeaf(page.origin + page.path) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val matches = served?.bytes()?.contentEquals(leaf) == true
        synchronized(met) { if (matches && session != null) met[page.host] = session to leaf else met.remove(page.host) }
        return matches
    }

    private fun X509Certificate.bytes(): ByteArray? = runCatching { encoded }.getOrNull()

    private fun script(page: SitePage, form: SiteSignInForm, id: String, secret: String?): String = """(function() {
  const origin = ${literal(page.origin)}, path = ${literal(page.path)};
  const studentIdField = ${literal(form.studentId)}, studentId = ${literal(id)};
  const passwordField = ${literal(form.password)}, password = ${literal(secret)};
  try {
$FILL
  } catch (error) {
    return 'failed';
  }
})();"""

    companion object {
        private const val TAG = "BrowserSiteSignIn"

        /** As long as the pinned request's own timeouts. */
        const val CHECK_TIMEOUT_MS = PinnedPageCertificate.TIMEOUT_SECONDS * 1_000

        /** What evaluateJavascript answers for a script that threw. */
        const val FAILED = "\"failed\""

        private val json = Gson()

        /**
         * [value] as a JSON string literal in ASCII alone, or `null`: Gson escapes quotes,
         * backslashes, control characters, U+2028 and U+2029, and `<`, `>`, `&`, `=` and `'` too;
         * every other character past ASCII becomes a `\u` escape here.
         */
        fun literal(value: String?): String = if (value == null) "null" else buildString {
            json.toJson(value).forEach { if (it.code in 0x20..0x7e) append(it) else append("\\u%04x".format(it.code)) }
        }

        /**
         * Fills only empty fields, as the student may have typed, and leaves every other field
         * alone: nothing unless the fields share one form that posts to the page's own origin, and
         * the password only beside the stored student id, never beside one the student typed.
         */
        private const val FILL = """    if (location.origin !== origin || location.pathname !== path) return false;
    const field = (name) => Array.prototype.find.call(
      document.getElementsByName(name), (element) => element instanceof HTMLInputElement && element.form !== null);
    const id = field(studentIdField), secret = passwordField === null ? null : field(passwordField);
    if (!id || (passwordField !== null && (!secret || secret.type !== 'password' || secret.form !== id.form))) return false;
    if (new URL(id.form.action).origin !== origin) return false;
    const fills = secret && password !== null && (!id.value || id.value === studentId)
      ? [[id, studentId], [secret, password]] : [[id, studentId]];
    for (const [element, value] of fills) {
      if (element.value) continue;
      element.value = value;
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    }
    return true;"""
    }
}
