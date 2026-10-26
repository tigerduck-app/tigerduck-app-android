@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    /** A call no longer wanted opens nothing: the open would log in with the caller's credentials. */
    @Test
    fun `a call no longer wanted neither opens a connection nor runs`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        var blockRuns = 0
        val result = runCatching { holder.use(creds, stillWanted = { false }) { blockRuns++ } }
        assertEquals(true, result.exceptionOrNull() is MailError.Abandoned)
        assertEquals(0, blockRuns)
        assertEquals(0, server.opens)
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

    /**
     * The mail the user opens goes out next, not after the background download's next batch: a
     * call from outside an [SessionHolder.ahead] block waits for the block to end, and does not get
     * in between two of its calls either.
     */
    @Test
    fun `a call outside an ahead block waits for it to end, even between its calls`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        val sent = mutableListOf<String>()
        val between = CompletableDeferred<Unit>()
        val opened = launch {
            holder.ahead {
                holder.use(creds) { sent += "summary" }
                between.await()
                holder.use(creds) { sent += "body" }
            }
        }
        runCurrent()
        val batch = launch { holder.use(creds) { sent += "batch" } }
        runCurrent()
        assertEquals(listOf("summary"), sent)

        between.complete(Unit)
        opened.join()
        batch.join()
        assertEquals(listOf("summary", "body", "batch"), sent)
    }

    /** One that is cancelled, as leaving the mail does, lets the rest go on. */
    @Test
    fun `a cancelled ahead block lets the calls outside it go`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        val opened = launch { holder.ahead { awaitCancellation() } }
        runCurrent()
        val batch = async { holder.use(creds) { "batch" } }
        runCurrent()
        assertFalse(batch.isCompleted)

        opened.cancel()
        assertEquals("batch", batch.await())
    }

    /**
     * A call already in line for the connection when an ahead block starts steps back for it: the
     * download's next batch, queued behind a list refresh, does not go before the mail opened
     * meanwhile. Threads, as the calls block while they hold the connection.
     */
    @Test
    fun `a call already in line steps back for an ahead block that starts meanwhile`() = runTest {
        val holder = SessionHolder(server.factory(), backgroundScope)
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refresh = thread(isDaemon = true) {
            runBlocking { holder.use(creds) { holding.countDown(); release.await(); sent += "refresh" } }
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        val batch = thread(isDaemon = true) { runBlocking { holder.use(creds) { sent += "batch" } } }
        awaitParked(batch)
        val opened = thread(isDaemon = true) { runBlocking { holder.ahead { holder.use(creds) { sent += "opened" } } } }
        awaitParked(opened)

        release.countDown()
        listOf(refresh, batch, opened).forEach { it.join(5_000) }
        assertEquals(listOf("refresh", "opened", "batch"), sent)
    }

    /** Until [t], a `runBlocking` thread, has suspended: here, in line for the connection. */
    private fun awaitParked(t: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (t.state != Thread.State.WAITING && t.state != Thread.State.TIMED_WAITING) {
            assertTrue("${t.name} never waited", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }
}
