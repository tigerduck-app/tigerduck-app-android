package org.ntust.app.tigerduck.browser.sites

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.browser.MoodleBrowserSignIn
import org.ntust.app.tigerduck.browser.SchoolBrowserSignIn

/** Leaves room below IIS's 16 KB header limit only where school SSO can restore the session. */
object SchoolSiteCookieBudget {
    const val BYTES = 8 * 1024

    fun appliesTo(opening: String): Boolean = SchoolBrowserSignIn.isSchool(opening) && !MoodleBrowserSignIn.isMoodle(opening)

    fun cookiesToDrop(cookies: List<Cookie>, opening: String): List<Cookie> {
        if (!appliesTo(opening)) return emptyList()
        val url = opening.toHttpUrlOrNull() ?: return emptyList()
        val sent = cookies.filter { it.matches(url) }
        val size = sent.sumOf { it.name.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size + 2 }
        if (size <= BYTES) return emptyList()
        return sent.filter { it.domain.trimStart('.').equals(url.host, ignoreCase = true) }
    }
}
