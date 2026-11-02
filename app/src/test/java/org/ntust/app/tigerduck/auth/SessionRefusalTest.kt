package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.syncApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SimulatedFailure
import org.ntust.app.tigerduck.util.testSignIn
import java.io.IOException
import java.net.ConnectException

/**
 * A request the backend answers with a 401 for a session it ended early goes once more after a
 * sign-in, rather than failing until the access token runs out.
 */
class SessionRefusalTest {

    private var relogins = 0

    /** What the snapshot's fetch throws for a 401. */
    private fun refused() = PushApiException("sync/full failed: HTTP 401", 401)

    private fun relogin(succeeds: Boolean): suspend (SignIn) -> Boolean = {
        relogins++
        succeeds
    }

    @Test
    fun `a 401 or a revoked session reads as refused, anything else not`() {
        assertTrue(PushApiException("sync/full failed: HTTP 401", 401).isSessionRefused())
        assertTrue(BackendException(BackendError(401, "session_revoked"), "refused").isSessionRefused())
        assertFalse(PushApiException("sync/full failed: HTTP 503", 503).isSessionRefused())
        assertFalse(IOException("HTTP 503").isSessionRefused())
        assertFalse(IOException().isSessionRefused())
    }

    /**
     * Only the backend's answer says a session is refused, never a failure's words: Android's
     * org.json quotes the text it could not read, and a snapshot cut short holds revisions, ids
     * and course numbers, any of which can read 401; a failed connection names an address.
     * Each of these sent the request a second time.
     */
    @Test
    fun `a failure that only quotes 401 or session_revoked is not refused, and goes once`() = runTest {
        val quoting = listOf(
            JSONException("Unterminated object at character 31 of {\"current_revision\": 401, \"courses\""),
            ConnectException("Failed to connect to api.example.test/2001:db8::a401:443"),
            IOException("HTTP 403 session_revoked"),
            PushApiException("register failed: HTTP 500 {\"detail\":\"session_revoked\"}"),
        )
        for (failure in quoting) {
            var sends = 0

            val thrown = runCatching {
                retryingAfterRelogin(testSignIn(), relogin(succeeds = true)) {
                    sends++
                    throw failure
                }
            }.exceptionOrNull()

            assertFalse("$failure", failure.isSessionRefused())
            assertSame(failure, thrown)
            assertEquals("$failure", 1, sends)
        }
        assertEquals(0, relogins)
    }

    /** What the snapshot's fetch throws for [status] and [body], at an endpoint ending in [version]. */
    private suspend fun snapshotFailure(version: String, status: Int, body: String): Throwable {
        val backend = RecordingBackend().apply { answer("/$version/sync/full", status, body) }
        val sync = syncApiClientAt("http://127.0.0.1:9/$version", backend.client)
        return runCatching { sync.fetchFullSync(testSignIn()) }.exceptionOrNull()!!
    }

    @Test
    fun `a snapshot refused with a 401 reads as refused, whichever version's body says why`() = runTest {
        val refusals = listOf(
            "v3" to """{"detail":"session_revoked"}""",
            "v3" to """{"detail":"invalid_access_token"}""",
            "v3" to "",
            "v4" to syncFixture("v4/error-session-user-mismatch.json"),
            "v4" to """{"error":"session_revoked","message":"The session has ended; sign in again."}""",
            "v4" to "",
        )
        for ((version, body) in refusals) {
            assertTrue("$version $body", snapshotFailure(version, 401, body).isSessionRefused())
        }
        assertFalse(snapshotFailure("v3", 403, """{"detail":"session_revoked"}""").isSessionRefused())
        assertFalse(snapshotFailure("v4", 503, """{"error":"moodle_unreachable","message":"Moodle did not answer."}""").isSessionRefused())
    }

    /** A debug build's simulated backend 401 still takes the relogin path, as a real one does. */
    @Test
    fun `the debug simulator's backend 401 reads as refused, and its other failures not`() = runTest {
        suspend fun simulated(failure: SimulatedFailure): Throwable {
            ServerFailureSimulator.setFailure(failure, ServerKind.BACKEND)
            return runCatching { ServerFailureSimulator.check(ServerKind.BACKEND) {} }.exceptionOrNull()!!
        }
        try {
            val refused = simulated(SimulatedFailure.HTTP_401)

            assertTrue(refused.isSessionRefused())
            assertTrue("still an IO failure, got $refused", refused is IOException)
            assertEquals("Simulated HTTP 401 for BACKEND", refused.message)
            for (other in listOf(SimulatedFailure.HTTP_500, SimulatedFailure.HTTP_403, SimulatedFailure.UNREACHABLE)) {
                assertFalse(other.name, simulated(other).isSessionRefused())
            }
        } finally {
            ServerFailureSimulator.resetAll()
        }
    }

    @Test
    fun `a refused session signs in again and the request goes once more`() = runTest {
        var sends = 0

        val answer = retryingAfterRelogin(testSignIn(), relogin(succeeds = true)) {
            if (++sends == 1) throw refused() else "snapshot"
        }

        assertEquals("snapshot", answer)
        assertEquals(2, sends)
        assertEquals(1, relogins)
    }

    @Test
    fun `a refused session whose sign-in fails keeps its failure`() = runTest {
        val refused = refused()

        val failure = runCatching { retryingAfterRelogin(testSignIn(), relogin(succeeds = false)) { throw refused } }.exceptionOrNull()

        assertSame(refused, failure)
        assertEquals(1, relogins)
    }

    @Test
    fun `another failure signs nothing in`() = runTest {
        val failure = runCatching {
            retryingAfterRelogin(testSignIn(), relogin(succeeds = true)) { throw IOException("HTTP 503") }
        }.exceptionOrNull()

        assertEquals("HTTP 503", failure?.message)
        assertEquals(0, relogins)
    }

    @Test
    fun `a second refusal after the sign-in fails rather than signing in again`() = runTest {
        var sends = 0

        val failure = runCatching {
            retryingAfterRelogin(testSignIn(), relogin(succeeds = true)) {
                sends++
                throw refused()
            }
        }.exceptionOrNull()

        assertEquals(401, (failure as? PushApiException)?.statusCode)
        assertEquals(2, sends)
        assertEquals(1, relogins)
    }
}
