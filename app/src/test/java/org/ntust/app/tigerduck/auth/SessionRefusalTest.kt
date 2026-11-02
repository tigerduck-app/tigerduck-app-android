package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SimulatedFailure
import org.ntust.app.tigerduck.util.testSignIn
import java.io.IOException

/**
 * A request the backend answers with a 401 for a session it ended early goes once more after a
 * sign-in, rather than failing until the access token runs out.
 */
class SessionRefusalTest {

    private var relogins = 0

    private fun relogin(succeeds: Boolean): suspend (SignIn) -> Boolean = {
        relogins++
        succeeds
    }

    @Test
    fun `a 401 or a revoked session reads as refused, anything else not`() {
        assertTrue(IOException("full sync failed: HTTP 401").isSessionRefused())
        assertTrue(IOException("HTTP 403 session_revoked").isSessionRefused())
        assertFalse(IOException("HTTP 503").isSessionRefused())
        assertFalse(IOException().isSessionRefused())
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
            if (++sends == 1) throw IOException("HTTP 401") else "snapshot"
        }

        assertEquals("snapshot", answer)
        assertEquals(2, sends)
        assertEquals(1, relogins)
    }

    @Test
    fun `a refused session whose sign-in fails keeps its failure`() = runTest {
        val refused = IOException("HTTP 401")

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
                throw IOException("HTTP 401")
            }
        }.exceptionOrNull()

        assertEquals("HTTP 401", failure?.message)
        assertEquals(2, sends)
        assertEquals(1, relogins)
    }
}
