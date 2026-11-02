package org.ntust.app.tigerduck.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.ApiBase
import org.ntust.app.tigerduck.sync.SyncClock

/**
 * Hears the backend's clock: every response from our backend carries `X-TigerSync-Clock`, errors
 * too, and [SyncClock] keeps the last one heard, for the cap on its stamps, and the largest, below
 * which it never stamps (`tigersync-clock`, "The backend reports its clock on every response").
 *
 * On the shared client, as [ApiVersionInterceptor] is, so every call to our backend is heard
 * whichever service made it. Only our backend's, the one the endpoint setting names now at either
 * API version ([ApiBase.serves]): the NTUST, Moodle and library traffic on the same client, and a
 * departing unregister to a backend the setting no longer names, say nothing of its time. Read from
 * the response's own request, the last hop of any redirect. Hearing changes no request and no
 * response, and a clock that cannot record what it heard fails nothing.
 */
class SyncClockInterceptor internal constructor(
    // Our backend's base URL as the endpoint setting names it now; null when it cannot be read.
    private val backend: () -> String?,
    private val heard: (serverClockUs: Long) -> Unit,
) : Interceptor {

    constructor(prefs: AppPreferences, clock: SyncClock) : this(
        { runCatching { resolveAnnouncementEndpoint(prefs).url }.getOrNull() },
        clock::heard,
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val clock = response.header(CLOCK_HEADER)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return response
        val base = backend() ?: return response
        if (!ApiBase.serves(base, response.request.url)) return response
        try {
            heard(clock)
        } catch (e: RuntimeException) {
            Log.w(TAG, "the backend's clock was not recorded: ${e.javaClass.simpleName}")
        }
        return response
    }

    internal companion object {
        const val CLOCK_HEADER = "X-TigerSync-Clock"
        private const val TAG = "SyncClockInterceptor"
    }
}
