package org.ntust.app.tigerduck.network

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bulletin subscriptions on `/v4` (`docs/api-v4.md` on tigerduck-backend `main`,
 * "Subscriptions"): the list read with each rule's `client_id`, and saved whole in one PUT that
 * keeps each rule by it. The fixtures in `sync/v4/` are written by hand from that document.
 *
 * The backend here answers as the first interceptor of the client, so it sees a request as the
 * client built it: `Accept`, which the client's own interceptor adds after that, is not in it.
 */
class BulletinApiClientV4Test {

    private class Sent(val line: String, val headers: List<Pair<String, String>>, val contentType: String?, val body: String?)

    private val sent = CopyOnWriteArrayList<Sent>()
    private val replies = ConcurrentLinkedQueue<Pair<Int, String>>()

    private val backend = OkHttpClient.Builder().addInterceptor { chain ->
        val request: Request = chain.request()
        sent += Sent(
            "${request.method} ${request.url}",
            request.headers.map { (name, value) -> name to value },
            request.body?.contentType()?.toString(),
            request.body?.let { Buffer().also(it::writeTo).readUtf8() },
        )
        val (code, reply) = replies.poll() ?: (200 to "{}")
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private fun clientAt(base: String): Pair<BulletinApiClient, AuthTokenManager> {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = base }
        // Never the real backend, even past this one.
        assertEquals(ResolvedAnnouncementEndpoint(base, overrideApplied = true), resolveAnnouncementEndpoint(prefs))
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-bulletins-v4", Context.MODE_PRIVATE))
        tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        val manager = AuthTokenManager(tokens, backend, prefs, "device-1")
        return BulletinApiClient(backend, prefs, manager) to manager
    }

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/sync/v4/$name")) { "missing fixture sync/v4/$name" }.readText()

    private fun json(text: String) = JsonParser.parseString(text)

    /** The three rules of `subscriptions-put.json`, as the page holds them. */
    private val rules = listOf(
        SubscriptionRule(41, "Scholarships", listOf("office"), listOf("scholarship"), "OR", true, "0b7c6f2e-4a1d-4e8b-9c3f-2d5e8a1b7c90"),
        SubscriptionRule(42, null, emptyList(), listOf("lecture", "event"), "AND", true, "5f3e2d1c-0b9a-4876-a5b4-c3d2e1f0a9b8"),
        SubscriptionRule(null, "Lectures", listOf("office"), emptyList(), "AND", true, "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
    )

    @Test
    fun `the rules are read with each one's client_id`() = runTest {
        val (client, manager) = clientAt(V4)
        replies += 200 to fixture("subscriptions.json")

        val read = client.fetchSubscriptions(manager.session.signIns.current).items

        assertEquals("GET $V4/bulletin-subscriptions", sent.single().line)
        assertEquals(listOf("Authorization" to "Bearer access-1"), sent.single().headers)
        assertEquals(
            listOf(
                SubscriptionRule(41, "Scholarships", listOf("office"), listOf("scholarship"), "OR", true, "0b7c6f2e-4a1d-4e8b-9c3f-2d5e8a1b7c90"),
                SubscriptionRule(42, null, emptyList(), listOf("lecture", "event"), "AND", false, "5f3e2d1c-0b9a-4876-a5b4-c3d2e1f0a9b8"),
            ),
            read,
        )
    }

    @Test
    fun `the whole list goes in one PUT, each rule by its client_id`() = runTest {
        val (client, manager) = clientAt(V4)
        replies += 200 to fixture("subscriptions-put-reply.json")

        val kept = client.saveSubscriptions(rules, manager.session.signIns.current)

        val put = sent.single()
        assertEquals("PUT $V4/bulletin-subscriptions", put.line)
        assertEquals(listOf("Authorization" to "Bearer access-1"), put.headers)
        assertEquals("application/json; charset=utf-8", put.contentType)
        assertEquals(json(fixture("subscriptions-put.json")), json(put.body!!))
        assertEquals(listOf(41, 42, 57), kept.map { it.id })
        assertEquals(rules.map { it.clientId }, kept.map { it.clientId })
        assertEquals(listOf(true, true, true), kept.map { it.enabled })
    }

    @Test
    fun `an empty list is put as one`() = runTest {
        val (client, manager) = clientAt(V4)
        replies += 200 to """{"items":[]}"""

        assertEquals(emptyList<SubscriptionRule>(), client.saveSubscriptions(emptyList(), manager.session.signIns.current))

        assertEquals("""{"rules":[]}""", sent.single().body)
    }

    @Test
    fun `a duplicate client_id is refused as an invalid request`() = runTest {
        val (client, manager) = clientAt(V4)
        val body = fixture("error-duplicate-client-id.json")
        replies += 422 to body

        val failure = runCatching { client.saveSubscriptions(rules, manager.session.signIns.current) }.exceptionOrNull()

        assertTrue("got $failure", failure is BackendException)
        assertEquals(BackendError(422, "invalid_request"), (failure as BackendException).error)
        assertEquals("saveSubscriptions failed: HTTP 422 $body", failure.message)
    }

    @Test
    fun `a rule without a client_id is never put`() = runTest {
        val (client, manager) = clientAt(V4)

        val failure = runCatching {
            client.saveSubscriptions(rules + SubscriptionRule(name = "Grants"), manager.session.signIns.current)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is BulletinApiException)
        assertEquals(emptyList<String>(), sent.map { it.line })
    }

    @Test
    fun `a save under an ended sign-in is never put`() = runTest {
        val (client, manager) = clientAt(V4)
        val signIn = manager.session.signIns.current
        manager.logout()
        manager.session.open()

        val failure = runCatching { client.saveSubscriptions(rules, signIn) }.exceptionOrNull()

        assertTrue("got $failure", failure is org.ntust.app.tigerduck.auth.NotSignedInException)
        assertEquals(emptyList<String>(), sent.map { it.line })
    }

    private companion object {
        const val V4 = "http://127.0.0.1:9/v4"
    }
}
