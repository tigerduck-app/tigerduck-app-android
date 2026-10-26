package org.ntust.app.tigerduck.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The cookies of one sign-in run, in memory and nowhere else: the run's own, so it never
 * overwrites the app's shared jar ([NtustSessionManager]'s), which other sign-ins wipe, and the
 * session it gets is never one the app's own calls also use. A run may start from cookies of the
 * shared jar, as the website sign-in starts from its SSO session. Kept as a browser keeps them:
 * one per name, domain and path, the newest winning, gone once expired.
 */
class SignInCookieJar(private val now: () -> Long = System::currentTimeMillis) : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now()) this.cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt > now() && it.matches(url) }

    /** Every cookie the run holds, for any address. */
    @Synchronized
    fun all(): List<Cookie> = cookies.filter { it.expiresAt > now() }
}
