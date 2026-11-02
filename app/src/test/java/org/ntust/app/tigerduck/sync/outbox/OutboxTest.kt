package org.ntust.app.tigerduck.sync.outbox

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries

/** The outbox over channels that only record what they are asked: the pinned order, and what each call reaches. */
class OutboxTest {

    private val series = SignInSeries()
    private val calls = mutableListOf<String>()

    private inner class FakeChannel(
        override val id: OutboxChannelId,
        private val held: Set<String> = emptySet(),
        private val onDrain: () -> Unit = {},
    ) : OutboxChannel {
        val signIns = mutableListOf<SignIn>()

        override suspend fun drain(signIn: SignIn) {
            calls += "drain $id"
            signIns += signIn
            onDrain()
        }

        override suspend fun requeueParked(signIn: SignIn) {
            calls += "requeue $id"
            signIns += signIn
        }

        override suspend fun heldFields(): Set<String> = held

        override fun forgetMemory() {
            calls += "forget $id"
        }
    }

    // Every channel, registered in an order that is not the pinned one.
    private val channels = OutboxChannelId.entries.reversed().map { FakeChannel(it) }

    @Test
    fun `a drain sends every channel, one after the other, in the pinned order`() = runTest {
        val signIn = series.current
        Outbox(channels.shuffled().toSet()).drain(signIn)

        assertEquals(OutboxChannelId.entries.map { "drain $it" }, calls)
        assertTrue(channels.all { it.signIns == listOf(signIn) })
    }

    @Test
    fun `the pinned order is the order the syncs sent the six queues in`() {
        assertEquals(
            listOf(
                OutboxChannelId.COURSE_IGNORES, OutboxChannelId.MARKS, OutboxChannelId.COURSE_ADDS,
                OutboxChannelId.COURSE_DELETES, OutboxChannelId.COURSE_OVERRIDES, OutboxChannelId.HOLIDAYS,
            ),
            OutboxChannelId.entries,
        )
    }

    @Test
    fun `a course upload landing gives every channel's parked edits one more try, in the pinned order`() = runTest {
        Outbox(channels.toSet()).courseUploadLanded(series.current)

        assertEquals(OutboxChannelId.entries.map { "requeue $it" }, calls)
    }

    @Test
    fun `a drain stops at the channel after its sign-in ended`() = runTest {
        val first = FakeChannel(OutboxChannelId.COURSE_IGNORES) { series.end() }
        val second = FakeChannel(OutboxChannelId.MARKS)

        Outbox(setOf(second, first)).drain(series.current)

        assertEquals(listOf("drain COURSE_IGNORES"), calls)
    }

    @Test
    fun `a field is held while any channel holds an edit of it`() = runTest {
        val outbox = Outbox(
            setOf(
                FakeChannel(OutboxChannelId.MARKS, setOf("assignment_overrides/1/local_status")),
                FakeChannel(OutboxChannelId.COURSE_DELETES, setOf("courses/client:1141:A")),
                FakeChannel(OutboxChannelId.COURSE_ADDS, setOf("courses/client:1141:A", "courses/client:1141:B")),
            ),
        )

        assertTrue(outbox.holds("assignment_overrides/1/local_status"))
        assertTrue(outbox.holds("courses/client:1141:B"))
        assertFalse(outbox.holds("assignment_overrides/2/local_status"))
        assertEquals(
            setOf("assignment_overrides/1/local_status", "courses/client:1141:A", "courses/client:1141:B"),
            outbox.heldFields(),
        )
    }

    @Test
    fun `an outbox with no channel sends nothing and holds nothing`() = runTest {
        val outbox = Outbox(emptySet())
        outbox.drain(series.current)
        outbox.courseUploadLanded(series.current)

        assertFalse(outbox.holds("assignment_overrides/1/local_status"))
    }

    @Test
    fun `a sign-out forgets every channel's memory`() = runTest {
        Outbox(channels.toSet()).forgetAccount()

        assertEquals(OutboxChannelId.entries.map { "forget $it" }, calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two channels of one id are refused`() {
        Outbox(setOf(FakeChannel(OutboxChannelId.MARKS), FakeChannel(OutboxChannelId.MARKS)))
    }
}
