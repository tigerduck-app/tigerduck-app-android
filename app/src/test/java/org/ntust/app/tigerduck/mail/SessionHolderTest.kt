@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `a dead held connection is replaced by a fresh one before the block runs`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        holder.use(creds) { it.listFolders() }
        server.nextCallError = MailError.Network()
        assertEquals(5, holder.use(creds) { it.listFolders().size })
        assertEquals(2, server.opens)
    }

    @Test
    fun `an error partway through a block on a held connection is surfaced without retrying`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        holder.use(creds) { it.listFolders() }
        // First call after this is the liveness probe (let it through with null),
        // the second is the caller's own block, which should fail exactly once.
        server.queueCallErrors(null, MailError.Network())
        var blockRuns = 0
        val result = runCatching {
            holder.use(creds) { session ->
                blockRuns++
                session.listFolders()
            }
        }
        assertEquals(true, result.exceptionOrNull() is MailError.Network)
        assertEquals(1, blockRuns)
        assertEquals(1, server.opens)
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

    @Test
    fun `use after release still closes on the next idle timeout`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope, idleMillis = 1_000)
        holder.use(creds) { it.listFolders() }
        holder.releaseLater()
        advanceTimeBy(500); runCurrent()
        assertEquals(1, server.openSessions)
        holder.use(creds) { it.status("INBOX") }
        advanceTimeBy(500); runCurrent()
        assertEquals(1, server.openSessions)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `an error that doesn't indict the connection leaves it open`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        holder.use(creds) { it.listFolders() }
        // null lets the liveness probe through; the actual block gets the Protocol error.
        server.queueCallErrors(null, MailError.Protocol("no"))
        runCatching { holder.use(creds) { it.listFolders() } }
        assertEquals(1, server.openSessions)
        assertEquals(1, server.opens)
    }

    @Test
    fun `a release survives a call that throws without closing the connection`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope, idleMillis = 1_000)
        holder.use(creds) { it.listFolders() }
        holder.releaseLater()
        // null lets the liveness probe through; the actual block gets a Protocol
        // error, which never closes the connection (see the test above) -- the
        // idle close cancelled to run this call must still get re-armed.
        server.queueCallErrors(null, MailError.Protocol("folder changed"))
        runCatching { holder.use(creds) { it.listFolders() } }
        assertEquals(1, server.openSessions)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `hold cancels a pending idle close and stops it from re-arming`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope, idleMillis = 1_000)
        holder.use(creds) { it.listFolders() }
        holder.releaseLater()
        holder.hold()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, server.openSessions)
    }
}
