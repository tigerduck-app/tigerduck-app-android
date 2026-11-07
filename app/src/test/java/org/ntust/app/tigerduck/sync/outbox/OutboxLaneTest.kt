package org.ntust.app.tigerduck.sync.outbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        OutboxLane(OutboxChannelId.MARKS, store, policy, ack, backgroundScope, send = backend::send)

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

        OutboxLane(OutboxChannelId.MARKS, store, LanePolicy(Edit::field, 5, onDisk()), ack, backgroundScope, send = backend::send)
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
    fun `a requeue that does not ask for the edit in flight leaves it to its reply`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val signIn = series.current
        val draining = launch { lane.drain(signIn) }
        runCurrent()

        lane.requeueParked(signIn)
        backend.reply(SendResult.WaitsForUpload)
        draining.join()

        assertEquals(listOf(5), store.entries.map { it.attempts })
    }

    @Test
    fun `a requeue that asks for the edit in flight leaves it one more try though its reply would park it`() = runTest {
        for ((result, before) in listOf(SendResult.WaitsForUpload to 0, SendResult.Refused to 4)) {
            backend.sent.clear()
            backend.answer = { null }
            val store = FakeStore(listOf(Edit("f1", "a", attempts = before)))
            val lane = lane(store)
            val signIn = series.current
            val draining = launch { lane.drain(signIn) }
            runCurrent()

            lane.requeueParked(signIn, inFlightToo = true)
            backend.reply(result)
            draining.join()
            assertEquals("$result", listOf(4), store.entries.map { it.attempts })

            // One more try, and only one: the next reply that would park it parks it.
            backend.answer = { result }
            repeat(2) { lane.drain(signIn) }
            assertEquals("$result", listOf("a", "a"), backend.values)
            assertEquals("$result", listOf(5), store.entries.map { it.attempts })
        }
    }

    @Test
    fun `the edit in flight a requeue marks has its one more try though its caller is cancelled`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val signIn = series.current
        val caller = launch { lane.drain(signIn) }
        runCurrent()

        lane.requeueParked(signIn, inFlightToo = true)
        caller.cancelAndJoin()
        backend.reply(SendResult.WaitsForUpload)
        runCurrent()

        assertEquals(listOf(4), store.entries.map { it.attempts })
    }

    @Test
    fun `an edit in flight the requeue does not find due is left to its reply`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val signIn = series.current
        val draining = launch { lane.drain(signIn) }
        runCurrent()

        lane.requeueParked(signIn, inFlightToo = true) { it.field == "f2" }
        backend.reply(SendResult.WaitsForUpload)
        draining.join()

        assertEquals(listOf(5), store.entries.map { it.attempts })
    }

    @Test
    fun `a requeue's mark is the edit in flight's alone`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a"), Edit("f2", "b", attempts = 4)))
        val lane = lane(store)
        val signIn = series.current
        backend.answer = { if (it.field == "f1") null else SendResult.Refused }
        val draining = launch { lane.drain(signIn) }
        runCurrent()

        lane.requeueParked(signIn, inFlightToo = true)
        backend.reply(SendResult.Sent(null))
        draining.join()

        assertEquals(listOf("a", "b"), backend.values)
        assertEquals(listOf(Edit("f2", "b", attempts = 5, refusedAtMs = 99L)), store.entries)
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

    /**
     * Two drains under way at once: the one that sent a field's last edit, and a send now of a
     * newer edit of it, kept while the last was out. Whichever of them takes the newer edit, it is
     * tried once: an attempt counts for every drain under way then, as each tap sent only its own
     * edit before there was one lane.
     */
    @Test
    fun `an edit one drain tried is not tried again by another under way with it`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "old", madeAtMs = 1)))
        val lane = lane(store)
        val signIn = series.current
        backend.answer = { if (it.value == "new") SendResult.Retry else null }
        val draining = launch { lane.drain(signIn) }
        runCurrent()
        val sending = launch {
            lane.enqueue(Edit("f1", "new", madeAtMs = 2), signIn)
            lane.drain(signIn, setOf("f1"))
        }
        runCurrent()

        backend.reply(SendResult.Sent(null))
        draining.join()
        sending.join()

        assertEquals(listOf("old", "new"), backend.values)
        assertEquals(listOf(Edit("f1", "new", madeAtMs = 2)), store.entries)
    }

    /**
     * One after the other, never at once, as a queue that drains under a lock of its own does: each
     * drain is a fresh one, and tries a failed edit again.
     */
    @Test
    fun `drains one after another each try a failed edit once`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        backend.answer = { SendResult.Retry }

        lane.drain(series.current)
        lane.drain(series.current, setOf("f1"))
        lane.enqueueAndDrain(Edit("f1", "a"), series.current) { true }

        assertEquals(listOf("a", "a", "a"), backend.values)
    }

    /**
     * A drain of the first student's, waiting behind their request while they sign out and the
     * next student edits: once its sign-in has ended it neither sends nor counts anything as tried
     * for the next student's drain, which sends the edit.
     */
    @Test
    fun `a drain whose sign-in ended while it waited leaves the next student's edit to the next student's drain`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val first = series.current
        backend.answer = { if (it.value == "b") SendResult.Sent(null) else null }
        val sending = launch { lane.drain(first) }
        runCurrent()
        val waiting = launch { lane.drain(first) }
        runCurrent()
        // Signed out, the queue wiped, and the next student signs in and edits.
        series.end()
        store.entries = emptyList()
        series.open()
        val second = series.current
        assertTrue(lane.enqueue(Edit("f2", "b"), second))
        val next = launch { lane.drain(second) }
        runCurrent()

        backend.reply(SendResult.Sent(null))
        sending.join()
        waiting.join()
        next.join()

        assertEquals(listOf("a" to first, "b" to second), backend.sent.map { (edit, signIn) -> edit.value to signIn })
        assertEquals(emptyList<Edit>(), store.entries)
    }

    /**
     * The first student's drain past its sign-in check, its read of the queue slow, as the student
     * signs out and the next one's edit and drain arrive: what it reads it counts for drains of its
     * own sign-in only, so the next student's drain still sends the edit.
     */
    @Test
    fun `an edit read under an ended sign-in counts as tried for no drain of another sign-in`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "a")))
        val lane = lane(store)
        val first = series.current
        backend.answer = { SendResult.Sent(null) }
        val read = CompletableDeferred<Unit>()
        store.holdNextLoad = read
        val draining = launch { lane.drain(first) }
        runCurrent()
        // Signed out while its read waits: the queue wiped, then the next student's edit in it.
        series.end()
        store.entries = listOf(Edit("f2", "b"))
        series.open()
        val second = series.current
        val next = launch { lane.drain(second) }
        runCurrent()

        read.complete(Unit)
        draining.join()
        next.join()

        assertEquals(listOf("b" to second), backend.sent.map { (edit, signIn) -> edit.value to signIn })
    }

    /** "Tried" is kept by field: two fields' edits a channel's sameEdit calls alike are two edits. */
    @Test
    fun `edits of two fields that look alike each go`() = runTest {
        val store = FakeStore(listOf(Edit("f1", "x"), Edit("f2", "x")))
        val alike = OnDiskAttempts<Edit>(
            attempts = { it.attempts },
            counted = { e, n, _ -> e.copy(attempts = n) },
            sameEdit = { a, b -> a.value == b.value && a.madeAtMs == b.madeAtMs },
        )
        val lane = lane(store, LanePolicy(Edit::field, 5, alike))
        backend.answer = { SendResult.Retry }

        lane.drain(series.current)

        assertEquals(listOf("f1", "f2"), backend.sent.map { it.first.field })
    }

    @Test
    fun `a send now keeps its edit and sends its field, and under an ended sign-in does neither`() = runTest {
        val store = FakeStore(listOf(Edit("f2", "other")))
        val lane = lane(store)
        backend.answer = { SendResult.Sent(null) }
        val signIn = series.current

        assertTrue(lane.enqueueAndDrain(Edit("f1", "a"), signIn) { false })
        assertEquals("kept, not sent", emptyList<String>(), backend.values)
        assertTrue(lane.enqueueAndDrain(Edit("f1", "b"), signIn) { true })
        assertEquals("only its own field", listOf("b"), backend.values)

        val ended = series.end()
        assertFalse(lane.enqueueAndDrain(Edit("f3", "c"), ended) { true })
        assertEquals(listOf("b"), backend.values)
        assertEquals(listOf(Edit("f2", "other")), store.entries)
    }

    @Test
    fun `a send now of a channel's own write drains the fields it kept, and none when it kept none`() = runTest {
        val store = FakeStore(listOf(Edit("f2", "other")))
        val lane = lane(store)
        backend.answer = { SendResult.Sent(null) }
        val signIn = series.current

        assertFalse("kept nothing", lane.keepAndDrain(signIn, { true }) { null })
        assertFalse("kept no field", lane.keepAndDrain(signIn, { true }) { emptySet() })
        assertTrue(lane.keepAndDrain(signIn, { false }) { setOf("f1").takeIf { lane.enqueue(Edit("f1", "a"), signIn) } })
        assertEquals("kept, not sent", emptyList<String>(), backend.values)
        assertTrue(lane.keepAndDrain(signIn, { true }) { setOf("f1").takeIf { lane.enqueue(Edit("f1", "b"), signIn) } })

        assertEquals("only the fields it kept", listOf("b"), backend.values)
        assertEquals(listOf(Edit("f2", "other")), store.entries)
    }

    /**
     * As [enqueueAndDrain]'s, for a write of the channel's own: the edit is kept, and the drain under
     * way takes it and fails while the write has yet to return. The send now was under way from
     * before the write, so it does not send the edit again.
     */
    @Test
    fun `a send now of a channel's own write counts as under way from before the write`() = runBlocking {
        val store = FakeStore(listOf(Edit("f1", "old", madeAtMs = 1)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lane = OutboxLane(OutboxChannelId.MARKS, store, LanePolicy(Edit::field, 5, onDisk()), ack, scope, send = backend::send)
        val newTried = CountDownLatch(1)
        backend.answer = { edit -> if (edit.value == "new") SendResult.Retry.also { newTried.countDown() } else null }
        val signIn = series.current
        try {
            val draining = launch(Dispatchers.Default) { lane.drain(signIn) }
            // The old edit is out, its reply held.
            withTimeout(5_000) { while (backend.pending.isEmpty()) delay(1) }
            val kept = CountDownLatch(1)
            val goOn = CountDownLatch(1)
            val sending = launch(Dispatchers.IO) {
                lane.keepAndDrain(signIn, { true }) {
                    lane.enqueue(Edit("f1", "new", madeAtMs = 2), signIn)
                    kept.countDown()
                    goOn.await(5, TimeUnit.SECONDS)
                    setOf("f1")
                }
            }
            assertTrue(kept.await(5, TimeUnit.SECONDS))
            backend.reply(SendResult.Sent(null))
            assertTrue("the drain under way took the new edit", newTried.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { draining.join() }
            goOn.countDown()
            withTimeout(5_000) { sending.join() }

            assertEquals(listOf("old", "new"), backend.values)
        } finally {
            scope.cancel()
        }
    }

    /**
     * On threads of their own, as a tap and a sync run: the send now's edit is kept, and before it
     * has asked whether to send, the drain under way takes the edit and fails. The send now counted
     * as under way from before its edit was kept, so it does not send the edit again.
     */
    @Test
    fun `a send now counts as under way from before its edit is kept, whichever thread takes the edit`() = runBlocking {
        val store = FakeStore(listOf(Edit("f1", "old", madeAtMs = 1)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lane = OutboxLane(OutboxChannelId.MARKS, store, LanePolicy(Edit::field, 5, onDisk()), ack, scope, send = backend::send)
        val newTried = CountDownLatch(1)
        backend.answer = { edit -> if (edit.value == "new") SendResult.Retry.also { newTried.countDown() } else null }
        val signIn = series.current
        try {
            val draining = launch(Dispatchers.Default) { lane.drain(signIn) }
            // The old edit is out, its reply held.
            withTimeout(5_000) { while (backend.pending.isEmpty()) delay(1) }
            val kept = CountDownLatch(1)
            val goOn = CountDownLatch(1)
            val sending = launch(Dispatchers.IO) {
                lane.enqueueAndDrain(Edit("f1", "new", madeAtMs = 2), signIn) {
                    kept.countDown()
                    goOn.await(5, TimeUnit.SECONDS)
                    true
                }
            }
            assertTrue(kept.await(5, TimeUnit.SECONDS))
            backend.reply(SendResult.Sent(null))
            assertTrue("the drain under way took the new edit", newTried.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { draining.join() }
            goOn.countDown()
            withTimeout(5_000) { sending.join() }

            assertEquals(listOf("old", "new"), backend.values)
        } finally {
            scope.cancel()
        }
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
