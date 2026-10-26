package org.ntust.app.tigerduck.browser

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.util.logSummary

/**
 * The school's sites' sign-in, every `*.ntust.edu.tw` site but Moodle's, which has its own
 * ([MoodleBrowserSignIn]). A site sending the browser to SSO's sign-in page is signed in by the app
 * itself: [run] (`SsoLoginService.signInForBrowser`) signs the site in with the SSO session the app
 * holds, in a run of its own, and the browser gets the site's cookies alone. SSO's session never
 * goes into the browser, nor a password into a page. With no SSO session to use, the app signs in
 * to SSO first ([warm]), as for its own calls, and runs once more. ssoam's NetIQ page and a site's
 * own password form are the student's to sign in to: they are never taken over.
 *
 * The browser signs a site in by itself only from a page the app opened or a school page
 * ([signsInFrom]); another site sending it to SSO's sign-in page gets the sign-in screen, and the
 * student's Retry. By itself it signs each site in at most once every [INTERVAL_MS], a failed run
 * included, so a site that does not keep the session ends on the screen rather than loop.
 *
 * Nothing runs for the demo account or while the browser cannot be used ([usable]), and nothing is
 * kept of a run a sign-out or an account change overtook ([generation]) or that took longer than
 * [timeoutMs].
 */
class SchoolBrowserSignIn(
    private val run: suspend (target: String) -> List<Cookie>,
    private val warm: suspend () -> Boolean,
    private val isDemo: () -> Boolean,
    private val usable: () -> Boolean,
    private val generation: () -> Int,
    private val writeCookie: (url: String, value: String) -> Unit,
    private val flush: () -> Unit,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : BrowserSignIn {

    override val siteName: String = "NTUST"

    /** When each site was last signed in by itself, by host, and for which [generation]. */
    private val lastRuns = mutableMapOf<String, Pair<Long, Int>>()

    /** The cookies handed to the browser, by host, and for which [generation]. */
    private val handed = mutableMapOf<String, Pair<Int, Set<String>>>()

    override fun handles(url: String): Boolean = isSchool(url) && !MoodleBrowserSignIn.isMoodle(url)

    /** Never asked without the site's address: see the other [hasSession]. */
    override fun hasSession(cookieHeader: String?): Boolean = false

    /** Whether the browser still holds every cookie the app handed it for [url]'s site. */
    override fun hasSession(url: String, cookieHeader: String?): Boolean {
        val host = hostOf(url) ?: return false
        val names = synchronized(this) { handed[host]?.takeIf { it.first == generation() }?.second } ?: return false
        val sent = cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.toSet()
        return names.isNotEmpty() && sent.containsAll(names)
    }

    /** A school page loads as it is; its site's own redirect to SSO is what signs it in. */
    override fun signsInAhead(url: String): Boolean = false

    override fun signsInFrom(page: String?): Boolean = page == null || isSchool(page)

    override fun isSignInPage(url: String): Boolean = isSsoSignInPage(url) && !isForMoodle(url)

    override fun isReturnPage(url: String): Boolean = handles(url) && !isSso(url)

    override fun isSignInLink(url: String): Boolean = false

    override suspend fun signedInUrl(target: String): String? = null

    override fun maySignInWithoutLink(target: String, byHand: Boolean): Boolean {
        if (isDemo() || !usable() || !isReturnPage(target)) return false
        if (byHand) return true
        val host = hostOf(target) ?: return false
        val (at, forGeneration) = synchronized(this) { lastRuns[host] } ?: return true
        return forGeneration != generation() || now() - at >= INTERVAL_MS
    }

    override suspend fun signInWithoutLink(target: String, byHand: Boolean): Boolean {
        if (!maySignInWithoutLink(target, byHand)) return false
        val host = hostOf(target) ?: return false
        val before = generation()
        synchronized(this) { lastRuns[host] = now() to before }
        val cookies = try {
            withTimeoutOrNull(timeoutMs) { runWithSession(target) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a school site's sign-in failed: ${e.logSummary()}")
            null
        } ?: return false
        if (generation() != before || !usable()) return false
        cookies.forEach { writeCookie("https://${it.domain}/", it.forBrowser()) }
        flush()
        synchronized(this) { handed[host] = before to cookies.map { it.name }.toSet() }
        return true
    }

    /** [run], after a sign-in to SSO when the app holds no SSO session to use. */
    private suspend fun runWithSession(target: String): List<Cookie> = try {
        run(target)
    } catch (_: SsoLoginError.SessionMissing) {
        if (!warm()) throw SsoLoginError.SessionMissing()
        run(target)
    }

    /** As the site set it, sent over https alone whatever it said. */
    private fun Cookie.forBrowser(): String = if (secure) toString() else "$this; secure"

    companion object {
        private const val TAG = "SchoolBrowserSignIn"

        /** As long as Moodle's: longer than a site's sign-in takes, shorter than a student waits on a spinner. */
        const val TIMEOUT_MS = 15_000L

        /** How often a site is signed in by itself; Retry signs it in at once. */
        const val INTERVAL_MS = 60_000L

        private const val SCHOOL_DOMAIN = "ntust.edu.tw"
        private val SSO_HOSTS = setOf("ssoam.ntust.edu.tw", "ssoam2.ntust.edu.tw")
        private const val SSO_SIGN_IN_HOST = "ssoam2.ntust.edu.tw"

        /** An https page of the school's: `ntust.edu.tw` or any host under it. */
        fun isSchool(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            val host = parsed.host.lowercase()
            return parsed.isHttps && (host == SCHOOL_DOMAIN || host.endsWith(".$SCHOOL_DOMAIN"))
        }

        private fun isSso(url: String): Boolean = hostOf(url) in SSO_HOSTS

        /** ssoam2's sign-in form, where a site that finds no session of its own sends the browser. */
        fun isSsoSignInPage(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps && parsed.host.lowercase() == SSO_SIGN_IN_HOST &&
                parsed.encodedPath.lowercase().startsWith("/account/login")
        }

        /** An SSO sign-in that goes on to Moodle, whose own provider signs it in: its ReturnUrl names Moodle's site. */
        private fun isForMoodle(url: String): Boolean {
            val back = url.toHttpUrlOrNull()?.queryParameter("ReturnUrl") ?: return false
            return back.lowercase().contains(org.ntust.app.tigerduck.network.moodle.MoodleWebservice.SITE_HOST.lowercase())
        }

        private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host?.lowercase()
    }
}
