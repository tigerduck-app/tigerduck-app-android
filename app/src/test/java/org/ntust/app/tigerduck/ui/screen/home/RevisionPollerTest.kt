@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.SyncChange
import org.ntust.app.tigerduck.push.SyncChangesPage
import org.ntust.app.tigerduck.ui.screen.home.RevisionPollOutcome.CHANGED
import org.ntust.app.tigerduck.ui.screen.home.RevisionPollOutcome.FAILED
import org.ntust.app.tigerduck.ui.screen.home.RevisionPollOutcome.OFFLINE
import org.ntust.app.tigerduck.ui.screen.home.RevisionPollOutcome.UNAUTHORIZED
import org.ntust.app.tigerduck.ui.screen.home.RevisionPollOutcome.UNCHANGED

/**
 * How long the foreground revision poll waits between requests
 * ([RevisionPollSchedule]), and that it never has two in flight
 * ([RevisionPoller]) — tigerduck-app/tigerduck-app-android#154. It used to
 * ask every 10 seconds whatever the server answered.
 */
class RevisionPollScheduleTest {

    private val start = 1_000_000L

    private fun at(seconds: Long) = start + seconds * 1_000

    @Test
    fun `a minute after a return it polls every 10 seconds`() {
        val schedule = RevisionPollSchedule(start)
        assertEquals(10_000L, schedule.delayAfterAnswer(at(10)))
        assertEquals(10_000L, schedule.delayAfterAnswer(at(59)))
    }

    @Test
    fun `then each quiet answer doubles the wait, up to a minute`() {
        val schedule = RevisionPollSchedule(start)
        val waits = listOf(61L, 81, 121, 181, 241).map { schedule.delayAfterAnswer(at(it)) }
        assertEquals(listOf(20_000L, 40_000, 60_000, 60_000, 60_000), waits)
    }

    @Test
    fun `a change or a local edit brings back the 10-second cadence`() {
        val schedule = RevisionPollSchedule(start)
        schedule.delayAfterAnswer(at(61))
        schedule.delayAfterAnswer(at(81))
        schedule.noteActivity(at(100))
        assertEquals(10_000L, schedule.delayAfterAnswer(at(110)))
        assertEquals(20_000L, schedule.delayAfterAnswer(at(161)))
    }

    @Test
    fun `failures back off exponentially up to five minutes`() {
        val schedule = RevisionPollSchedule(start)
        val waits = (0 until 6).map { schedule.delayAfterFailure(at(5), jitter = 1.0) }
        assertEquals(listOf(20_000L, 40_000, 80_000, 160_000, 300_000, 300_000), waits)
    }

    @Test
    fun `a failure never waits less than the schedule it interrupts`() {
        val schedule = RevisionPollSchedule(start)
        schedule.delayAfterAnswer(at(61))
        schedule.delayAfterAnswer(at(81))
        assertEquals(40_000L, schedule.delayAfterFailure(at(121), jitter = 0.0))
    }

    @Test
    fun `jitter spreads a failure's wait over the upper half of its backoff`() {
        assertEquals(10_000L, RevisionPollSchedule(start).delayAfterFailure(at(5), jitter = 0.0))
        assertEquals(20_000L, RevisionPollSchedule(start).delayAfterFailure(at(5), jitter = 1.0))
    }

    @Test
    fun `an answer after failures returns to the schedule`() {
        val schedule = RevisionPollSchedule(start)
        schedule.delayAfterFailure(at(5), jitter = 1.0)
        schedule.delayAfterFailure(at(25), jitter = 1.0)
        assertEquals(10_000L, schedule.delayAfterAnswer(at(30)))
        assertEquals(20_000L, schedule.delayAfterFailure(at(40), jitter = 1.0))
    }
}

class RevisionPollerTest {

    /** The poll the poller runs: answers from [outcomes], and parks the next call when [holdNext]. */
    private class ScriptedPoll(private val clock: () -> Long) {
        val outcomes = ArrayDeque<RevisionPollOutcome>()
        var holdNext = false
        val times = mutableListOf<Long>()
        private var parked: CompletableDeferred<Unit>? = null

        val calls get() = times.size
        val isParked get() = parked != null

        suspend fun run(): RevisionPollOutcome {
            times += clock()
            if (holdNext) {
                holdNext = false
                CompletableDeferred<Unit>().also { parked = it }.await()
            }
            return outcomes.removeFirstOrNull() ?: UNCHANGED
        }

        fun release() {
            parked?.complete(Unit)
            parked = null
        }
    }

    private fun TestScope.scriptedPoll() = ScriptedPoll { testScheduler.currentTime }

    private fun TestScope.poller(
        poll: ScriptedPoll,
        relogin: suspend () -> Boolean = { false },
    ) = RevisionPoller(
        // backgroundScope: the poller re-arms for as long as it runs, which
        // the test's own scope would wait out for ever.
        scope = backgroundScope,
        poll = poll::run,
        relogin = relogin,
        now = { testScheduler.currentTime },
        jitter = { 1.0 },
    )

    private fun TestScope.advanceSeconds(seconds: Long) {
        advanceTimeBy(seconds * 1_000)
        runCurrent()
    }

    @Test
    fun `a return polls at once, then waits 10 seconds`() = runTest {
        val poll = scriptedPoll()
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        assertEquals(listOf(0L), poll.times)

        advanceSeconds(9)
        assertEquals(1, poll.calls)
        advanceSeconds(1)
        assertEquals(listOf(0L, 10_000L), poll.times)
    }

    @Test
    fun `launch waits for its first poll, since its own sync has just pulled`() = runTest {
        val poll = scriptedPoll()
        val poller = poller(poll)

        poller.start(pollNow = false)
        runCurrent()
        assertEquals(0, poll.calls)

        advanceSeconds(10)
        assertEquals(1, poll.calls)
    }

    @Test
    fun `returns poll at most once per 10 seconds`() = runTest {
        val poll = scriptedPoll()
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        advanceSeconds(4)
        poller.stop()
        poller.start(pollNow = true)
        runCurrent()
        assertEquals(1, poll.calls)

        advanceSeconds(6)
        assertEquals(listOf(0L, 10_000L), poll.times)
    }

    @Test
    fun `an hour with nothing changing costs a fraction of the old 360 requests`() = runTest {
        val poll = scriptedPoll()
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        advanceSeconds(3_600)

        // A minute at 10 seconds, then 20, 40 and a minute at a time.
        assertTrue("polled ${poll.calls} times", poll.calls in 60..70)
        val gaps = poll.times.zipWithNext { a, b -> b - a }
        assertEquals(60_000L, gaps.last())
    }

    @Test
    fun `a change brings back the 10-second cadence`() = runTest {
        val poll = scriptedPoll()
        val poller = poller(poll)
        poller.start(pollNow = true)
        runCurrent()
        advanceSeconds(600)
        val idleCalls = poll.calls

        poll.outcomes += CHANGED
        advanceSeconds(60)
        advanceSeconds(10)

        assertEquals(idleCalls + 2, poll.calls)
        assertEquals(10_000L, poll.times.last() - poll.times[poll.calls - 2])
    }

    @Test
    fun `a local edit brings the next poll within 10 seconds`() = runTest {
        val poll = scriptedPoll()
        val poller = poller(poll)
        poller.start(pollNow = true)
        runCurrent()
        // Idle by now: a minute between polls.
        advanceSeconds(600)
        val lastPoll = poll.times.last()
        advanceSeconds(1)

        poller.noteActivity()
        advanceSeconds(10)

        assertTrue(poll.times.last() > lastPoll)
        assertEquals(lastPoll + 11_000, poll.times.last())
    }

    @Test
    fun `a return while a poll is in flight sends no second request`() = runTest {
        val poll = scriptedPoll()
        poll.holdNext = true
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        assertTrue(poll.isParked)
        poller.start(pollNow = true)
        poller.noteActivity()
        runCurrent()
        assertEquals(1, poll.calls)

        poll.release()
        runCurrent()
        assertFalse(poller.isPolling)
        assertEquals(1, poll.calls)
        advanceSeconds(10)
        assertEquals(2, poll.calls)
    }

    @Test
    fun `a stop lets the poll in flight finish and arms nothing after it`() = runTest {
        val poll = scriptedPoll()
        poll.holdNext = true
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        poller.stop()
        poll.release()
        runCurrent()

        assertFalse(poller.isPolling)
        advanceSeconds(3_600)
        assertEquals(1, poll.calls)
    }

    @Test
    fun `a 401 gets one relogin, and a second stops polling until the next return`() = runTest {
        val poll = scriptedPoll()
        poll.outcomes += listOf(UNAUTHORIZED, UNAUTHORIZED)
        var relogins = 0
        val poller = poller(poll) {
            relogins++
            true
        }

        poller.start(pollNow = true)
        runCurrent()
        assertEquals("asked again at once after the relogin", 2, poll.calls)
        assertEquals(1, relogins)
        assertTrue(poller.isSuspended)

        poller.noteActivity()
        advanceSeconds(3_600)
        assertEquals(2, poll.calls)
        assertEquals(1, relogins)

        poller.start(pollNow = true)
        runCurrent()
        assertEquals(3, poll.calls)
        assertFalse(poller.isSuspended)
    }

    @Test
    fun `a failed relogin stops polling until the next return`() = runTest {
        val poll = scriptedPoll()
        poll.outcomes += UNAUTHORIZED
        val poller = poller(poll) { false }

        poller.start(pollNow = true)
        runCurrent()
        advanceSeconds(3_600)

        assertTrue(poller.isSuspended)
        assertEquals(1, poll.calls)
    }

    @Test
    fun `failed polls wait longer each time, and an answer ends the backoff`() = runTest {
        val poll = scriptedPoll()
        poll.outcomes += listOf(FAILED, FAILED, FAILED)
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        advanceSeconds(160)

        // 20, 40 and 80 seconds after the failures (jitter at its top), then
        // the idle schedule: the active minute is over by 140 seconds.
        assertEquals(listOf(0L, 20_000, 60_000, 140_000, 160_000), poll.times)
    }

    @Test
    fun `offline asks nothing and keeps to the schedule`() = runTest {
        val poll = scriptedPoll()
        poll.outcomes += listOf(OFFLINE, OFFLINE)
        val poller = poller(poll)

        poller.start(pollNow = true)
        runCurrent()
        advanceSeconds(20)

        assertEquals(listOf(0L, 10_000, 20_000), poll.times)
    }

    @Test
    fun `a poll that throws counts as a failure`() = runTest {
        val poller = RevisionPoller(
            scope = backgroundScope,
            poll = { error("boom") },
            relogin = { false },
            now = { testScheduler.currentTime },
            jitter = { 1.0 },
        )

        poller.start(pollNow = true)
        runCurrent()

        assertFalse(poller.isPolling)
        assertFalse(poller.isSuspended)
    }
}

/**
 * One poll ([pollRevisionOnce]): what it asks and what it makes of the
 * answer, which decides the next wait.
 */
class PollRevisionOnceTest {

    private var syncOn = true
    private var online = true
    private var signedIn = true
    private var serverRevision: suspend () -> Long = { 5L }
    private var lastKnown = 5L
    private var inFlight = 0
    private var fetches = 0
    private var syncs = 0
    private var syncLands = true

    /** The change feed's answer; null as when the read failed or the changes are gone. */
    private var changes: suspend (Long) -> SyncChangesPage? = { null }
    private val feedReads = mutableListOf<Long>()
    private var generation = 1
    private var retries = 0
    private var onRetry: () -> Unit = {}

    private suspend fun poll() = pollRevisionOnce(
        syncEnabled = { syncOn },
        online = { online },
        signIn = { signedIn },
        fetchRevision = {
            fetches++
            serverRevision()
        },
        fetchChanges = { since ->
            feedReads += since
            changes(since)
        },
        lastKnownRevision = { lastKnown },
        setLastKnownRevision = { lastKnown = it },
        signInGeneration = { generation },
        fullSyncsInFlight = { inFlight },
        retryUnsent = {
            retries++
            onRetry()
        },
        fullSync = {
            syncs++
            if (syncLands) lastKnown = 7
        },
    )

    private fun page(vararg changes: Pair<Long, String>) =
        SyncChangesPage(7, 7, hasMore = false, changes.map { (revision, type) -> SyncChange(revision, type) })

    @Test
    fun `with sync off, offline or signed out nothing is asked`() = kotlinx.coroutines.test.runTest {
        syncOn = false
        assertEquals(UNCHANGED, poll())
        syncOn = true
        online = false
        assertEquals(OFFLINE, poll())
        online = true
        signedIn = false
        assertEquals(UNCHANGED, poll())
        assertEquals(0, fetches)
    }

    @Test
    fun `at the revision this phone has, nothing is synced`() = kotlinx.coroutines.test.runTest {
        assertEquals(UNCHANGED, poll())
        assertEquals(0, syncs)
    }

    @Test
    fun `ahead of it, a full sync runs`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }

        assertEquals(CHANGED, poll())
        assertEquals(1, syncs)
    }

    @Test
    fun `a full sync that did not land counts as a failure, to back off`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        syncLands = false

        assertEquals(FAILED, poll())
    }

    @Test
    fun `a full sync already under way takes the change, with no second one`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        inFlight = 1

        assertEquals(CHANGED, poll())
        assertEquals(0, syncs)
    }

    @Test
    fun `a 401 is told apart from other failures`() = kotlinx.coroutines.test.runTest {
        serverRevision = { throw org.ntust.app.tigerduck.push.PushApiException("HTTP 401", 401) }
        assertEquals(UNAUTHORIZED, poll())

        serverRevision = { throw org.ntust.app.tigerduck.push.PushApiException("HTTP 503", 503) }
        assertEquals(FAILED, poll())

        serverRevision = { throw java.io.IOException("reset") }
        assertEquals(FAILED, poll())
    }

    @Test
    fun `changes to assignments and bulletins alone move the watermark once what waits is sent, with no full sync`() =
        kotlinx.coroutines.test.runTest {
            serverRevision = { 7L }
            changes = { page(6L to "assignment", 7L to "bulletin_state") }

            assertEquals(CHANGED, poll())
            assertEquals(listOf(5L), feedReads)
            assertEquals(0, syncs)
            assertEquals(1, retries)
            assertEquals(7L, lastKnown)
        }

    @Test
    fun `any other change takes the full sync`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        changes = { page(6L to "assignment", 7L to "course_override") }

        assertEquals(CHANGED, poll())
        assertEquals(1, syncs)
        assertEquals(0, retries)
    }

    @Test
    fun `a feed read that failed or found the changes gone takes the full sync`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        changes = { throw java.io.IOException("reset") }
        assertEquals(CHANGED, poll())

        lastKnown = 5
        changes = { null }
        assertEquals(CHANGED, poll())

        assertEquals(2, syncs)
    }

    @Test
    fun `before a full sync has set the watermark the feed is not read`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        lastKnown = 0

        assertEquals(CHANGED, poll())
        assertEquals(emptyList<Long>(), feedReads)
        assertEquals(1, syncs)
    }

    @Test
    fun `a full sync or a sign-out that overtakes the read takes the change`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        changes = {
            lastKnown = 7
            page(6L to "assignment", 7L to "assignment")
        }
        assertEquals(CHANGED, poll())

        lastKnown = 5
        changes = {
            generation++
            page(6L to "assignment", 7L to "assignment")
        }
        assertEquals(CHANGED, poll())

        assertEquals(0, syncs)
        assertEquals(0, retries)
        assertEquals("the sign-out's watermark is not moved for the account that left", 5L, lastKnown)
    }

    @Test
    fun `a sign-out while what waits is sent leaves the watermark alone`() = kotlinx.coroutines.test.runTest {
        serverRevision = { 7L }
        changes = { page(7L to "assignment") }
        onRetry = { generation++ }

        assertEquals(CHANGED, poll())
        assertEquals(5L, lastKnown)
    }

    @Test
    fun `a cancellation is not taken for a failed poll`() = kotlinx.coroutines.test.runTest {
        serverRevision = { throw kotlinx.coroutines.CancellationException("Home left") }

        val thrown = runCatching { poll() }.exceptionOrNull()

        assertTrue("got $thrown", thrown is kotlinx.coroutines.CancellationException)
    }
}
