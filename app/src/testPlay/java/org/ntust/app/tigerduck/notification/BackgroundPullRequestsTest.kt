package org.ntust.app.tigerduck.notification

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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The requests the background worker's sync with the backend sends, in order, as they reach the
 * backend: what waits in each queue, the snapshot, then what the worker's narrower pass asks for
 * (C2): the current term only, no marks of a first sync, no course reset, and the settings pull.
 * Pinned so moving the pass into the synchronizer sends the same bytes on `/v3`. Made-up
 * accounts and course numbers; times in a body, which the queues take from the clock, read as
 * `<time>`. The courses and the assignments are left not due, so the run asks the school nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackgroundPullRequestsTest {

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
    private lateinit var settings: NotificationSettingsSync
    private lateinit var signIn: SignIn
    private lateinit var term: String

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        rig = homeSyncRig(context, w, backend.client, idleScope)
        settings = settingsSyncAt(context, w, backend.client, idleScope)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.courseService.currentSemesterCode()
        // The school's data was fetched a minute ago, so only the backend sync is due.
        val aMinuteAgo = System.currentTimeMillis() - 60_000
        w.academicCalendar.current().currentTerm(AppClock.localDateTime().toLocalDate())
            ?.let { w.appPrefs.school.backgroundCoursesTerm.set(it.code, signIn) }
        w.appPrefs.school.backgroundCoursesSyncedAtMs.set(aMinuteAgo, signIn)
        w.appPrefs.school.schoolDataSyncedAtMs.set(aMinuteAgo, signIn)
        w.appPrefs.semesterCatalogTerms = listOf(OTHER_TERM, term)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** Runs the worker off the main thread, which it posts to, and runs what it posts there meanwhile. */
    private fun runWorker(): ListenableWorker.Result {
        val worker = backgroundSyncWorker(context, w, rig, settings, idleScope)
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

    /** Each request after the first [from]: its method and path, token, protocol header and body. */
    private fun sentSince(from: Int): List<String> = backend.endpoints().indices.drop(from).map { i ->
        val endpoint = backend.endpoints()[i]
        val request = backend.received(endpoint).let { all ->
            // The how-many-th request to this endpoint the i-th one is.
            all[backend.endpoints().subList(0, i).count { it == endpoint }]
        }
        val body = request.body.replace(Regex("""\d{12,}"""), "<time>")
        "$endpoint | ${request.authorization} | ${request.protocol} | $body"
    }

    private fun snapshot(rows: Boolean, resetAt: String? = null) = JSONObject()
        .apply { if (resetAt != null) put("courses_reset_at", resetAt) }
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put(
            "courses",
            if (!rows) JSONArray() else JSONArray().put(
                JSONObject().put("course_no", COURSE).put("course_name", "Kept").put("semester", term).put("moodle_id", moodleId()),
            ),
        )
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("moodle_id", moodleId()).put("course_no", COURSE).put("semester", term).put("color_hex", "#123456"),
            ),
        )
        .put("current_revision", 42)
        .toString()

    private fun moodleId() = "$term$COURSE"

    /** One change waiting in each queue, its first send refused where it makes one; the cache as the tests share it. */
    private suspend fun oneChangeInEachQueue() {
        val kept = Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = moodleId())
        val added = Course(courseNo = ADDED, courseName = "Added here", isManual = true)
        w.dataCache.saveCourses(listOf(kept, added), term, signIn)
        // A term other than the current one, which the server holds nothing of: Home's sync uploads it.
        w.dataCache.saveCourses(listOf(Course(courseNo = LOCAL, courseName = "Only here")), OTHER_TERM, signIn)
        // A mark this phone holds, which an empty server takes as this phone's first sync of them.
        w.dataCache.saveIgnoredAssignments(setOf("7"), signIn)

        backend.answer("PATCH /sync/courses/${moodleId()}/override", 500)
        rig.ignores.setIgnored(term, COURSE, moodleId(), null, ignore = true, signIn = signIn)
        rig.marks.keep("8", "locally_completed", signIn)
        backend.answer("POST /sync/courses", 500)
        rig.adds.send(listOf(kept, added), term, ADDED, addedAt = 1L, signIn = signIn)
        assertTrue(rig.deletes.keep(term, GONE, queuedAt = 5L, signIn = signIn))
        rig.overrides.queue(
            listOf(PendingCourseOverrides.Change(moodleId(), colorHex = "#ABCDEF", semester = term, courseNo = COURSE)),
            rig.overrides.reserveVersion(), signIn,
        )
        backend.answer("PUT /sync/holiday-overrides/$HOLIDAY", 500)
        rig.holidays.send(HOLIDAY, signIn)
    }

    /** What waits, in the order the retries run: ignores, marks, adds, deletes, overrides, holidays. */
    private fun whatWaits() = listOf(
        """PATCH /sync/courses/${moodleId()}/override | Bearer access-1 | null | {"ignore_assignments":true,"hlc":<time>}""",
        """PATCH /sync/assignments/8/override | Bearer access-1 | null | {"local_status":"locally_completed","hlc":<time>}""",
        "POST /sync/courses/upload | Bearer access-1 | 2 | " +
            """{"courses":[${addRow()}],"course_overrides":[],"force_keys":["client:$term:$ADDED"],""" +
            """"adds":[{"course_key":"client:$term:$ADDED","hlc":1000}]}""",
        "DELETE /sync/courses/client:$term:$GONE | Bearer access-1 | null | ",
        """PATCH /sync/courses/${moodleId()}/override | Bearer access-1 | null | {"color_hex":"#ABCDEF","hlc":<time>}""",
        """PUT /sync/holiday-overrides/$HOLIDAY | Bearer access-1 | null | {"notify":false,"hlc":<time>}""",
    )

    private fun addRow() = """{"semester":"$term","course_no":"$ADDED","course_name":"Added here","credits":0.0,""" +
        """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""

    @Test
    fun `a background sync sends what waits, takes the snapshot, then pulls the settings, and nothing for another term or a first sync's marks`() {
        runBlocking { oneChangeInEachQueue() }
        backend.answer("GET /sync/full", 200, snapshot(rows = true))
        val from = backend.endpoints().size

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(whatWaits() + listOf("GET /sync/full | Bearer access-1 | null | ") + SETTINGS_PULL, sentSince(from))
        // Recorded by Home's sync alone: the categories it took, and the marks of a first sync.
        assertEquals(SyncSource.BACKEND, w.appPrefs.lastSyncSource.value)
        assertEquals(emptySet<String>(), w.appPrefs.school.syncedCategories.get())
        assertEquals(false, w.appPrefs.school.assignmentMarksSyncedOnce.get())
    }

    @Test
    fun `a background sync leaves a course reset made on another device to Home`() {
        runBlocking { oneChangeInEachQueue() }
        assertTrue(w.appPrefs.school.lastSeenCoursesResetAt.set("2026-01-01T00:00:00Z", signIn))
        backend.answer("GET /sync/full", 200, snapshot(rows = true, resetAt = "2026-02-01T00:00:00Z"))
        val from = backend.endpoints().size

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(whatWaits() + listOf("GET /sync/full | Bearer access-1 | null | ") + SETTINGS_PULL, sentSince(from))
        // Not remembered, so Home's next sync applies it; the hand-added course and its add stay.
        assertEquals("2026-01-01T00:00:00Z", w.appPrefs.school.lastSeenCoursesResetAt.get())
        assertEquals(setOf(ADDED), runBlocking { rig.adds.waitingIn(term) })
        assertTrue(runBlocking { w.dataCache.loadCourses(term) }.any { it.courseNo == ADDED })
    }

    @Test
    fun `a background sync uploads the current term when the server holds none of it`() {
        runBlocking { oneChangeInEachQueue() }
        backend.answer("GET /sync/full", 200, snapshot(rows = false))
        val from = backend.endpoints().size

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val keptRow = """{"semester":"$term","course_no":"$COURSE","course_name":"Kept","moodle_id":"${moodleId()}","credits":0.0,""" +
            """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""
        assertEquals(
            whatWaits() + listOf(
                "GET /sync/full | Bearer access-1 | null | ",
                "POST /sync/courses/upload | Bearer access-1 | null | " +
                    """{"courses":[$keptRow,${addRow()}],"course_overrides":[{"course_key":"client:$term:$COURSE","color_hex":"#123456"}]}""",
            ) + SETTINGS_PULL,
            sentSince(from),
        )
    }

    @Test
    fun `a background sync sends the color it moved off a course the server holds on another's`() {
        val first = Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = moodleId())
        val second = Course(courseNo = OTHER, courseName = "Other", moodleIdNumber = "$term$OTHER")
        runBlocking { w.dataCache.saveCourses(listOf(first, second), term, signIn) }
        // An older build left both on one color: the later pick, by course number, keeps it.
        val snapshot = JSONObject(snapshot(rows = true))
            .put(
                "courses",
                JSONArray()
                    .put(JSONObject().put("course_no", COURSE).put("course_name", "Kept").put("semester", term).put("moodle_id", moodleId()))
                    .put(JSONObject().put("course_no", OTHER).put("course_name", "Other").put("semester", term).put("moodle_id", "$term$OTHER")),
            )
            .put(
                "course_overrides",
                JSONArray()
                    .put(JSONObject().put("moodle_id", moodleId()).put("course_no", COURSE).put("semester", term).put("color_hex", "#123456"))
                    .put(JSONObject().put("moodle_id", "$term$OTHER").put("course_no", OTHER).put("semester", term).put("color_hex", "#123456")),
            )
        backend.answer("GET /sync/full", 200, snapshot.toString())
        val from = backend.endpoints().size

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(
            listOf("GET /sync/full | Bearer access-1 | null | ", MOVED_COLOR.replace("{id}", moodleId())) + SETTINGS_PULL,
            sentSince(from),
        )
    }

    private companion object {
        const val OTHER = "CS1002"

        /** The color the course that moved takes, as the class table's pick gives it one. */
        const val MOVED_COLOR = """PATCH /sync/courses/{id}/override | Bearer access-1 | null | {"color_hex":"#DDA0DD","hlc":<time>}"""

        const val STUDENT = "B00015999"
        const val OTHER_TERM = "1132"
        const val COURSE = "CS1001"
        const val ADDED = "AD1001"
        const val LOCAL = "LO1001"
        const val GONE = "GO1001"
        const val HOLIDAY = 3

        /** The worker's settings pull, which the backend here answers with no document. */
        val SETTINGS_PULL = listOf("GET /settings/notification | Bearer access-1 | null | ")
    }
}
