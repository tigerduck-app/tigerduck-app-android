package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.util.testSignIn

/**
 * What the snapshot, the change feed and the revision poll send, byte for byte: the method, the
 * address, every header and the body, through [ApiVersionInterceptor] as the shared client sends
 * them. `/v4` keeps `/v3`'s requests for all three; reading the answers is the parsers' own.
 */
class SyncApiRequestsTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v4 }))
    private val client = syncApiClientAt(v4, backend.client)
    private val bearer = listOf("Authorization" to "Bearer access")

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, body)

    @Before fun openGate() = ApiVersionGate.resetForTesting()

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    @Test
    fun `the snapshot is a bare GET with the session's header`() = runTest {
        backend.answer("/v4/sync/full", 200, syncFixture("v4/full.json"))

        client.fetchFullSync(testSignIn())

        assertEquals(listOf("GET", "$v4/sync/full", bearer, null), backend.sent.single().shape())
    }

    @Test
    fun `the change feed asks after the revision, and nothing more`() = runTest {
        backend.answer("/v4/sync", 200, """{"current_revision":7,"returned_until_revision":7,"has_more":false,"changes":[]}""")

        client.fetchChanges(sinceRevision = 5, testSignIn())

        assertEquals(listOf("GET", "$v4/sync?since_revision=5", bearer, null), backend.sent.single().shape())
    }

    @Test
    fun `the revision poll is a bare GET too`() = runTest {
        backend.answer("/v4/sync/revision", 200, """{"revision":7}""")

        assertEquals(7L, client.fetchRevision(testSignIn()))
        assertEquals(listOf("GET", "$v4/sync/revision", bearer, null), backend.sent.single().shape())
    }

    @Test
    fun `a failed snapshot says its status in its message, and goes once`() = runTest {
        backend.answer("/v4/sync/full", 401, """{"error":"session_revoked","message":"The session has ended; sign in again."}""")

        val failure = runCatching { client.fetchFullSync(testSignIn()) }.exceptionOrNull()

        assertEquals("sync/full failed: HTTP 401", (failure as? PushApiException)?.message)
        assertEquals(1, backend.sent.size)
    }

    @Test
    fun `a feed 409 that names no expired revision is a failure with its status`() = runTest {
        backend.answer("/v4/sync", 409, """{"error":"refused","message":"made up"}""")

        val failure = runCatching { client.fetchChanges(sinceRevision = 5, testSignIn()) }.exceptionOrNull()

        assertEquals(409, (failure as? PushApiException)?.statusCode)
    }
}
