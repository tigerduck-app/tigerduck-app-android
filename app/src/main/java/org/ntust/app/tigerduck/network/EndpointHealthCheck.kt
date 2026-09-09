package org.ntust.app.tigerduck.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes a candidate API endpoint before the app commits to it.
 *
 * The endpoint row lets anyone point the app at their own deployment of the
 * (open source) TigerDuck backend. Without a probe, a typo — a wrong port, a
 * stale LAN address, a host that answers but isn't the backend — is only
 * discovered later as every screen failing to load, from a Settings row the
 * user has already navigated away from. Checking here turns that into an
 * inline error at the moment of saving.
 *
 * ## What counts as healthy
 *
 * The server must answer `GET {base}/../health` with `200` and a JSON body
 * whose `status` is `"ok"`. That is the backend's own contract
 * (`server/main.py`), so requiring the shape — rather than accepting any
 * `200` — is what distinguishes "your backend is up" from "something on this
 * address served us a captive-portal page".
 */
@Singleton
class EndpointHealthCheck @Inject constructor(client: OkHttpClient) {

    /**
     * Its own client rather than the injected one: the shared client's read
     * timeout is 20 s, and a probe the user is watching a spinner for has to
     * give up sooner than that. Built from the injected client so the
     * connection pool, dispatcher and any interceptors are still shared.
     */
    private val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        /** A TigerDuck backend answered. */
        data object Ok : Result

        /**
         * Nothing answered, or the transport failed (DNS, TLS, timeout,
         * connection refused). [detail] is the underlying message, for the
         * inline error.
         */
        data class Unreachable(val detail: String) : Result

        /**
         * Something answered, but it did not look like this backend — wrong
         * status code, non-JSON body, or no `"status": "ok"`.
         */
        data object NotTigerDuck : Result
    }

    suspend fun probe(base: String): Result = withContext(Dispatchers.IO) {
        val url = healthUrl(base) ?: return@withContext Result.Unreachable("Invalid URL")
        val request = Request.Builder()
            .url(url)
            .get()
            // The probe answers "is this endpoint live *right now*", so a
            // cached 200 from a previous address would be exactly the wrong
            // answer.
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .build()
        try {
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.NotTigerDuck
                val body = response.body.string()
                val status = runCatching { JSONObject(body).optString("status") }.getOrNull()
                if (status?.lowercase() == "ok") Result.Ok else Result.NotTigerDuck
            }
        } catch (e: Exception) {
            Result.Unreachable(e.message ?: e::class.java.simpleName)
        }
    }

    companion object {
        /**
         * Long enough for a cold container on a home server to wake, short
         * enough that a wrong address doesn't leave the Save button spinning
         * past the user's patience.
         */
        private const val PROBE_TIMEOUT_SECONDS = 10L

        /**
         * The health URL for a given API base.
         *
         * `/health` is mounted at the FastAPI app root, a **sibling** of the
         * version prefix rather than a child of it — so `…/v3` maps to
         * `…/health`, and a deployment behind a path prefix
         * (`…/tigerduck/v3`) maps to `…/tigerduck/health`. Dropping the last
         * path segment and appending `health` gets both right, where
         * appending to the base would produce `…/v3/health` (404) and going
         * to the bare origin would miss the prefixed deployment.
         */
        fun healthUrl(base: String): HttpUrl? {
            val parsed = base.toHttpUrlOrNull() ?: return null
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }.dropLast(1)
            return parsed.newBuilder()
                .encodedPath("/" + (segments + "health").joinToString("/"))
                .query(null)
                .fragment(null)
                .build()
        }
    }
}
