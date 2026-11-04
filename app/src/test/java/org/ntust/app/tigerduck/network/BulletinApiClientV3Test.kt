package org.ntust.app.tigerduck.network

import android.content.Context
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
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bulletin subscription requests on `/v3` as [BulletinApiClient] sends them: method, address,
 * headers and body, byte for byte, and what it makes of each answer. Phase 3 saves the rules as one
 * list on `/v4` (T3.24), and on `/v3` none of this may change.
 *
 * The backend here answers as the first interceptor of the client each request goes through, so it
 * sees a request as the client built it: `Accept`, which the client's own interceptor adds after
 * that, is not in it.
 */
class BulletinApiClientV3Test {

    // Pins /v3's requests and rules until Z1 and Z2 delete them: a base ending in /v3 stays there.
    @get:Rule
    val speaksV3 = SpeaksV3()

    private class Sent(
        val line: String,
        val headers: List<Pair<String, String>>,
        val contentType: String?,
        val contentLength: Long?,
        val body: String?,
    )

    private val sent = CopyOnWriteArrayList<Sent>()
    private val replies = ConcurrentLinkedQueue<Pair<Int, String>>()

    private val backend = OkHttpClient.Builder().addInterceptor { chain ->
        val request: Request = chain.request()
        sent += Sent(
            "${request.method} ${request.url}",
            request.headers.map { (name, value) -> name to value },
            request.body?.contentType()?.toString(),
            request.body?.contentLength(),
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

    private val context = JvmContext()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = BASE }
    private val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-bulletins", Context.MODE_PRIVATE))
        .apply { save("access-1", "refresh-1", Long.MAX_VALUE) }
    private val manager = AuthTokenManager(tokens, backend, prefs, "device-1")
    private val client = BulletinApiClient(backend, prefs, manager)
    private val signIn: SignIn = manager.session.signIns.current

    private val rule = SubscriptionRule(
        id = 7, name = "Scholarships", orgs = listOf("office"), tags = listOf("scholarship"), mode = "OR", enabled = false,
    )

    private fun answer(code: Int, body: String) {
        replies += code to body
    }

    @Test
    fun `the endpoint setting is a v3 base`() {
        // Never the real backend, even past this one.
        assertEquals(ResolvedAnnouncementEndpoint(BASE, overrideApplied = true), resolveAnnouncementEndpoint(prefs))
    }

    @Test
    fun `the rules are read with the session's token`() = runTest {
        answer(200, """{"items":[{"id":3,"name":"Lectures","orgs":["office"],"tags":["lecture"],"mode":"OR","enabled":false,"revision":2,"deleted_at":null}]}""")

        val read = client.fetchSubscriptions(signIn)

        val get = sent.single()
        assertEquals("GET $BASE/bulletin-subscriptions", get.line)
        assertEquals(listOf("Authorization" to "Bearer access-1"), get.headers)
        assertEquals(null, get.body)
        assertEquals(
            listOf(SubscriptionRule(id = 3, name = "Lectures", orgs = listOf("office"), tags = listOf("lecture"), mode = "OR", enabled = false)),
            read.items,
        )
    }

    @Test
    fun `a new rule is posted whole, without its id`() = runTest {
        answer(200, """{"id":8,"name":"Scholarships","orgs":["office"],"tags":["scholarship"],"mode":"OR","enabled":false,"revision":1,"deleted_at":null}""")

        val created = client.createSubscription(rule, signIn)

        val post = sent.single()
        assertEquals("POST $BASE/bulletin-subscriptions", post.line)
        assertEquals(listOf("Authorization" to "Bearer access-1"), post.headers)
        assertEquals("application/json; charset=utf-8", post.contentType)
        assertEquals("""{"name":"Scholarships","orgs":["office"],"tags":["scholarship"],"mode":"OR","enabled":false}""", post.body)
        assertEquals(rule.copy(id = 8), created)
    }

    @Test
    fun `a rule without a name leaves it out`() = runTest {
        answer(200, """{"id":8,"orgs":[],"tags":["scholarship"],"mode":"AND","enabled":true}""")

        client.createSubscription(SubscriptionRule(tags = listOf("scholarship")), signIn)

        assertEquals("""{"orgs":[],"tags":["scholarship"],"mode":"AND","enabled":true}""", sent.single().body)
    }

    @Test
    fun `an edit is patched to the rule's id, its own id left out`() = runTest {
        answer(200, """{"id":3,"name":"Scholarships","orgs":["office"],"tags":["scholarship"],"mode":"OR","enabled":false,"revision":2}""")

        val saved = client.updateSubscription(3, rule, signIn)

        val patch = sent.single()
        assertEquals("PATCH $BASE/bulletin-subscriptions/3", patch.line)
        assertEquals(listOf("Authorization" to "Bearer access-1"), patch.headers)
        assertEquals("application/json; charset=utf-8", patch.contentType)
        assertEquals("""{"name":"Scholarships","orgs":["office"],"tags":["scholarship"],"mode":"OR","enabled":false}""", patch.body)
        assertEquals(rule.copy(id = 3), saved)
    }

    @Test
    fun `a delete carries the empty body OkHttp's delete() sent`() = runTest {
        answer(204, "")

        client.deleteSubscription(3, signIn)

        val delete = sent.single()
        assertEquals("DELETE $BASE/bulletin-subscriptions/3", delete.line)
        assertEquals(listOf("Authorization" to "Bearer access-1"), delete.headers)
        assertEquals(null, delete.contentType)
        assertEquals(0L, delete.contentLength)
        assertEquals("", delete.body)
    }

    @Test
    fun `a refusal fails each call with its status and body`() = runTest {
        answer(422, """{"detail":"bad"}""")
        answer(409, """{"error":"subscription_conflict"}""")
        answer(404, """{"detail":"subscription not found"}""")
        answer(400, """{"detail":"device_id_required"}""")

        val failures = listOf(
            runCatching { client.createSubscription(rule, signIn) },
            runCatching { client.updateSubscription(3, rule, signIn) },
            runCatching { client.deleteSubscription(3, signIn) },
            runCatching { client.fetchSubscriptions(signIn) },
        ).map { it.exceptionOrNull() }

        assertTrue(failures.toString(), failures.all { it is BulletinApiException })
        assertEquals(
            listOf(
                """createSubscription failed: HTTP 422 {"detail":"bad"}""",
                """updateSubscription failed: HTTP 409 {"error":"subscription_conflict"}""",
                "deleteSubscription failed: HTTP 404",
                "GET $BASE/bulletin-subscriptions failed: HTTP 400",
            ),
            failures.map { it?.message },
        )
    }

    private companion object {
        const val BASE = "http://127.0.0.1:9/v3"
    }
}
