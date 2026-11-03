package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.session.accountEventStream
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.reconcile.Reconciler
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.SingleFlight
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The order of a refresh (refactor-v4 D13; `sync-order`, "A refresh reads the backend before it
 * uploads school data"; iOS `RefreshOrderTests`): the pull and the school fetch start together,
 * the school's data is applied after the pull, and what it derived goes up after that. Each step
 * runs on [Dispatchers.Default], so it may land at any moment of the others.
 */
class RefreshOrderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val school = SignInSeries()
    private val events = accountEventStream()
    private val gate = FirstPullGate(events, scope)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val cache by lazy {
        val context = JvmContext()
        DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }
    // On /v4, the one API whose school uploads wait for the first pull.
    private val uploads by lazy { SchoolUploads(gate, scope, cache, { BackendApi.V4 }) { _, _ -> } }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun until(what: String, done: () -> Boolean) {
        withTimeout(10_000) { while (!done()) delay(1) }
        assertTrue(what, done())
    }

    /** What the school's fetch derived, through [SchoolUploads] as the app's fetches send it. */
    private suspend fun uploadDerived(signIn: SignIn) =
        uploads.afterFirstPull(signIn, "derived", now = { log += "upload" }, later = { log += "upload" })

    @Test
    fun `a school answer before the pull is applied after it, and its upload after that`() = runBlocking {
        val signIn = school.current
        val pullHeld = CompletableDeferred<Unit>()
        val refresh = scope.launch {
            RefreshOrder.run(
                pull = {
                    pullHeld.await()
                    gate.notePull(signIn)
                    log += "pulled"
                },
                pushOutbox = { log += "outbox" },
            ) { afterPull ->
                log += "fetched"
                afterPull()
                log += "applied"
                uploadDerived(signIn)
            }
        }
        until("the school answered") { log.toList() == listOf("fetched") }
        // Long enough for anything not held to have run.
        delay(200)
        assertEquals("the school's answer waits for the pull", listOf("fetched"), log.toList())

        pullHeld.complete(Unit)
        withTimeout(10_000) { refresh.join() }

        assertEquals(listOf("fetched", "pulled", "outbox", "applied", "upload"), log.toList())
    }

    /** `sync-order`, "The backend is unreachable": the school's data shows, and its uploads wait. */
    @Test
    fun `a failed pull still shows the school data, and what it derived waits for a pull that lands`() = runBlocking {
        val signIn = school.current

        RefreshOrder.run(pull = { log += "pull failed" }, pushOutbox = { log += "outbox" }) { afterPull ->
            afterPull()
            log += "applied"
            uploadDerived(signIn)
        }
        until("the upload waits at the gate") { gate.waiting == 1 }
        delay(200)
        assertEquals(listOf("pull failed", "outbox", "applied"), log.toList())

        gate.notePull(signIn)
        until("the upload went") { log.lastOrNull() == "upload" }
        assertEquals(listOf("pull failed", "outbox", "applied", "upload"), log.toList())
    }

    /** As iOS's: the pull is cancelled, the outbox step after it still runs, and the fetch applies nothing. */
    @Test
    fun `a cancelled refresh cancels its pull, and its school fetch applies nothing`() = runBlocking {
        val pullStarted = CompletableDeferred<Unit>()
        val pullCancelled = CompletableDeferred<Unit>()
        val refresh = scope.launch {
            RefreshOrder.run(
                pull = {
                    pullStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        pullCancelled.complete(Unit)
                    }
                },
                // A step that suspends, as the app's do: it runs whatever cancelled the pull.
                pushOutbox = {
                    yield()
                    log += "outbox"
                },
            ) { afterPull ->
                log += "fetched"
                afterPull()
                log += "applied"
            }
        }
        withTimeout(10_000) { pullStarted.await() }
        until("the school answered") { "fetched" in log }

        refresh.cancelAndJoin()

        withTimeout(10_000) { pullCancelled.await() }
        assertEquals(listOf("fetched", "outbox"), log.toList())
    }

    @Test
    fun `a school fetch that fails lets the pull finish first`() = runBlocking {
        val pullHeld = CompletableDeferred<Unit>()
        val refresh = scope.async {
            RefreshOrder.run(
                pull = {
                    pullHeld.await()
                    log += "pulled"
                },
                pushOutbox = { log += "outbox" },
            ) { throw IOException("made-up school failure") }
        }
        delay(200)
        pullHeld.complete(Unit)

        try {
            withTimeout(10_000) { refresh.await() }
            fail("the school's failure was swallowed")
        } catch (e: IOException) {
            assertEquals("made-up school failure", e.message)
        }
        assertEquals(listOf("pulled", "outbox"), log.toList())
    }

    /**
     * `tigersync-refresh-order`, "Two pulls in a row": the user pulls to refresh twice within a
     * second. Home's refreshes share one run (its `SingleFlight`): one pull, and one round of
     * school fetches. Pulls asked for from elsewhere meanwhile (a return to the app, the revision
     * poll, the worker) wait for it and share one more pass, as iOS's `FullSyncGate` follows up:
     * passes never overlap.
     */
    @Test
    fun `two refreshes within a second run one pull`() = runBlocking {
        val snapshots = AtomicInteger()
        val schoolRounds = AtomicInteger()
        val pullHeld = CompletableDeferred<Unit>()
        val synchronizer = synchronizer {
            snapshots.incrementAndGet()
            pullHeld.await()
        }
        val signIn = school.current
        val home = SingleFlight(scope)
        val refresh: suspend () -> Unit = {
            RefreshOrder.run(pull = { synchronizer.sync(SyncMode.FOREGROUND, signIn) }, pushOutbox = {}) { afterPull ->
                schoolRounds.incrementAndGet()
                afterPull()
            }
        }

        val first = scope.launch { home.join(refresh) }
        until("the pull is under way") { snapshots.get() == 1 }
        // Each runs here up to its first wait, so it waits before the pull is let go.
        val second = scope.launch(start = CoroutineStart.UNDISPATCHED) { home.join(refresh) }
        val elsewhere = List(2) { scope.async(start = CoroutineStart.UNDISPATCHED) { synchronizer.sync(SyncMode.BACKGROUND, signIn) } }
        until("the other pulls wait for the one under way") { synchronizer.waiting == 2 }
        assertEquals("one pull for the two refreshes, nothing beside it", 1, snapshots.get())
        pullHeld.complete(Unit)

        withTimeout(10_000) {
            first.join()
            second.join()
        }
        for (sync in elsewhere) assertEquals(SyncOutcome.SYNCED, withTimeout(10_000) { sync.await() })
        assertEquals("rounds of school fetches", 1, schoolRounds.get())
        assertEquals("snapshots fetched: the refresh's, then one more for the pulls asked meanwhile", 2, snapshots.get())
    }

    /** A synchronizer whose every step but the snapshot's [fetch] does nothing. */
    private fun synchronizer(fetch: suspend () -> Unit): Synchronizer {
        val result = BackendSyncResult(
            ignoredIds = emptySet(), completedIds = emptySet(), courseOverrides = emptyList(), serverCourseNos = emptySet(), currentRevision = 1,
        )
        val policy = SyncPolicy(
            cloudSync = true, flavorSyncs = true, switchedOn = SyncCategory.entries.toSet(), held = emptySet(), lastWriterWins = false,
        )
        val steps = object : PassSteps {
            override suspend fun mayRun() = true
            override fun beginCourseSync() = DataCache.CourseSync(0L) { 0L }
            override fun endCourseSync(courseSync: DataCache.CourseSync) = Unit
            override suspend fun stampDeletes() = 0L
            override suspend fun fetch(signIn: SignIn, courseSync: DataCache.CourseSync): BackendSyncResult = result.also { fetch() }
            override suspend fun listed(result: BackendSyncResult, signIn: SignIn) = Unit
            override fun policy() = policy
            override fun recordTaken(pass: SyncPass, took: Set<String>) = Unit
            override fun noteRevision(pass: SyncPass) = Unit
            override fun notePull(pass: SyncPass) = gate.notePull(pass.signIn)
            override fun synced() = Unit
            override fun afterSync(pass: SyncPass) = Unit
            override fun failed() = Unit
        }
        val none = object : Reconciler {
            override suspend fun reconcile(pass: SyncPass) = true
        }
        return Synchronizer(
            drain = {}, foreground = steps, background = steps,
            marks = none, ignores = none, overrides = none, reset = none, holidays = none, roster = none,
        )
    }
}
