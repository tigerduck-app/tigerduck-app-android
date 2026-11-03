package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.reconcile.Reconciler
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full syncs asked for while one runs (`tigersync-refresh-order`, "One refresh at a time"; iOS
 * `FullSyncGate` with a follow-up), over fake steps whose snapshot fetch waits for the test. The callers run on [Dispatchers.Default], so
 * each may reach the synchronizer at any moment of the other's pass.
 */
class SynchronizerJoinTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val school = SignInSeries()
    private val fetches = AtomicInteger()
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What each fetch waits for; completed, they answer at once. */
    @Volatile private var fetchHeld = CompletableDeferred<Unit>()

    /** The fetches that throw, by their number from 1. */
    private val failing = Collections.synchronizedSet(mutableSetOf<Int>())
    private val policy = SyncPolicy(
        cloudSync = true, flavorSyncs = true, switchedOn = SyncCategory.entries.toSet(), held = emptySet(), lastWriterWins = false,
    )
    private val result = BackendSyncResult(
        ignoredIds = emptySet(), completedIds = emptySet(), courseOverrides = emptyList(), serverCourseNos = emptySet(), currentRevision = 1,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private inner class Steps(private val name: String) : PassSteps {
        override suspend fun mayRun() = true
        override fun beginCourseSync() = DataCache.CourseSync(0L) { 0L }
        override fun endCourseSync(courseSync: DataCache.CourseSync) = Unit
        override suspend fun stampDeletes() = 0L
        override suspend fun fetch(signIn: SignIn, courseSync: DataCache.CourseSync): BackendSyncResult {
            val number = fetches.incrementAndGet()
            log += "$name fetch"
            fetchHeld.await()
            if (number in failing) throw IOException("made-up failure")
            return result
        }
        override suspend fun listed(result: BackendSyncResult, signIn: SignIn) = Unit
        override fun policy() = policy
        override fun recordTaken(pass: SyncPass, took: Set<String>) = Unit
        override fun noteRevision(pass: SyncPass) = Unit
        override fun notePull(pass: SyncPass) = Unit
        override fun synced() = Unit
        override fun afterSync(pass: SyncPass) {
            log += "$name after-steps"
        }
        override fun failed() = Unit
    }

    private val none = object : Reconciler {
        override suspend fun reconcile(pass: SyncPass) = true
    }

    private val synchronizer = Synchronizer(
        drain = {}, foreground = Steps("foreground"), background = Steps("background"),
        marks = none, ignores = none, overrides = none, reset = none, holidays = none, roster = none,
    )

    private suspend fun untilFetches(count: Int) {
        withTimeout(10_000) { while (fetches.get() < count) delay(1) }
    }

    /** Until [count] callers wait, on a pass they did not start or for the one under way to be done. */
    private suspend fun untilWaiting(count: Int) {
        withTimeout(10_000) { while (synchronizer.waiting != count) delay(1) }
    }

    @Test
    fun `a sync asked for while one runs waits for it, and one more pass runs for every sync asked meanwhile`() = runBlocking {
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val second = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        val third = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilWaiting(2)
        assertEquals("nothing fetched beside the pass under way", 1, fetches.get())
        fetchHeld.complete(Unit)

        for (sync in listOf(first, second, third)) assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { sync.await() })
        assertEquals("snapshot fetches", 2, fetches.get())
        assertEquals(
            "the one more pass fetched once the first was done",
            listOf("foreground fetch", "foreground after-steps", "foreground fetch", "foreground after-steps"),
            log.toList(),
        )
    }

    /**
     * The worker's sync, asked for while Home's pass runs, waits for it and runs the one more pass
     * in its own mode; a Home sync asked for meanwhile shares it, its steps after it over that pass.
     */
    @Test
    fun `a sync in the other mode waits too, and the one more pass is in the mode of its first caller`() = runBlocking {
        val home = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val worker = scope.async { synchronizer.sync(SyncMode.BACKGROUND, school.current) }
        untilWaiting(1)
        val sharedModes = Collections.synchronizedList(mutableListOf<SyncMode>())
        val homeAgain = scope.async {
            synchronizer.sync(SyncMode.FOREGROUND, school.current, afterSync = { pass -> sharedModes += pass.mode })
        }
        untilWaiting(2)
        fetchHeld.complete(Unit)

        for (sync in listOf(home, worker, homeAgain)) assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { sync.await() })
        assertEquals(2, fetches.get())
        assertEquals("Home's second sync ran its steps after the worker's pass", listOf(SyncMode.BACKGROUND), sharedModes.toList())
        assertEquals(
            listOf("foreground fetch", "foreground after-steps", "background fetch", "background after-steps"),
            log.toList(),
        )
    }

    /**
     * The departing sign-in's pass, and the one more pass a sync of it asked for: the next sign-in's
     * sync waits for neither and shares neither, and its steps after it see only its own pass.
     */
    @Test
    fun `a sync under the next sign-in never waits for or shares the departing one's passes`() = runBlocking {
        val departing = school.current
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, departing) }
        untilFetches(1)
        val again = scope.async { synchronizer.sync(SyncMode.FOREGROUND, departing) }
        untilWaiting(1)
        school.end()
        school.open()
        val signIn = school.current
        val seen = Collections.synchronizedList(mutableListOf<SignIn>())

        val next = scope.async { synchronizer.sync(SyncMode.FOREGROUND, signIn, afterSync = { pass -> seen += pass.signIn }) }
        untilFetches(2)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { next.await() })
        withTimeout(10_000) {
            first.await()
            again.await()
        }
        assertEquals("the next sign-in's steps after it saw its own pass alone", listOf(signIn), seen.toList())
    }

    /**
     * A pass cut short by its caller's cancellation, and the one more pass whose first caller was
     * cancelled while it waited, are each run by a caller that waited for them: nothing asked for
     * is lost, and still one pass at a time.
     */
    @Test
    fun `a pass, or the one more pass, whose caller was cancelled is run by a caller that waited for it`() = runBlocking {
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val second = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilWaiting(1)
        val third = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilWaiting(2)

        second.cancelAndJoin()
        untilWaiting(1)
        first.cancelAndJoin()
        untilFetches(2)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { third.await() })
        assertEquals(2, fetches.get())
    }

    /**
     * Home's relogin after a refused session runs the sync again from the failed pass's failure
     * step. Never a wait on the pass it is part of, which would wait for itself: it shares the one
     * more pass a caller asked for meanwhile, which runs once the failed one is done. Only the
     * failed pass's own failure step runs.
     */
    @Test
    fun `a failed pass's failure step shares the one more pass, and nothing waits for itself`() = runBlocking {
        failing += 1
        val signIn = school.current
        val failureSteps = AtomicInteger()
        val first = scope.async {
            synchronizer.sync(SyncMode.FOREGROUND, signIn) {
                failureSteps.incrementAndGet()
                assertEquals("the sync run again", SyncOutcome.SYNCED, synchronizer.sync(SyncMode.FOREGROUND, signIn))
            }
        }
        untilFetches(1)
        val asked = scope.async { synchronizer.sync(SyncMode.FOREGROUND, signIn) { failureSteps.incrementAndGet() } }
        untilWaiting(1)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.FAILED, withTimeout(10_000) { first.await() })
        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { asked.await() })
        assertEquals("only the failed pass's own failure step ran", 1, failureSteps.get())
        // The run again shares the one more pass, or, asked for once that has begun, runs one after it.
        assertTrue("snapshot fetches: ${fetches.get()}", fetches.get() in 2..3)
    }

    @Test
    fun `a sync asked for once the pass has landed runs a pass of its own`() = runBlocking {
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, synchronizer.sync(SyncMode.FOREGROUND, school.current))
        assertEquals(SyncOutcome.SYNCED, synchronizer.sync(SyncMode.FOREGROUND, school.current))
        assertEquals(2, fetches.get())
        assertTrue(log.count { it == "foreground after-steps" } == 2)
    }
}
