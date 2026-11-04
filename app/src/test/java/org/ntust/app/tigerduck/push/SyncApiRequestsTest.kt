package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.util.testSignIn

/**
 * What the snapshot, the change feed and the revision poll send on `/v3`, byte for byte: the
 * method, the address, every header and the body, through [ApiVersionInterceptor] as the shared
 * client sends them. Reading the backend's answers moves; these stay.
 */
class SyncApiRequestsTest {

    // Pins /v3's requests and rules until Z1 and Z2 delete them: a base ending in /v3 stays there.
    @get:Rule
    val speaksV3 = SpeaksV3()

    private val v3 = "http://127.0.0.1:9/v3"
    private val backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val client = syncApiClientAt(v3, backend.client)
    private val bearer = listOf("Authorization" to "Bearer access")

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, body)

    @Before fun openGate() = ApiVersionGate.resetForTesting()

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    @Test
    fun `the snapshot is a bare GET with the session's header`() = runTest {
        backend.answer("/v3/sync/full", 200, syncFixture("v3/full.json"))

        client.fetchFullSync(testSignIn())

        assertEquals(listOf("GET", "$v3/sync/full", bearer, null), backend.sent.single().shape())
    }

    @Test
    fun `the change feed asks after the revision, and nothing more`() = runTest {
        backend.answer("/v3/sync", 200, """{"current_revision":7,"returned_until_revision":7,"has_more":false,"changes":[]}""")

        client.fetchChanges(sinceRevision = 5, testSignIn())

        assertEquals(listOf("GET", "$v3/sync?since_revision=5", bearer, null), backend.sent.single().shape())
    }

    @Test
    fun `the revision poll is a bare GET too`() = runTest {
        backend.answer("/v3/sync/revision", 200, """{"revision":7}""")

        assertEquals(7L, client.fetchRevision(testSignIn()))
        assertEquals(listOf("GET", "$v3/sync/revision", bearer, null), backend.sent.single().shape())
    }

    @Test
    fun `a failed snapshot says its status in its message, and goes once`() = runTest {
        backend.answer("/v3/sync/full", 401, """{"detail":"session_revoked"}""")

        val failure = runCatching { client.fetchFullSync(testSignIn()) }.exceptionOrNull()

        assertEquals("sync/full failed: HTTP 401", (failure as? PushApiException)?.message)
        assertEquals(1, backend.sent.size)
    }

    @Test
    fun `the v3 feed's 409, which it never sends, is a failure with its status`() = runTest {
        backend.answer("/v3/sync", 409, """{"detail":"refused"}""")

        val failure = runCatching { client.fetchChanges(sinceRevision = 5, testSignIn()) }.exceptionOrNull()

        assertEquals(409, (failure as? PushApiException)?.statusCode)
    }

    @Test
    fun `the v3 feed's 410 is changes it no longer keeps`() = runTest {
        backend.answer("/v3/sync", 410, """{"error":"sync_revision_expired","min_available_revision":40,"current_revision":52,"full_sync_required":true}""")

        assertNull(client.fetchChanges(sinceRevision = 5, testSignIn()))
    }
}
