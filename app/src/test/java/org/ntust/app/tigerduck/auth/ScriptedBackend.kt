package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Answers every request in place of the backend: the interceptor never calls proceed(). Each
 * endpoint answers 200 unless a test queued another status for it, and a [Held] answer keeps its
 * request waiting until the test releases it. A sign-in or a refresh answers with the pair
 * "access-2" and "refresh-2".
 */
internal class ScriptedBackend {
    class Received(
        val endpoint: String,
        val authorization: String?,
        val body: String,
        val host: String = "",
        val query: String = "",
        /** The `X-TigerSync-Protocol` header, null without one. */
        val protocol: String? = null,
    )

    class Held {
        val arrived = CompletableDeferred<Unit>()
        private val reply = CompletableFuture<Int>()

        fun release(code: Int) {
            reply.complete(code)
        }

        fun await(): Int = reply.get(10, TimeUnit.SECONDS)
    }

    private val received = CopyOnWriteArrayList<Received>()
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedQueue<Any>>()

    /** Runs as each sign-in arrives, before its answer. */
    @Volatile
    var onLogin: () -> Unit = {}

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    fun answer(endpoint: String, vararg codes: Int) {
        codes.forEach { queue(endpoint).add(it) }
    }

    /** Answers [endpoint] once with [code] and [body] in place of the default reply. */
    fun answer(endpoint: String, code: Int, body: String) {
        queue(endpoint).add(code to body)
    }

    fun hold(endpoint: String): Held = Held().also { queue(endpoint).add(it) }

    fun received(endpoint: String): List<Received> = received.filter { it.endpoint == endpoint }

    fun endpoints(): List<String> = received.map { it.endpoint }

    /** The Moodle token each sign-in carried, in order. */
    fun sentMoodleTokens(): List<String?> = received("POST /auth/login").map {
        JSONObject(it.body).optString("moodle_token").ifEmpty { null }
    }

    private fun queue(endpoint: String) = queued.getOrPut(endpoint) { ConcurrentLinkedQueue() }

    private fun answer(request: Request): Response {
        val endpoint = request.method + " " + request.url.encodedPath.removePrefix("/v3")
        val sent = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        received += Received(
            endpoint, request.header("Authorization"), sent, request.url.host, request.url.encodedQuery.orEmpty(),
            request.header("X-TigerSync-Protocol"),
        )
        if (endpoint == "POST /auth/login") onLogin()
        val next = queued[endpoint]?.poll()
        val code = when (next) {
            is Held -> {
                next.arrived.complete(Unit)
                next.await()
            }
            is Int -> next
            is Pair<*, *> -> next.first as Int
            else -> 200
        }
        val reply = when {
            next is Pair<*, *> -> next.second as String
            code !in 200..299 -> """{"detail":"refused"}"""
            endpoint == "POST /auth/login" || endpoint == "POST /auth/refresh" ->
                """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":900,"device_id":"device-1"}"""
            else -> """{"device_id":"device-1","push_token_id":1}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
