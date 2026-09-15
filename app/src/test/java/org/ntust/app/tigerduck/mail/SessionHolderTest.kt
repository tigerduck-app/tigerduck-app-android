package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHolderTest {
    private val server = FakeMailServer()
    private val creds = MailCredentials("b10000001", "pw")

    @Test
    fun `reuses one connection and closes it after the idle delay`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope, idleMillis = 30_000)
        holder.use(creds) { it.listFolders() }
        holder.use(creds) { it.status("INBOX") }
        assertEquals(1, server.opens)
        holder.releaseLater()
        advanceTimeBy(29_000); runCurrent()
        assertEquals(1, server.openSessions)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `a stale held connection is retried once on a fresh one`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        holder.use(creds) { it.listFolders() }
        server.nextCallError = MailError.Network()
        assertEquals(5, holder.use(creds) { it.listFolders().size })
        assertEquals(2, server.opens)
    }

    @Test
    fun `a new connection that fails is not retried`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        server.nextCallError = MailError.Network()
        val result = runCatching { holder.use(creds) { it.listFolders() } }
        assertEquals(true, result.exceptionOrNull() is MailError.Network)
        assertEquals(1, server.opens)
        assertEquals(0, server.openSessions)
    }
}
