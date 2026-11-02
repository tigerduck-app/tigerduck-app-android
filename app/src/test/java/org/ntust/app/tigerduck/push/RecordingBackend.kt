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
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers every request in place of the backend, with the reply queued for its path or an empty
 * object, and keeps each request whole as it left the client: its method, its address, every
 * header and its body. [ahead] run first, as the shared client's interceptors run before a
 * request leaves.
 */
internal class RecordingBackend(ahead: List<Interceptor> = emptyList()) {
    class Sent(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: String?)

    private class Reply(val code: Int, val body: String)

    private val replies = ConcurrentHashMap<String, ConcurrentLinkedQueue<Reply>>()

    val sent = CopyOnWriteArrayList<Sent>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .apply { ahead.forEach { addInterceptor(it) } }
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    /** Answers the next request to [path] (the URL's path, `/v3/sync/full`) with [code] and [body]. */
    fun answer(path: String, code: Int, body: String) {
        replies.getOrPut(path) { ConcurrentLinkedQueue() }.add(Reply(code, body))
    }

    private fun answer(request: Request): Response {
        sent += Sent(
            request.method,
            request.url.toString(),
            request.headers.map { (name, value) -> name to value },
            request.body?.let { Buffer().also(it::writeTo).readUtf8() },
        )
        val reply = replies[request.url.encodedPath]?.poll() ?: Reply(200, "{}")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("")
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

/** A sync fixture, `app/src/test/resources/sync/[path]`. */
internal fun syncFixture(path: String): String =
    requireNotNull(RecordingBackend::class.java.getResource("/sync/$path")) { "missing fixture sync/$path" }.readText()

/**
 * A [SyncApiClient] whose endpoint setting is [base], signed in with the pair `access`/`refresh`,
 * reaching nothing but [backend].
 */
internal fun syncApiClientAt(base: String, backend: OkHttpClient): SyncApiClient {
    val context = JvmContext()
    val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = base }
    // Never the real backend, even past the recording one.
    assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
    val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-recording", Context.MODE_PRIVATE))
    tokens.save("access", "refresh", Long.MAX_VALUE)
    return SyncApiClient(AuthTokenManager(tokens, backend, prefs, "device-1"), prefs, backend)
}
