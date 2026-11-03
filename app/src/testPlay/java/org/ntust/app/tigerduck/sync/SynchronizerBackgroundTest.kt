package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.ListenableWorker
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.backgroundSyncWorker
import org.ntust.app.tigerduck.notification.settingsSyncAt
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The background mode of the one synchronizer over the real stores and clients: the worker's
 * pull opens the first-pull gate, its fetch signs in again once and marks each attempt, and its
 * marks step tells the worker's own after-steps whether this phone's marks changed. What it sends,
 * and what it leaves to Home (C2), is `BackgroundPullRequestsTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SynchronizerBackgroundTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig
    private lateinit var signIn: SignIn

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        rig = homeSyncRig(context, w, backend.client, idleScope)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun snapshot(ignored: List<Int> = emptyList()) = JSONObject()
        .put("assignments", JSONArray())
        .put(
            "assignment_overrides",
            JSONArray().apply {
                ignored.forEach { put(JSONObject().put("moodle_assignment_id", it).put("local_status", "ignored")) }
            },
        )
        .put("courses", JSONArray())
        .put("course_overrides", JSONArray())
        .put("current_revision", 42)
        .toString()

    /** The worker's run, off the main thread it posts to, with only the backend sync due. */
    private fun runWorker(): ListenableWorker.Result {
        val aMinuteAgo = System.currentTimeMillis() - 60_000
        w.academicCalendar.current().currentTerm(AppClock.localDateTime().toLocalDate())
            ?.let { w.appPrefs.school.backgroundCoursesTerm.set(it.code, signIn) }
        w.appPrefs.school.backgroundCoursesSyncedAtMs.set(aMinuteAgo, signIn)
        w.appPrefs.school.schoolDataSyncedAtMs.set(aMinuteAgo, signIn)
        val worker = backgroundSyncWorker(context, w, rig, settingsSyncAt(context, w, backend.client, idleScope), idleScope)
        val thread = Executors.newSingleThreadExecutor()
        try {
            val run = thread.submit(Callable { runBlocking { worker.doWork() } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!run.isDone) {
                check(System.nanoTime() < deadline) { "the background sync never finished" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            return run.get()
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun `a background run's pull opens the first-pull gate for its sign-in`() {
        backend.answer("GET /sync/full", 200, snapshot())
        assertFalse(rig.gate.hasPulled(signIn))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue("the worker's pull left the gate shut", rig.gate.hasPulled(signIn))
    }

    @Test
    fun `a background run whose pull fails leaves the gate shut`() {
        backend.answer("GET /sync/full", 503)

        runWorker()

        assertFalse(rig.gate.hasPulled(signIn))
    }

    @Test
    fun `the background marks step tells the worker's after-steps whether this phone's marks changed`() = runBlocking {
        // Synced before, so the server's marks are taken over this phone's.
        assertTrue(w.appPrefs.school.assignmentMarksSyncedOnce.set(true, signIn))
        val changed = mutableListOf<Boolean>()

        backend.answer("GET /sync/full", 200, snapshot(ignored = listOf(7)))
        rig.synchronizer.sync(SyncMode.BACKGROUND, signIn, afterSync = { changed += it.marksChanged })
        backend.answer("GET /sync/full", 200, snapshot(ignored = listOf(7)))
        rig.synchronizer.sync(SyncMode.BACKGROUND, signIn, afterSync = { changed += it.marksChanged })

        assertEquals("taken, then the same again", listOf(true, false), changed)
        assertEquals(setOf("7"), w.dataCache.loadIgnoredAssignments())
    }

    /** The background's steps over the rig's stores, its snapshot from [fetch] and its relogin [relogin]. */
    private fun backgroundSteps(
        fetch: suspend (SignIn) -> BackendSyncResult,
        relogin: suspend (SignIn) -> Boolean,
    ) = BackgroundSteps(
        w.appPrefs, SyncPolicies(w.appPrefs), w.dataCache, w.tokens, fetch, relogin,
        UnsentQueues(rig.ignores, rig.marks, rig.adds, rig.deletes, rig.overrides, rig.holidays), rig.gate,
    )

    private val result = BackendSyncResult(
        ignoredIds = emptySet(), completedIds = emptySet(), courseOverrides = emptyList(), serverCourseNos = emptySet(), currentRevision = 1,
    )

    @Test
    fun `the background fetch signs in again once on a refused session, and marks each attempt's fetch`() = runBlocking {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val fetched = mutableListOf<SignIn>()
        val steps = backgroundSteps(
            fetch = { pinned ->
                fetched += pinned
                log += "fetch"
                if (fetched.size == 1) throw BackendException(BackendError(401, "session_revoked"), "refused")
                result
            },
            relogin = { log += "relogin"; true },
        )

        val answer = steps.fetch(signIn, DataCache.CourseSync(0L) { log += "fetching"; 0L })

        assertSame(result, answer)
        assertEquals(listOf("fetching", "fetch", "relogin", "fetching", "fetch"), log.toList())
        assertEquals("both under the sign-in the run began with", listOf(signIn, signIn), fetched.toList())
    }

    @Test
    fun `the background fetch gives up on a refused session it cannot sign in again for, and on any other failure`() = runBlocking {
        for ((failure, signsIn) in listOf(BackendException(BackendError(401, "session_revoked"), "refused") to false, BackendException(BackendError(503, null), "busy") to true)) {
            var reloginAsked = 0
            var fetches = 0
            val steps = backgroundSteps(
                fetch = { fetches++; throw failure },
                relogin = { reloginAsked++; signsIn },
            )
            try {
                steps.fetch(signIn, DataCache.CourseSync(0L) { 0L })
                fail("the fetch's failure was swallowed")
            } catch (e: BackendException) {
                assertSame(failure, e)
            }
            assertEquals("fetched again", 1, fetches)
            assertEquals(if (failure.error.status == 401) 1 else 0, reloginAsked)
        }
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
