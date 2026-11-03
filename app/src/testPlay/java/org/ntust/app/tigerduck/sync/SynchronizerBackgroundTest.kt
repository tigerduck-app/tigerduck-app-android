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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.backgroundSyncWorker
import org.ntust.app.tigerduck.notification.settingsSyncAt
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.idleTermSync
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
 * pull opens the first-pull gate, its fetch signs in again once and marks each attempt (Home's
 * marks its one), its marks step tells the worker's own after-steps whether this phone's marks
 * changed, and its steps need no screen. What it sends,
 * and what it leaves to Home (C2), is `BackgroundPullRequestsTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SynchronizerBackgroundTest {

    // Pins /v3's requests and rules until Z1 and Z2 delete them: a base ending in /v3 stays there.
    @get:Rule
    val speaksV3 = SpeaksV3()

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
    fun `a background run whose pull fails leaves the gate shut, and says the data is this phone's`() {
        backend.answer("GET /sync/full", 503)

        runWorker()

        assertFalse(rig.gate.hasPulled(signIn))
        assertEquals(SyncSource.LOCAL, w.appPrefs.lastSyncSource.value)
    }

    @Test
    fun `a background pass with TigerSync off sends nothing, and says no sync was attempted`() = runBlocking {
        w.appPrefs.cloudSyncEnabled = false
        w.appPrefs.setLastSyncSource(SyncSource.BACKEND)
        val from = backend.endpoints().size

        assertEquals(SyncOutcome.SKIPPED, rig.synchronizer.sync(SyncMode.BACKGROUND, signIn))

        assertEquals(emptyList<String>(), backend.endpoints().drop(from))
        assertEquals(SyncSource.NONE, w.appPrefs.lastSyncSource.value)
    }

    @Test
    fun `a background pass answers the snapshot's roster requests before it ends, Home's later`() = runBlocking {
        val asked = mutableListOf<String>()
        val roster = homeSyncRig(context, w, backend.client, idleScope, termSync = idleTermSync(w, asked))
        val requests = JSONObject(snapshot()).put("roster_requests", JSONArray().put("1141")).toString()

        backend.answer("GET /sync/full", 200, requests)
        assertEquals(SyncOutcome.SYNCED, roster.synchronizer.sync(SyncMode.BACKGROUND, signIn))
        assertEquals(listOf("1141"), asked)

        // Home's goes on the application scope, which never runs here.
        asked.clear()
        backend.answer("GET /sync/full", 200, requests)
        roster.sync.pull(homeState(), signIn)
        assertEquals(emptyList<String>(), asked)
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

    @Test
    fun `a background pass takes the server's ignored courses with no screen to show them on`() = runBlocking {
        val term = w.courseService.currentSemesterCode()
        val snapshot = JSONObject(snapshot())
            .put("courses", JSONArray().put(JSONObject().put("course_no", "CS1001").put("course_name", "Kept").put("semester", term).put("moodle_id", "${term}CS1001")))
            .put(
                "course_overrides",
                JSONArray().put(
                    JSONObject().put("moodle_id", "${term}CS1001").put("course_no", "CS1001").put("semester", term)
                        .put("ignore_assignments", true).put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
                ),
            )
        backend.answer("GET /sync/full", 200, snapshot.toString())

        assertEquals(SyncOutcome.SYNCED, rig.synchronizer.sync(SyncMode.BACKGROUND, signIn))

        assertEquals(setOf("$term:CS1001"), w.dataCache.loadIgnoredCourses())
    }

    /**
     * A rig whose course-ignore step runs [whileIgnoring] once it has written the snapshot's
     * ignores, before its own requests (the `moodle_course_id` fill-in) go: a switch turned there
     * is turned after the pass read its policy, and before the steps after it read theirs.
     */
    private fun rigTurning(whileIgnoring: () -> Unit, asked: MutableList<String> = mutableListOf()) = homeSyncRig(
        context, w, backend.client, idleScope,
        ignores = CourseIgnoreSync(
            w.dataCache,
            sender = { _, _, _, _, _ -> true },
            effects = { whileIgnoring() },
            sends = { CourseIgnoreSync.syncsCourseIgnores(w.appPrefs) },
            clock = System::currentTimeMillis,
        ),
        termSync = idleTermSync(w, asked),
    )

    /** A snapshot whose ignore of the term's course changes this phone's, with the course's color and a roster request. */
    private fun ignoreAndColor(term: String) = JSONObject(snapshot())
        .put("courses", JSONArray().put(JSONObject().put("course_no", "CS1001").put("course_name", "Kept").put("semester", term).put("moodle_id", "${term}CS1001")))
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("moodle_id", "${term}CS1001").put("course_no", "CS1001").put("semester", term).put("color_hex", "#123456")
                    .put("ignore_assignments", true).put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
            ),
        )
        .put("roster_requests", JSONArray().put("1141"))
        .toString()

    @Test
    fun `a color switch turned off while the background ignores step runs keeps the snapshot's color off this phone`() = runBlocking {
        val term = w.courseService.currentSemesterCode()
        w.dataCache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "Kept", moodleIdNumber = "${term}CS1001")), term, signIn)
        val rig = rigTurning({ w.appPrefs.syncCourseColors = false })
        backend.answer("GET /sync/full", 200, ignoreAndColor(term))

        assertEquals(SyncOutcome.SYNCED, rig.synchronizer.sync(SyncMode.BACKGROUND, signIn))

        assertEquals("the ignores step ran", setOf("$term:CS1001"), w.dataCache.loadIgnoredCourses())
        assertEquals("applied after the switch went off", listOf<String?>(null), w.dataCache.loadCourses(term).map { it.customColorHex })
    }

    @Test
    fun `a course switch turned off while the background ignores step runs leaves the course list and its roster requests`() = runBlocking {
        val term = w.courseService.currentSemesterCode()
        val asked = mutableListOf<String>()
        val rig = rigTurning({ w.appPrefs.syncCourses = false }, asked)
        backend.answer("GET /sync/full", 200, ignoreAndColor(term))

        rig.synchronizer.sync(SyncMode.BACKGROUND, signIn)

        assertEquals("the ignores step ran", setOf("$term:CS1001"), w.dataCache.loadIgnoredCourses())
        assertEquals("answered after the switch went off", emptyList<String>(), asked)
        assertEquals("reconciled after the switch went off", emptyMap<String, List<String>>(), w.dataCache.loadServerKnownNos())
    }

    @Test
    fun `Home's fetch marks the course window's fetch once, and leaves a refused session to Home`() = runBlocking {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val steps = ForegroundSteps(
            w.appPrefs, SyncPolicies(w.appPrefs), w.dataCache, w.tokens,
            fetchSnapshot = { log += "fetch"; throw BackendException(BackendError(401, "session_revoked"), "refused") },
            pendingCourseAdds = rig.adds, pendingCourseDeletes = rig.deletes, firstPullGate = rig.gate, widgetUpdater = w.widgetUpdater,
            reenableCheck = ReenableCheck(
                w.appPrefs, SyncApiClient(w.tokens, w.appPrefs, backend.client), w.dataCache, w.semesterCatalog, rig.ignores, w.auth,
                w.sessions, idleScope, context,
            ),
            notificationSettingsSync = w.notificationSettingsSync, appScope = idleScope,
        )

        try {
            steps.fetch(signIn, DataCache.CourseSync(0L) { log += "fetching"; 0L })
            fail("the refusal was swallowed")
        } catch (e: BackendException) {
            assertEquals(401, e.error.status)
        }
        assertEquals("one attempt: Home's pass runs again after its relogin", listOf("fetching", "fetch"), log.toList())
    }

    /** The background's steps over the rig's stores, its snapshot from [fetch] and its relogin [relogin]. */
    private fun backgroundSteps(
        fetch: suspend (SignIn) -> BackendSyncResult,
        relogin: suspend (SignIn) -> Boolean,
    ) = BackgroundSteps(
        w.appPrefs, SyncPolicies(w.appPrefs), w.dataCache, w.tokens, fetch, relogin, rig.adds, rig.gate,
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

    private fun homeState() = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    private companion object {
        const val STUDENT = "B00015999"
    }
}
