package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A response body with the ETag it came with, kept for the next conditional GET. */
data class ETaggedBody(val body: String, val etag: String?)

/** The directory's answer, which carries no ETag, with how long it stays fresh. */
data class ClubDirectoryAnswer(val body: String, val maxAgeSeconds: Long)

/**
 * Every path answers 503 with an HTML page while the club system is down, as it is for
 * maintenance each day from 00:00 to 07:00 Taipei time.
 */
class ClubsUnavailableException : IOException("the club system is unavailable")

/**
 * The public API of clubs.ntust.edu.tw, which needs no account: `GET /clubs` and
 * `GET /activities` under [BASE_URL], as the Apple apps' `ClubAPIClient`. The client is the app's
 * own, so the host's pin set (`clubs`) applies; it keeps no cookies, and no HTTP cache, which could
 * answer a revalidation itself and hide the 304. The club stores keep the answers.
 *
 * It follows no redirect: one to another host would send a request there under that host's trust
 * before the answer could be refused. A 3xx is a failed fetch, which keeps the stored copy.
 */
@Singleton
class ClubApiClient internal constructor(
    baseClient: OkHttpClient,
    private val baseUrl: HttpUrl,
) {
    @Inject constructor(baseClient: OkHttpClient) : this(baseClient, BASE_URL.toHttpUrl())

    private val client: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Every club, with how long the answer stays fresh. */
    suspend fun clubs(): ClubDirectoryAnswer = send(request("clubs")) { response, body ->
        ClubDirectoryAnswer(body, maxAge(response.header("Cache-Control")))
    }

    /**
     * The activities from today through 13 days on, as a conditional GET of the copy [etag] came
     * with: null for a 304, otherwise the new copy with its ETag. A failure throws before the
     * caller writes anything, so its copy stands.
     */
    suspend fun activities(etag: String?): ETaggedBody? {
        val request = request("activities").apply { if (!etag.isNullOrEmpty()) header("If-None-Match", etag) }
        return send(request) { response, body ->
            if (response.code == HTTP_NOT_MODIFIED) null else ETaggedBody(body, response.header("ETag"))
        }
    }

    private fun request(path: String): Request.Builder =
        Request.Builder().url(baseUrl.newBuilder().addPathSegment(path).build()).header("Accept", "application/json")

    private suspend fun <T> send(request: Request.Builder, answer: (Response, String) -> T): T =
        withContext(Dispatchers.IO) {
            client.newCall(request.build()).execute().use { response ->
                // No redirect is followed; an answer from another host is not the club system's all the same.
                if (hostName(response.request.url.host) != hostName(baseUrl.host)) {
                    throw IOException("club answer from another host")
                }
                if (response.code == HTTP_UNAVAILABLE) throw ClubsUnavailableException()
                if (response.code != HTTP_NOT_MODIFIED && !response.isSuccessful) {
                    throw IOException("club request failed: HTTP ${response.code}")
                }
                answer(response, response.body.string())
            }
        }

    companion object {
        const val BASE_URL = "https://clubs.ntust.edu.tw/api/v1/public"

        /** The directory's lifetime when its `Cache-Control` names none; the server sends 300. */
        const val DEFAULT_MAX_AGE_SECONDS = 300L

        /** A longer `max-age` is read as this, so a wrong header cannot keep an old directory for weeks. */
        const val LONGEST_MAX_AGE_SECONDS = 86_400L

        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_UNAVAILABLE = 503
        private val MAINTENANCE_HOURS = 0 until 7

        fun maxAge(cacheControl: String?): Long {
            val seconds = cacheControl.orEmpty().split(',').map { it.trim().lowercase() }
                .firstNotNullOfOrNull { directive -> directive.removePrefix("max-age=").takeIf { it != directive }?.toLongOrNull() }
            if (seconds == null || seconds < 0) return DEFAULT_MAX_AGE_SECONDS
            return minOf(seconds, LONGEST_MAX_AGE_SECONDS)
        }

        /** Whether [error] is the nightly maintenance; at another hour a 503 means the system is down for another reason. */
        fun isMaintenance(error: Throwable, at: Instant): Boolean =
            error is ClubsUnavailableException && at.atZone(TaipeiClock.zone).hour in MAINTENANCE_HOURS

        private fun hostName(host: String): String = host.trimEnd('.').lowercase()
    }
}
