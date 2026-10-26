package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A request the backend answers with a 401 for a session it ended early goes once more after a
 * sign-in, rather than failing until the access token runs out.
 */
class SessionRefusalTest {

    private var relogins = 0

    private fun relogin(succeeds: Boolean): suspend () -> Boolean = {
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

    @Test
    fun `a refused session signs in again and the request goes once more`() = runTest {
        var sends = 0

        val answer = retryingAfterRelogin(relogin(succeeds = true)) {
            if (++sends == 1) throw IOException("HTTP 401") else "snapshot"
        }

        assertEquals("snapshot", answer)
        assertEquals(2, sends)
        assertEquals(1, relogins)
    }

    @Test
    fun `a refused session whose sign-in fails keeps its failure`() = runTest {
        val refused = IOException("HTTP 401")

        val failure = runCatching { retryingAfterRelogin(relogin(succeeds = false)) { throw refused } }.exceptionOrNull()

        assertSame(refused, failure)
        assertEquals(1, relogins)
    }

    @Test
    fun `another failure signs nothing in`() = runTest {
        val failure = runCatching {
            retryingAfterRelogin(relogin(succeeds = true)) { throw IOException("HTTP 503") }
        }.exceptionOrNull()

        assertEquals("HTTP 503", failure?.message)
        assertEquals(0, relogins)
    }

    @Test
    fun `a second refusal after the sign-in fails rather than signing in again`() = runTest {
        var sends = 0

        val failure = runCatching {
            retryingAfterRelogin(relogin(succeeds = true)) {
                sends++
                throw IOException("HTTP 401")
            }
        }.exceptionOrNull()

        assertEquals("HTTP 401", failure?.message)
        assertEquals(2, sends)
        assertEquals(1, relogins)
    }
}
