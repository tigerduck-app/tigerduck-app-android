package org.ntust.app.tigerduck.sync.outbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.EARLIEST_CLOCK_US

/**
 * One channel's lane over a queue file in memory and a backend that answers when told: one request
 * in flight, never cut short by its caller, what each answer does to the edit, and the sign-in
 * guard (Review Focus 5).
 */
class OutboxLaneTest {

    private val series = SignInSeries()
    private val ack = AckClock()
    private val backend = FakeBackend()
    private val clockUs = EARLIEST_CLOCK_US + 1_000_000

    private fun TestScope.lane(store: FakeStore, policy: LanePolicy<Edit> = LanePolicy(Edit::field, 5, onDisk())) =
        OutboxLane(OutboxChannelId.MARKS, store, policy, ack, backgroundScope, backend::send)

    @Test
    fun `one request is in flight at a time, and a second drain waits for it`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b")))
        val lane = lane(store)
        val signIn = series.current

        val first = launch { lane.drain(signIn) }
        runCurrent()
        val second = launch { lane.drain(signIn) }
        runCurrent()
        assertEquals(listOf("a"), backend.values)

        backend.reply(SendResult.Sent(null))
        runCurrent()
        assertEquals(listOf("a", "b"), backend.values)
        backend.reply(SendResult.Sent(null))
        first.join()
        second.join()

        assertEquals("each edit once", listOf("a", "b"), backend.values)
        assertEquals(1, backend.mostInFlight)
        assertEquals(emptyList<Edit>(), store.entries)
    }

    @Test
    fun `an edit made while its field's request is in flight is kept at once and goes after the reply`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "old", madeAtMs = 1)))
        val lane = lane(store)
        val signIn = series.current
        val draining = launch { lane.drain(signIn) }
        runCurrent()

        // Kept while the request is still out: only requests wait.
        assertTrue(lane.enqueue(Edit("f1", "new", madeAtMs = 2), signIn))
        assertEquals(listOf(Edit("f1", "new", madeAtMs = 2)), store.entries)

        backend.reply(SendResult.Sent(clockUs))
        runCurrent()
        // The reply answered the older edit: the newer stays, and goes now.
        assertEquals(listOf("old", "new"), backend.values)
        assertEquals(listOf(Edit("f1", "new", madeAtMs = 2)), store.entries)
        backend.reply(SendResult.Sent(clockUs + 1))
        draining.join()

        assertEquals(emptyList<Edit>(), store.entries)
        assertEquals(clockUs + 1, ack.lastAck("f1"))
    }

    @Test
    fun `a caller cancelled during its request leaves the request to finish, and its answer is stored`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b")))
        val lane = lane(store)
        val caller = launch { lane.drain(series.current) }
        runCurrent()

        caller.cancelAndJoin()
        backend.reply(SendResult.Sent(clockUs))
        runCurrent()

        assertEquals("the request was not cut short", 0, backend.cancelled)
        assertEquals(listOf(Edit("f2", "b")), store.entries)
        assertEquals(clockUs, ack.lastAck("f1"))
        // The caller is gone, and its drain with it.
        assertEquals(listOf("a"), backend.values)
    }

    @Test
    fun `a caller cancelled while another's request is in flight sends nothing`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val signIn = series.current
        val first = launch { lane.drain(signIn, fields = setOf("f1")) }
        runCurrent()
        lane.enqueue(Edit("f2", "b"), signIn)
        val waiting = launch { lane.drain(signIn) }
        runCurrent()

        waiting.cancelAndJoin()
        backend.reply(SendResult.Sent(null))
        first.join()
        runCurrent()

        assertEquals(listOf("a"), backend.values)
        assertEquals(listOf(Edit("f2", "b")), store.entries)
    }

    @Test
    fun `the oldest edit goes first, and a field's newer edit takes the place its waiting edit had`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b")))
        val lane = lane(store)
        val signIn = series.current
        backend.answer = { SendResult.Sent(null) }

        lane.enqueue(Edit("f3", "c"), signIn)
        lane.enqueue(Edit("f1", "a again", madeAtMs = 2), signIn)
        assertEquals(listOf("a again", "b", "c"), store.entries.map { it.value })
        lane.drain(signIn)

        assertEquals(listOf("a again", "b", "c"), backend.values)
    }

    @Test
    fun `a drain of some fields sends only theirs`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b"), Edit("f3", "c")))
        val lane = lane(store)
        backend.answer = { SendResult.Sent(null) }

        lane.drain(series.current, fields = setOf("f2", "f3"))

        assertEquals(listOf("b", "c"), backend.values)
        assertEquals(listOf(Edit("f1", "a")), store.entries)
    }

    @Test
    fun `a sent edit leaves the queue, its reply's clock recorded for its field`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b"), Edit("f3", "c")))
        val lane = lane(store)
        backend.answer = {
            when (it.field) {
                "f1" -> SendResult.Sent(clockUs + 1)
                "f2" -> SendResult.Sent(clockUs + 2)
                else -> SendResult.Sent(null)
            }
        }

        lane.drain(series.current)

        assertEquals(emptyList<Edit>(), store.entries)
        assertEquals(clockUs + 1, ack.lastAck("f1"))
        assertEquals(clockUs + 2, ack.lastAck("f2"))
        assertNull("a reply with no clock acknowledges nothing", ack.lastAck("f3"))
    }

    @Test
    fun `the reply's clock is recorded before its edit leaves the queue`() = runTest {
        val acknowledgedAtRemoval = mutableListOf<Long?>()
        val store = object : LaneStore<Edit> {
            var entries = listOf(Edit("f1", "a"))

            override suspend fun load() = entries

            override suspend fun save(entries: List<Edit>, signIn: SignIn): Boolean {
                acknowledgedAtRemoval += ack.lastAck("f1")
                this.entries = entries
                return true
            }
        }
        backend.answer = { SendResult.Sent(clockUs) }

        OutboxLane(OutboxChannelId.MARKS, store, LanePolicy(Edit::field, 5, onDisk()), ack, backgroundScope, backend::send)
            .drain(series.current)

        assertEquals(listOf<Long?>(clockUs), acknowledgedAtRemoval)
        assertEquals(emptyList<Edit>(), store.entries)
    }

    @Test
    fun `an edit not sent, or failed for no fault of its own, stays as it was`() = runTest {
        for (result in listOf(SendResult.NotSent, SendResult.Retry)) {
            val store = FakeStore(listOf(Edit("f1", "a")))
            val lane = lane(store)
            backend.sent.clear()
            backend.answer = { result }

            repeat(2) { lane.drain(series.current) }

            assertEquals("$result: once per drain", 2, backend.sent.size)
            assertEquals("$result", listOf(Edit("f1", "a")), store.entries)
            assertEquals("$result", emptyList<Any>(), store.saves)
            assertNull("$result", ack.lastAck("f1"))
        }
    }

    @Test
    fun `a send that throws keeps its edit as it was`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        backend.answer = { throw IllegalStateException("the channel's own bug") }

        lane.drain(series.current)

        assertEquals(listOf(Edit("f1", "a")), store.entries)
        assertEquals(emptyList<Any>(), store.saves)
    }

    @Test
    fun `a send the channel cut short itself keeps its edit, and the drain goes on`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b")))
        val lane = lane(store)
        // As a withTimeout of the channel's own: a CancellationException, with the lane's scope still active.
        backend.answer = { if (it.field == "f1") throw CancellationException("timed out") else SendResult.Sent(null) }

        lane.drain(series.current)

        assertEquals(listOf("a", "b"), backend.values)
        assertEquals(listOf(Edit("f1", "a")), store.entries)
    }

    @Test
    fun `each refusal counts one attempt, and the edit parks at the lane's limit until it is given one more try`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store, LanePolicy(Edit::field, 3, onDisk()))
        val signIn = series.current
        backend.answer = { SendResult.Refused }

        lane.drain(signIn)
        assertEquals("one try per drain", 1, backend.sent.size)
        assertEquals(1, store.entries.single().attempts)
        repeat(2) { lane.drain(signIn) }
        assertEquals(3, backend.sent.size)
        assertEquals(Edit("f1", "a", attempts = 3, refusedAtMs = 99L), store.entries.single())

        lane.drain(signIn)
        assertEquals("parked", 3, backend.sent.size)
        assertEquals("kept at this phone's value", setOf("f1"), lane.heldFields())

        lane.requeueParked(signIn)
        assertEquals(2, store.entries.single().attempts)
        lane.drain(signIn)
        assertEquals(4, backend.sent.size)
        lane.drain(signIn)
        assertEquals("parked again after its one more try", 4, backend.sent.size)
    }

    @Test
    fun `an edit waiting for a course upload parks at once, and goes again once one has landed`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val signIn = series.current
        backend.answer = { if (backend.sent.size == 1) SendResult.WaitsForUpload else SendResult.Sent(null) }

        lane.drain(signIn)
        lane.drain(signIn)
        assertEquals(1, backend.sent.size)
        assertEquals(5, store.entries.single().attempts)

        lane.requeueParked(signIn)
        lane.drain(signIn)
        assertEquals(2, backend.sent.size)
        assertEquals(emptyList<Edit>(), store.entries)
    }

    @Test
    fun `a parked edit the requeue does not find due stays parked`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a", attempts = 5), Edit("f2", "b", attempts = 5)))
        val lane = lane(store)
        backend.answer = { SendResult.Sent(null) }

        lane.requeueParked(series.current) { it.field == "f2" }
        lane.drain(series.current)

        assertEquals(listOf("b"), backend.values)
        assertEquals(listOf(Edit("f1", "a", attempts = 5)), store.entries)
    }

    @Test
    fun `counted in memory, refusals park the edit for this process only, and a newer edit starts from none`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store, LanePolicy(Edit::field, 2, InMemoryAttempts(Edit::field)))
        val signIn = series.current
        backend.answer = { SendResult.Refused }

        repeat(3) { lane.drain(signIn) }
        assertEquals(2, backend.sent.size)
        assertEquals("nothing counted on disk", emptyList<Any>(), store.saves)

        lane.requeueParked(signIn)
        repeat(2) { lane.drain(signIn) }
        assertEquals(3, backend.sent.size)

        lane.forgetMemory()
        lane.drain(signIn)
        assertEquals("forgotten, counted from none", 4, backend.sent.size)

        lane.drain(signIn)
        lane.enqueue(Edit("f1", "b", madeAtMs = 2), signIn)
        lane.drain(signIn)
        assertEquals(listOf("a", "a", "a", "a", "a", "b"), backend.values)
    }

    @Test
    fun `a lane that stops at a failed request leaves the rest for the next drain`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b")))
        backend.answer = { SendResult.Retry }

        lane(store, LanePolicy(Edit::field, 5, onDisk(), stopsOnRetry = true)).drain(series.current)
        assertEquals(listOf("a"), backend.values)
        lane(store).drain(series.current)
        assertEquals("one that goes on tries each", listOf("a", "a", "b"), backend.values)
    }

    @Test
    fun `a refusal of an edit replaced while it was in flight counts against neither`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "old", madeAtMs = 1)))
        val lane = lane(store)
        val signIn = series.current
        val draining = launch { lane.drain(signIn) }
        runCurrent()
        lane.enqueue(Edit("f1", "new", madeAtMs = 2), signIn)

        backend.reply(SendResult.Refused)
        runCurrent()
        assertEquals(listOf(Edit("f1", "new", madeAtMs = 2)), store.entries)
        backend.reply(SendResult.Sent(null))
        draining.join()
        assertEquals(emptyList<Edit>(), store.entries)
    }

    @Test
    fun `an entry read under an ended sign-in is neither sent nor rewritten`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a", attempts = 5), Edit("f2", "b")))
        val lane = lane(store)
        val ended = series.current
        series.end()

        lane.drain(ended)
        lane.requeueParked(ended)
        assertFalse(lane.enqueue(Edit("f3", "c"), ended))
        assertFalse(lane.edit(ended) { emptyList() })
        assertFalse("nothing holds for an ended sign-in, even a change of nothing", lane.edit(ended) { it })

        assertEquals(emptyList<Any>(), backend.sent)
        assertEquals(emptyList<Any>(), store.saves)
        assertEquals(listOf(Edit("f1", "a", attempts = 5), Edit("f2", "b")), store.entries)
    }

    @Test
    fun `a drain under an ended sign-in returns at once, without waiting for a request in flight`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val first = series.current
        val draining = launch { lane.drain(first) }
        runCurrent()
        series.end()
        series.open()

        // A sync the sign-out overtook, starting its drain late: nothing of the first student's to send.
        val stale = launch { lane.drain(first) }
        runCurrent()

        assertTrue("it did not wait for the request in flight", stale.isCompleted)
        backend.reply(SendResult.Sent(null))
        draining.join()
        assertEquals(listOf("a"), backend.values)
    }

    @Test
    fun `a refusal stored while a sign-out forgets the counts does not bring them back for the next student`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store, LanePolicy(Edit::field, 1, InMemoryAttempts(Edit::field)))
        val first = series.current
        val draining = launch { lane.drain(first) }
        runCurrent()

        // The refusal's storing reads the queue file, and the sign-out lands during that read: the
        // sign-in ends and the outbox forgets what it counted (Outbox.forgetAccount).
        val reading = CompletableDeferred<Unit>()
        store.holdNextLoad = reading
        backend.reply(SendResult.Refused)
        runCurrent()
        series.end()
        lane.forgetMemory()
        series.open()
        reading.complete(Unit)
        draining.join()

        // The next student's edit, equal to the first's, has nothing counted against it.
        backend.answer = { SendResult.Sent(null) }
        lane.drain(series.current)
        assertEquals(listOf("a", "a"), backend.values)
    }

    /** Counts in memory, with a sign-out landing right after one read of a count, as one on another thread can. */
    private class SignOutAfterRead(private val book: InMemoryAttempts<Edit>) : AttemptBook<Edit> by book {
        var onRead: (() -> Unit)? = null

        override fun attempts(entry: Edit): Int = book.attempts(entry).also {
            onRead?.let { signOut ->
                onRead = null
                signOut()
            }
        }
    }

    @Test
    fun `a requeue whose sign-out lands as it reads a count does not bring the count back for the next student`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val counts = SignOutAfterRead(InMemoryAttempts(Edit::field))
        val lane = lane(store, LanePolicy(Edit::field, 2, counts))
        val first = series.current
        backend.answer = { SendResult.Refused }
        repeat(2) { lane.drain(first) }

        // The sign-out ends the sign-in and the outbox forgets what it counted (Outbox.forgetAccount).
        counts.onRead = {
            series.end()
            lane.forgetMemory()
            series.open()
        }
        lane.requeueParked(first)

        // The next student's equal edit has its two tries before it parks, as any edit has.
        repeat(3) { lane.drain(series.current) }
        assertEquals(4, backend.sent.size)
    }

    @Test
    fun `a reply that lands after its sign-in ended is not stored`() = runTest {
        for (result in listOf(SendResult.Sent(clockUs), SendResult.Refused, SendResult.WaitsForUpload)) {
            val series = SignInSeries()
            val store = FakeStore(listOf(Edit("f1", "a")))
            val lane = lane(store)
            val signIn = series.current
            val draining = launch { lane.drain(signIn) }
            runCurrent()

            // The sign-out has ended the sign-in; its wipe of the files has yet to run.
            series.end()
            backend.reply(result)
            draining.join()

            assertEquals("$result", listOf(Edit("f1", "a")), store.entries)
            assertEquals("$result", emptyList<Any>(), store.saves)
            assertNull("$result", ack.lastAck("f1"))
        }
    }

    @Test
    fun `a sign-out during a request, then another student - nothing of the first goes with the second, nor lands in the second's queue`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "first's a"), Edit("f2", "first's b")))
        val lane = lane(store)
        val first = series.current
        val draining = launch { lane.drain(first) }
        runCurrent()

        // The sign-out: the first student's queue goes with the files, and a second signs in and edits.
        series.end()
        store.entries = emptyList()
        series.open()
        val second = series.current
        assertTrue(lane.enqueue(Edit("f1", "second's"), second))
        backend.reply(SendResult.Sent(clockUs))
        draining.join()

        assertEquals(listOf(Edit("f1", "second's")), store.entries)
        assertTrue(store.saves.none { it.second === first })
        assertNull(ack.lastAck("f1"))

        backend.answer = { SendResult.Sent(null) }
        lane.drain(second)
        assertEquals(
            listOf("first's a" to first, "second's" to second),
            backend.sent.map { (edit, signIn) -> edit.value to signIn },
        )
        assertEquals(emptyList<Edit>(), store.entries)
    }
}
