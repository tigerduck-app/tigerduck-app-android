package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

/**
 * The change feed a revision poll reads ([SyncApiClient.fetchChanges]),
 * tigerduck-app-android#120: one page after the asked revision, or null once
 * the server no longer keeps those changes.
 */
class SyncChangesFeedTest {

    private val backend = ScriptedBackend()
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

        val page = client.fetchChanges(sinceRevision = 5)

        assertEquals(
            SyncChangesPage(7, 7, hasMore = false, listOf(SyncChange(6, "assignment"), SyncChange(7, "course_override"))),
            page,
        )
        val asked = backend.received(FEED).single()
        assertEquals("since_revision=5", asked.query)
        assertEquals("Bearer access", asked.authorization)
    }

    @Test
    fun `changes the server no longer keeps read as none`() = runTest {
        backend.answer(FEED, 410, """{"error":"sync_revision_expired","full_sync_required":true}""")

        assertNull(client.fetchChanges(sinceRevision = 5))
    }

    @Test
    fun `another refusal says which`() = runTest {
        backend.answer(FEED, 503)

        val failure = runCatching { client.fetchChanges(sinceRevision = 5) }.exceptionOrNull()

        assertEquals(503, (failure as? PushApiException)?.statusCode)
    }

    @Test
    fun `a page missing a field or with an unreadable change reads as one the poll does not pass over`() {
        val page = parseChangesPage(JSONObject("""{"current_revision":7,"returned_until_revision":7,"changes":[null,{"revision":7}]}"""))

        assertTrue("no has_more reads as more to come", page.hasMore)
        assertEquals(listOf(SyncChange(0, ""), SyncChange(7, "")), page.changes)
    }

    private companion object {
        const val FEED = "GET /sync"
    }
}
