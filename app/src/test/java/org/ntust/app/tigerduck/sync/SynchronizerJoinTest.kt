package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.reconcile.Reconciler
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full syncs asked for while one runs, over fake steps whose snapshot fetch waits for the test.
 * The callers run on [Dispatchers.Default], so each may reach the synchronizer at any moment of
 * the other's pass.
 */
class SynchronizerJoinTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val school = SignInSeries()
    private val fetches = AtomicInteger()
    private val fetchHeld = CompletableDeferred<Unit>()
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

    private inner class Steps : PassSteps {
        override suspend fun mayRun() = true
        override fun beginCourseSync() = DataCache.CourseSync(0L) { 0L }
        override fun endCourseSync(courseSync: DataCache.CourseSync) = Unit
        override suspend fun stampDeletes() = 0L
        override suspend fun fetch(signIn: SignIn, courseSync: DataCache.CourseSync): BackendSyncResult {
            fetches.incrementAndGet()
            fetchHeld.await()
            return result
        }
        override suspend fun listed(result: BackendSyncResult, signIn: SignIn) = Unit
        override fun policy() = policy
        override fun recordTaken(pass: SyncPass, took: Set<String>) = Unit
        override fun noteRevision(pass: SyncPass) = Unit
        override fun notePull(pass: SyncPass) = Unit
        override fun synced() = Unit
        override fun afterSync(pass: SyncPass) = Unit
        override fun failed() = Unit
    }

    private val none = object : Reconciler {
        override suspend fun reconcile(pass: SyncPass) = true
    }

    private val synchronizer = Synchronizer(
        drain = {}, foreground = Steps(), background = Steps(),
        marks = none, ignores = none, overrides = none, reset = none, holidays = none, roster = none,
    )

    private suspend fun untilFetches(count: Int) {
        withTimeout(10_000) { while (fetches.get() < count) delay(1) }
    }

    @Test
    fun `two syncs asked for together each run a pass of their own`() = runBlocking {
        val first = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(1)
        val second = scope.async { synchronizer.sync(SyncMode.FOREGROUND, school.current) }
        untilFetches(2)
        fetchHeld.complete(Unit)

        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { first.await() })
        assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { second.await() })
        assertEquals("snapshot fetches", 2, fetches.get())
    }
}
