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

    /** The host whose answer set each cookie, by its name, domain and path. */
    private val setBy = mutableMapOf<Triple<String, String, String>, String>()

    private val Cookie.key get() = Triple(name, domain, path)

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.key == cookie.key }
            setBy.remove(cookie.key)
            if (cookie.expiresAt > now()) {
                this.cookies += cookie
                setBy[cookie.key] = url.host.lowercase()
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt > now() && it.matches(url) }

    /** The cookies [host]'s own answers set, whatever their scope. */
    @Synchronized
    fun setBy(host: String): List<Cookie> = cookies.filter { it.expiresAt > now() && setBy[it.key] == host.lowercase() }
}
