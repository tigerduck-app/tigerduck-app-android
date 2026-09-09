package org.ntust.app.tigerduck.network

import okhttp3.Interceptor
import okhttp3.Response
import org.ntust.app.tigerduck.data.preferences.AppPreferences

/**
 * Reports every response from our own backend to [ApiVersionGate].
 *
 * An interceptor rather than a check at each call site: the backend is
 * reached from a dozen clients and several dozen `!response.isSuccessful`
 * branches, and a signal this important should not depend on remembering to
 * add it to the next one.
 *
 * The host filter is the whole point. The same [okhttp3.OkHttpClient] is
 * shared with the NTUST, Moodle and library services, and a 410 from the
 * school's servers means a page moved, not that this app is out of date —
 * latching on one of those would tell every user to update for no reason.
 *
 * The base URL is resolved per response rather than captured once, because a
 * debug build can repoint the endpoint at runtime and the comparison has to
 * follow it.
 */
class ApiVersionInterceptor(
    private val prefs: AppPreferences,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 410 && isOurBackend(chain.request().url.toString())) {
            ApiVersionGate.note(response.code)
        }
        return response
    }

    private fun isOurBackend(requestUrl: String): Boolean {
        val base = runCatching {
            resolveAnnouncementEndpoint(prefs).url.trimEnd('/')
        }.getOrNull() ?: return false
        return base.isNotEmpty() && requestUrl.startsWith(base)
    }
}
