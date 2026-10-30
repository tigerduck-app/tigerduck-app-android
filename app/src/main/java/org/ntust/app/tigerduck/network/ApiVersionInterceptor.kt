package org.ntust.app.tigerduck.network

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
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
class ApiVersionInterceptor internal constructor(
    // Our backend's base URL as the endpoint setting names it now; null when it cannot be read.
    private val backend: () -> String?,
) : Interceptor {

    constructor(prefs: AppPreferences) : this({
        runCatching { resolveAnnouncementEndpoint(prefs).url.trimEnd('/') }.getOrNull()
    })

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 410 && isOurBackend(chain.request().url.toString()) && !asksForFullSync(response)) {
            ApiVersionGate.note(response.code)
        }
        return response
    }

    // The change feed answers 410 too when the changes after the revision it was asked for are
    // pruned, and the app then makes a full sync (SyncApiClient.fetchChanges). That one says
    // nothing about the app's version. Any other 410, whatever its body, still retires the API.
    // Peeked, so the caller still reads the whole body.
    private fun asksForFullSync(response: Response): Boolean = runCatching {
        JSONObject(response.peekBody(PEEKED_BYTES).string()).optString("error") == SYNC_REVISION_EXPIRED
    }.getOrDefault(false)

    private fun isOurBackend(requestUrl: String): Boolean {
        val base = backend() ?: return false
        return base.isNotEmpty() && requestUrl.startsWith(base)
    }

    private companion object {
        const val SYNC_REVISION_EXPIRED = "sync_revision_expired"

        // The feed's 410 body is a few short fields; a longer one is no such answer.
        const val PEEKED_BYTES = 4_096L
    }
}
