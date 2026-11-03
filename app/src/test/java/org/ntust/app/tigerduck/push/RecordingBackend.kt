package org.ntust.app.tigerduck.push

import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertTrue
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers every request in place of the backend, with the reply queued for its path or an empty
 * object, and keeps each request whole as it left the client: its method, its address, every
 * header, its body and the body's type. [ahead] run first, as the shared client's interceptors
 * run before a request leaves.
 */
internal class RecordingBackend(ahead: List<Interceptor> = emptyList()) {
    class Sent(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String?,
        val contentType: String? = null,
    )

    private class Reply(val code: Int, val body: String, val headers: Map<String, String>)

    private val replies = ConcurrentHashMap<String, ConcurrentLinkedQueue<Reply>>()

    val sent = CopyOnWriteArrayList<Sent>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .apply { ahead.forEach { addInterceptor(it) } }
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    /**
     * Answers the next request to [path] (the URL's path, `/v3/sync/full`) with [code], [body] and
     * [headers].
     */
    fun answer(path: String, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        replies.getOrPut(path) { ConcurrentLinkedQueue() }.add(Reply(code, body, headers))
    }

    private fun answer(request: Request): Response {
        sent += Sent(
            request.method,
            request.url.toString(),
            request.headers.map { (name, value) -> name to value },
            request.body?.let { Buffer().also(it::writeTo).readUtf8() },
            request.body?.contentType()?.toString(),
        )
        val reply = replies[request.url.encodedPath]?.poll() ?: Reply(200, "{}", emptyMap())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("")
            .apply { reply.headers.forEach { (name, value) -> header(name, value) } }
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

/** A sync fixture, `app/src/test/resources/sync/[path]`. */
internal fun syncFixture(path: String): String =
    requireNotNull(RecordingBackend::class.java.getResource("/sync/$path")) { "missing fixture sync/$path" }.readText()

/**
 * A [SyncApiClient] whose endpoint setting is [base], signed in with the pair `access`/`refresh`,
 * reaching nothing but [backend], its snapshots heard by [clock].
 */
internal fun syncApiClientAt(base: String, backend: OkHttpClient, clock: SyncClock? = null): SyncApiClient {
    val context = JvmContext()
    val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = base }
    // Never the real backend, even past the recording one.
    assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
    val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-recording", Context.MODE_PRIVATE))
    tokens.save("access", "refresh", Long.MAX_VALUE)
    return SyncApiClient(AuthTokenManager(tokens, backend, prefs, "device-1"), prefs, backend, clock)
}

/**
 * A [PushApiClient] whose endpoint setting is [base], signed in with the pair `access`/`refresh`,
 * reaching nothing but [backend]; [prefs] for a test that turns the switches, [clock] for one
 * whose `/v4` writes it stamps, and [uploadNews] for one that hears each course upload that landed.
 */
internal fun pushApiClientAt(
    base: String,
    backend: OkHttpClient,
    prefs: AppPreferences = AppPreferences(JvmContext()),
    clock: SyncClock? = null,
    uploadNews: CourseUploadListener? = null,
): PushApiClient {
    prefs.announcementApiBaseUrlOverride = base
    // Never the real backend, even past the recording one.
    assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
    val tokens = V3TokenStore(JvmContext().getSharedPreferences("v3-tokens-recording", Context.MODE_PRIVATE))
    tokens.save("access", "refresh", Long.MAX_VALUE)
    return PushApiClient(backend, prefs, AuthTokenManager(tokens, backend, prefs, "device-1"), clock, uploadNews?.let { dagger.Lazy { it } })
}
