package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.network.moodle.MoodleAutologin
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice

/**
 * How the TigerDuck browser signs in to one site, so the browser screen knows
 * nothing of any site itself. Moodle's ([MoodleBrowserSignIn]) is the only one
 * now; the school's SSO sites would add theirs to [BrowserSignIns].
 *
 * A provider never fills a password into a page. It hands the browser a link
 * the site signs it in with; where none can be had now, it may sign the
 * browser in itself ([signInWithoutLink]), with nothing of the app's but the
 * site's session from a sign-in run for this browser alone. Where neither
 * works, the browser says its automatic sign-in failed, with Retry.
 */
interface BrowserSignIn {
    /** The site's name as the sign-in screen shows it. */
    val siteName: String

    /** Whether [url] is on this provider's site. */
    fun handles(url: String): Boolean

    /** Whether the browser's [cookieHeader] for the site (`CookieManager.getCookie`) holds a session. */
    fun hasSession(cookieHeader: String?): Boolean

    /** [hasSession] for [url]'s site, for a provider that signs in more than one site. */
    fun hasSession(url: String, cookieHeader: String?): Boolean = hasSession(cookieHeader)

    /**
     * Whether opening [url] with no session kept signs the browser in before it loads; when not,
     * only the site sending the browser to its sign-in page does.
     */
    fun signsInAhead(url: String): Boolean = true

    /**
     * Whether a sign-in page reached from [page], the one shown, null for none, signs the browser
     * in by itself. When not, the sign-in screen waits for the student's Retry.
     */
    fun signsInFrom(page: String?): Boolean = true

    /**
     * Whether a main-frame navigation to [url] means the browser is signed
     * out: the site's own sign-in page, which the browser replaces with its
     * sign-in screen rather than show.
     */
    fun isSignInPage(url: String): Boolean

    /**
     * Whether a sign-in can go on to [url]: a page of the site, not one of the pages it signs in
     * through (its sign-in page, the links [signedInUrl] gives).
     */
    fun isReturnPage(url: String): Boolean

    /**
     * Whether [url] is one of the links [signedInUrl] gives, which sign in once and only from this
     * phone: never shown, handed on or loaded again.
     */
    fun isSignInLink(url: String): Boolean

    /** A link that signs the browser in and goes on to [target], or null when none can be had now. */
    suspend fun signedInUrl(target: String): String?

    /**
     * Whether [signInWithoutLink] would sign the browser in now: false for a site with no such way,
     * and by itself only every so often; [byHand] for a sign-in the student asked for.
     */
    fun maySignInWithoutLink(byHand: Boolean = false): Boolean = false

    /**
     * Signs the browser in to the site without a link, for when [signedInUrl] has none to give: the
     * app signs in itself, out of the browser, and hands it only the site's session. True once the
     * browser holds one.
     */
    suspend fun signInWithoutLink(byHand: Boolean = false): Boolean = false

    /** [maySignInWithoutLink] for a sign-in that goes on to [target], for a provider that signs in more than one site. */
    fun maySignInWithoutLink(target: String, byHand: Boolean): Boolean = maySignInWithoutLink(byHand)

    /** [signInWithoutLink] for a sign-in that goes on to [target]. */
    suspend fun signInWithoutLink(target: String, byHand: Boolean): Boolean = signInWithoutLink(byHand)
}

/**
 * Moodle's sign-in: Moodle's own one-time autologin link ([MoodleAutologin]),
 * which signs the browser in under Moodle's session cookie; the browser keeps
 * that cookie, so later opens need no new key. Moodle gives a key every 6
 * minutes; in between, the app signs in to Moodle's website itself with the
 * school SSO session it already holds ([webSession]), posting no password,
 * and the browser gets that session.
 *
 * Moodle's `/login/` page is never shown: it offers the local password form,
 * which counts each failure and locks the account after about ten (see
 * `MoodleTokenService`).
 */
class MoodleBrowserSignIn(
    private val autologin: MoodleAutologin,
    private val webSession: MoodleWebSessionSignIn? = null,
) : BrowserSignIn {
    override val siteName: String = "Moodle"

    override fun handles(url: String): Boolean = isMoodle(url)

    override fun hasSession(cookieHeader: String?): Boolean = hasMoodleSession(cookieHeader)

    override fun isSignInPage(url: String): Boolean = isMoodleSignInPage(url)

    override fun isReturnPage(url: String): Boolean = isMoodleReturnPage(url)

    override fun isSignInLink(url: String): Boolean = isMoodleSignInLink(url)

    override suspend fun signedInUrl(target: String): String? = autologin.signedIn(target)

    override fun maySignInWithoutLink(byHand: Boolean): Boolean = webSession?.maySignIn(byHand) ?: false

    override suspend fun signInWithoutLink(byHand: Boolean): Boolean = webSession?.signIn(byHand) ?: false

    companion object {
        // Moodle names its session cookie MoodleSession, with the site's suffix if it sets one.
        private val SESSION_COOKIE = Regex("""(?:^|;\s*)MoodleSession\w*=([^;]+)""")

        /** The Moodle pages a sign-in can go on to: not its sign-in entries under `/auth/`, its OIDC entry among them. */
        fun isMoodleReturnPage(url: String): Boolean =
            MoodleAutologin.isSignInTarget(url) && url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.startsWith("/auth/") != true

        /** Moodle's one-time sign-in links: its app pages, autologin.php among them. */
        fun isMoodleSignInLink(url: String): Boolean =
            isMoodle(url) && url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.startsWith("/admin/tool/mobile/") == true

        fun isMoodle(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.scheme == "https" && parsed.host.equals(MoodleWebservice.SITE_HOST, ignoreCase = true)
        }

        fun hasMoodleSession(cookieHeader: String?): Boolean =
            cookieHeader != null && SESSION_COOKIE.findAll(cookieHeader).any { it.groupValues[1].isNotBlank() }

        /** Moodle's `/login/` pages: the sign-in form and what it posts to. Its logout is under `/login/` too. */
        fun isMoodleSignInPage(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (!parsed.host.equals(MoodleWebservice.SITE_HOST, ignoreCase = true)) return false
            val path = parsed.encodedPath.lowercase()
            return path.startsWith("/login/") && !path.startsWith("/login/logout.php")
        }
    }
}

/** The providers the browser asks, first match wins. */
class BrowserSignIns(private val providers: List<BrowserSignIn>) {
    fun forUrl(url: String): BrowserSignIn? = providers.firstOrNull { it.handles(url) }
}
