package org.ntust.app.tigerduck.sync.outbox

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries

/**
 * The one drain while some queues are not channels yet: each such queue goes in the place its
 * channel will take, a channel registered under that id goes instead of it, and nothing goes once
 * the drain's sign-in has ended.
 */
class OutboxStandInTest {

    private val series = SignInSeries()
    private val calls = mutableListOf<String>()

    private inner class FakeChannel(override val id: OutboxChannelId, private val onDrain: () -> Unit = {}) : OutboxChannel {
        override suspend fun drain(signIn: SignIn) {
            calls += "channel $id"
            onDrain()
        }

        override suspend fun requeueParked(signIn: SignIn) = Unit

        override suspend fun heldFields(): Set<String> = emptySet()

        override fun forgetMemory() = Unit
    }

    private fun standIn(id: OutboxChannelId, onDrain: () -> Unit = {}): Pair<OutboxChannelId, suspend (SignIn) -> Unit> =
        id to { _ ->
            calls += "queue $id"
            onDrain()
        }

    // The app's four channels today, registered out of order.
    private val registered = listOf(
        OutboxChannelId.HOLIDAYS, OutboxChannelId.COURSE_DELETES, OutboxChannelId.MARKS, OutboxChannelId.COURSE_ADDS,
    ).map { FakeChannel(it) }.toSet()

    @Test
    fun `a queue that is not a channel yet goes in its channel's place in the order`() = runTest {
        Outbox(registered).drain(
            series.current,
            standIns = mapOf(standIn(OutboxChannelId.COURSE_OVERRIDES), standIn(OutboxChannelId.COURSE_IGNORES)),
        )

        assertEquals(
            listOf(
                "queue COURSE_IGNORES", "channel MARKS", "channel COURSE_ADDS", "channel COURSE_DELETES",
                "queue COURSE_OVERRIDES", "channel HOLIDAYS",
            ),
            calls,
        )
    }

    @Test
    fun `a channel registered goes in place of its stand-in, which is not sent too`() = runTest {
        Outbox(registered + FakeChannel(OutboxChannelId.COURSE_IGNORES)).drain(
            series.current,
            standIns = mapOf(standIn(OutboxChannelId.COURSE_IGNORES), standIn(OutboxChannelId.COURSE_OVERRIDES)),
        )

        assertEquals(
            listOf(
                "channel COURSE_IGNORES", "channel MARKS", "channel COURSE_ADDS", "channel COURSE_DELETES",
                "queue COURSE_OVERRIDES", "channel HOLIDAYS",
            ),
            calls,
        )
    }

    @Test
    fun `nothing goes past the drain's sign-in, a stand-in's queue included`() = runTest {
        Outbox(registered).drain(
            series.current,
            standIns = mapOf(standIn(OutboxChannelId.COURSE_IGNORES) { series.end() }, standIn(OutboxChannelId.COURSE_OVERRIDES)),
        )

        assertEquals(listOf("queue COURSE_IGNORES"), calls)

        calls.clear()
        val ended = series.current.also { series.end() }
        Outbox(registered).drain(ended, standIns = mapOf(standIn(OutboxChannelId.COURSE_IGNORES)))
        assertEquals(emptyList<String>(), calls)
    }
}
