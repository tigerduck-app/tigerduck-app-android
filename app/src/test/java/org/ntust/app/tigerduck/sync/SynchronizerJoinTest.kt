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
 * Full syncs asked for while one runs (`tigersync-refresh-order`, "One refresh at a time"), over
 * fake steps whose snapshot fetch waits for the test. The callers run on [Dispatchers.Default], so
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

    /** Until [count] callers wait on the pass they joined. */
    private suspend fun untilJoined(count: Int) {
        withTimeout(10_000) { while (synchronizer.joined != count) delay(1) }
    }

    @Test
    fun `a sync asked for while one runs joins it, and both have its one snapshot`() = runBlocking {
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val second = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilJoined(1)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { first.await() })
        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { second.await() })
        assertEquals("snapshot fetches", 1, fetches.get())
    }

    /** The worker's sync joins Home's pass, and runs its own steps after it over that pass. */
    @Test
    fun `a sync in the other mode joins too, and runs its own steps after the pass it joined`() = runBlocking {
        val home = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val joinedModes = Collections.synchronizedList(mutableListOf<SyncMode>())
        val worker = scope.async {
            synchronizer.sync(SyncMode.BACKGROUND, school.current, afterSync = { pass -> joinedModes += pass.mode })
        }
        untilJoined(1)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { home.await() })
        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { worker.await() })
        assertEquals(1, fetches.get())
        assertEquals("the worker's steps after it ran, over Home's pass", listOf(SyncMode.FOREGROUND), joinedModes.toList())
        assertEquals("only the pass's own mode's steps ran", listOf("foreground fetch", "foreground after-steps"), log.toList())
    }

    @Test
    fun `a sync under the next sign-in never joins the departing one's pass`() = runBlocking {
        val departing = school.current
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, departing) }
        untilFetches(1)
        school.end()
        school.open()

        val next = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(2)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { next.await() })
        withTimeout(10_000) { first.await() }
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a pass its caller cancelled is run again by the caller that joined it`() = runBlocking {
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val second = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilJoined(1)

        first.cancelAndJoin()
        untilFetches(2)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { second.await() })
        assertEquals(2, fetches.get())
    }

    /**
     * Home's relogin after a refused session runs the sync again from the failed pass's failure
     * step: a pass of its own, never one joined to the pass it is part of, which would wait for
     * itself. A caller that joined the failed pass hears the failure and runs no failure step.
     */
    @Test
    fun `a failed pass's failure step runs a pass of its own, and who joined hears the failure`() = runBlocking {
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
        val joined = scope.async { synchronizer.sync(SyncMode.FOREGROUND, signIn) { failureSteps.incrementAndGet() } }
        untilJoined(1)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.FAILED, withTimeout(10_000) { first.await() })
        assertEquals(SyncOutcome.FAILED, withTimeout(10_000) { joined.await() })
        assertEquals("only the pass's own failure step ran", 1, failureSteps.get())
        assertEquals(2, fetches.get())
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
