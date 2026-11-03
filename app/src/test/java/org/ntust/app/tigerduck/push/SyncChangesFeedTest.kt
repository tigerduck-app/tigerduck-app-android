package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The change feed a revision poll reads ([SyncApiClient.fetchChanges]),
 * tigerduck-app-android#120: one page after the asked revision, or null once
 * the server no longer keeps those changes (a 409 `sync_revision_expired`).
 */
class SyncChangesFeedTest {

    private val backend = ScriptedBackend(under = "/v4")
    private lateinit var client: SyncApiClient

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-feed-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        client = SyncApiClient(AuthTokenManager(tokens, backend.client, prefs, "device-1"), prefs, backend.client)
    }

    @Test
    fun `a page after the asked revision is read as the server sends it`() = runTest {
        backend.answer(
            FEED, 200,
            """{"current_revision":7,"returned_until_revision":7,"has_more":false,
               "changes":[{"revision":6,"entity_type":"assignment","entity_id":"1","operation":"upsert","payload":{}},
                          {"revision":7,"entity_type":"course_override","entity_id":"2","operation":"upsert","payload":{}}]}""",
        )

        val page = client.fetchChanges(sinceRevision = 5, testSignIn())

        assertEquals(
            SyncChangesPage(7, 7, hasMore = false, listOf(SyncChange(6, "assignment"), SyncChange(7, "course_override"))),
            page,
        )
        val asked = backend.received(FEED).single()
        assertEquals("since_revision=5", asked.query)
        assertEquals("Bearer access", asked.authorization)
    }

    @Test
    fun `another refusal says which`() = runTest {
        backend.answer(FEED, 503)

        val failure = runCatching { client.fetchChanges(sinceRevision = 5, testSignIn()) }.exceptionOrNull()

        assertEquals(503, (failure as? PushApiException)?.statusCode)
    }

    @Test
    fun `a page missing a field or with an unreadable change reads as one the poll does not pass over`() {
        val page = parseChangesPage(JSONObject("""{"current_revision":7,"returned_until_revision":7,"changes":[null,{"revision":7}]}"""))

        assertTrue("no has_more reads as more to come", page.hasMore)
        assertEquals(listOf(SyncChange(0, ""), SyncChange(7, "")), page.changes)
    }

    /**
     * `/v4` answers a pruned revision with a 409, keeping every 410 for "update the app"
     * (`docs/api-v4.md`, "GET /sync"); the app makes a full sync.
     */
    @Test
    fun `the v4 feed's 409 sync_revision_expired is changes it no longer keeps`() = runTest {
        val v4 = RecordingBackend().apply { answer("/v4/sync", 409, syncFixture("v4/feed-expired.json")) }

        assertNull(syncApiClientAt("http://127.0.0.1:9/v4", v4.client).fetchChanges(sinceRevision = 5, testSignIn()))
        assertEquals(listOf("http://127.0.0.1:9/v4/sync?since_revision=5"), v4.sent.map { it.url })
    }

    @Test
    fun `another v4 409 or a 410 on v4 is a failure with its status`() = runTest {
        val v4 = RecordingBackend().apply {
            answer("/v4/sync", 409, syncFixture("v4/error-clock-ahead.json"))
            answer("/v4/sync", 409, """{"detail":"sync_revision_expired_soon"}""")
            answer("/v4/sync", 410, syncFixture("v4/error-api-retired.json"))
        }
        val client = syncApiClientAt("http://127.0.0.1:9/v4", v4.client)

        val clockAhead = runCatching { client.fetchChanges(sinceRevision = 5, testSignIn()) }.exceptionOrNull()
        val unknown = runCatching { client.fetchChanges(sinceRevision = 5, testSignIn()) }.exceptionOrNull()
        val retired = runCatching { client.fetchChanges(sinceRevision = 5, testSignIn()) }.exceptionOrNull()

        assertEquals(409, (clockAhead as? PushApiException)?.statusCode)
        assertEquals(409, (unknown as? PushApiException)?.statusCode)
        // The poll takes a failed read for a full sync as it takes a pruned revision; the update
        // latch reads a 410 apart.
        assertEquals(410, (retired as? PushApiException)?.statusCode)
    }

    /**
     * Only a 409 says which with its code. Any other failure is reported as it comes, its
     * body unread, so a slow body never holds back a failure the status already told.
     */
    @Test
    fun `a failure other than a 409 is reported without reading its body`() = runTest {
        var read = false
        val body = object : ResponseBody() {
            private val source: BufferedSource by lazy {
                object : ForwardingSource(Buffer().writeUtf8("""{"error":"sync_revision_expired"}""")) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        read = true
                        return super.read(sink, byteCount)
                    }
                }.buffer()
            }

            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val unavailable = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("").body(body).build()
        }.build()

        val failure = runCatching {
            syncApiClientAt("http://127.0.0.1:9/v4", unavailable).fetchChanges(sinceRevision = 5, testSignIn())
        }.exceptionOrNull()

        assertEquals(503, (failure as? PushApiException)?.statusCode)
        assertFalse("the failure's body was read", read)
    }

    private companion object {
        const val FEED = "GET /sync"
    }
}
