package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice

/** What a page inside the TigerDuck browser asks to go to, and what the browser does. */
sealed interface NavigationDecision {
    /** Loads inside. */
    data object Load : NavigationDecision

    /** Off the allowlist: asks, then hands it to the phone's browser. */
    data class OpenOutside(val url: String) : NavigationDecision

    /** A `mailto:` link: the mail app's, as anywhere else in the app. */
    data class HandOff(val url: String) : NavigationDecision

    /** A scheme no page should hand anyone (`intent:`, `file:`, `javascript:`…): nothing happens. */
    data object Refuse : NavigationDecision
}

object BrowserRouting {
    /**
     * What the browser does with a page's main-frame navigation to [url]:
     * load it when the allowlist lets it in, ask before sending any other web
     * page outside, hand a mail link to the mail app, and refuse the rest.
     * `about:blank`, which pages open to write into, stays. Moodle's logout is refused
     * ([isMoodleLogout]), as are the school sites' ([isSchoolLogout]).
     */
    fun navigation(url: String, allowlist: BrowserAllowlist): NavigationDecision = when (scheme(url)) {
        "https", "http" -> when {
            isMoodleLogout(url) || isSchoolLogout(url) -> NavigationDecision.Refuse
            allowlist.allows(url) -> NavigationDecision.Load
            else -> NavigationDecision.OpenOutside(url)
        }
        "about" -> if (url.equals("about:blank", ignoreCase = true)) NavigationDecision.Load else NavigationDecision.Refuse
        "mailto" -> NavigationDecision.HandOff(url)
        else -> NavigationDecision.Refuse
    }

    /**
     * Whether a request the WebView is about to send must not go: a main-frame
     * one the allowlist does not let in. A form's POST is never put to
     * `shouldOverrideUrlLoading`, so this is the check that stops it before
     * the form reaches another site. Moodle's logout never goes, whoever asks for it: a frame or a
     * script ends the session as surely as the page.
     *
     * What no WebView callback sees is where a server redirects a frame or a script's request:
     * only its first address comes here. Sent to the logout that way, by a page of Moodle's own that
     * knows the session's key (`sesskey`, which the logout asks for), the session would end; the
     * browser then signs in again as for any session Moodle ends.
     */
    fun blocksRequest(url: String, isForMainFrame: Boolean, allowlist: BrowserAllowlist): Boolean =
        isMoodleLogout(url) || isSchoolLogout(url) || isForMainFrame && navigation(url, allowlist) != NavigationDecision.Load

    /**
     * Whether [url] ends Moodle's session: its logout page, `/login/logout.php`, and auth_oidc's
     * `/auth/oidc/logout.php`, which the school's SSO calls to end a session by its ID. As the
     * server reads the address: any scheme, port or query, the host's case and trailing dot,
     * percent-encoding, doubled slashes and dot segments, and anything after the script's name,
     * which PHP runs all the same. The session is TigerDuck's to end, at its own sign-out.
     */
    /**
     * Whether [url] signs the student out of a school site, on `ntust.edu.tw` or a host under it but
     * Moodle's, whose logout is [isMoodleLogout]'s, page by page: a
     * path segment that ends in logout, logoff or signout once its extension and all but its
     * letters are left out (`Logout.aspx`, `LogOff`, `AGLogout`, `sign-out`), SSO's `endsession`,
     * or an action the query names so (`?action=logout`). Read as the server reads it, as
     * [isMoodleLogout] is. Each site's session is TigerDuck's to end, at its own sign-out: a logout
     * inside the browser could end the SSO session or leave the student at a sign-in page.
     */
    fun isSchoolLogout(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.trimEnd('.').lowercase()
        if (host != SCHOOL_DOMAIN && !host.endsWith(".$SCHOOL_DOMAIN")) return false
        if (host == MoodleWebservice.SITE_HOST.lowercase()) return false
        val segments = parsed.pathSegments.map(::letters)
        if (segments.any { it == END_SESSION || it.isNotEmpty() && LOGOUT_WORDS.any(it::endsWith) }) return true
        return parsed.queryParameterNames.any { name ->
            name.lowercase() in ACTION_PARAMETERS && parsed.queryParameterValues(name).any { letters(it.orEmpty()) in LOGOUT_WORDS }
        }
    }

    /** [text] before its first dot, lowercased, letters only. */
    private fun letters(text: String): String = text.substringBefore('.').lowercase().filter(Char::isLetter)

    private const val SCHOOL_DOMAIN = "ntust.edu.tw"
    private const val END_SESSION = "endsession"
    private val LOGOUT_WORDS = listOf("logout", "logoff", "signout")

    /** The query parameters sites name an action by. */
    private val ACTION_PARAMETERS = setOf("action", "act", "op", "cmd", "do", "mode", "method", "task", "func")

    fun isMoodleLogout(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.host.trimEnd('.').equals(MoodleWebservice.SITE_HOST, ignoreCase = true)) return false
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }.map { it.lowercase() }
        return LOGOUT_PATHS.any { path -> segments.size >= path.size && segments.subList(0, path.size) == path }
    }

    private val LOGOUT_PATHS = listOf(listOf("login", "logout.php"), listOf("auth", "oidc", "logout.php"))

    private fun scheme(url: String): String? = url.substringBefore(':', "").takeIf { it.isNotEmpty() }?.lowercase()
}
