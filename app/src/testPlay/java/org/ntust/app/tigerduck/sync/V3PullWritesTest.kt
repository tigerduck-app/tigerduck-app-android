package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a `/v3` pull writes on this phone from its snapshot, each reconciler's part, in Home's pass
 * and the background's: the marks and the conflict they leave, the ignored courses, the colors and
 * names, the holidays, the term's courses and hides, and the categories recorded as synced. Pinned
 * before last writer wins comes in beside them, and held unchanged after: a `/v3` pull reads none
 * of the stamps a `/v4` snapshot carries, which this one carries too. Made-up accounts and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V3PullWritesTest {

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
    private lateinit var term: String

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

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
        term = w.dataCache.currentSemesterCode()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun course(no: String, color: String? = null, manual: Boolean = false) =
        Course(courseNo = no, courseName = "Course $no", moodleIdNumber = "$term$no", customColorHex = color, isManual = manual)

    /** This phone before the pull: marks, an ignored course, colors, a name, a holiday and the term's courses. */
    private fun seed(syncedBefore: Boolean = true) = runBlocking {
        w.dataCache.saveIgnoredAssignments(setOf("7", "8", "11"), signIn)
        w.dataCache.saveMarkedCompletedAssignments(setOf("9"), signIn)
        w.appPrefs.school.assignmentMarksSyncedOnce.set(syncedBefore, signIn)
        w.dataCache.saveIgnoredCourses(setOf("$term:CS1001"), signIn)
        w.dataCache.saveCourseCustomNames(mapOf("CS1003" to mapOf("zh" to "Local Three")), signIn)
        w.academicCalendar.applySyncedOverrides(setOf(5))
        val courses = listOf(course("CS1001", "#111111"), course("CS1003", "#333333"), course("MAN01", manual = true))
        w.dataCache.saveCourses(courses, term, signIn)
        state.ignoredAssignmentIds.value = setOf("7", "8", "11")
        state.markedCompletedIds.value = setOf("9")
        state.allCourses.value = courses
    }

    private fun mark(id: Int, status: String, hlc: Long) =
        JSONObject().put("moodle_assignment_id", id).put("local_status", status).put("local_status_hlc", hlc)

    private fun row(no: String) = JSONObject().put("id", no.hashCode().toLong() and 0xFFFF).put("course_no", no)
        .put("course_name", "Course $no").put("semester", term).put("moodle_id", "$term$no").put("source", "roster")

    /** The backend's: some marks agree, one is contested, one is new; colors, a name, ignores, a holiday, rows and a hide. */
    private fun snapshot() = JSONObject()
        .put("server_hlc", 1_792_900_000_000_000L)
        .put("assignments", JSONArray())
        .put(
            "assignment_overrides",
            JSONArray().put(mark(7, "ignored", 1)).put(mark(9, "none", 2)).put(mark(10, "locally_completed", 3))
                .put(mark(11, "locally_completed", 4)),
        )
        .put("courses", JSONArray().put(row("CS1001")).put(row("CS1002")))
        .put(
            "course_overrides",
            JSONArray()
                .put(
                    JSONObject().put("moodle_id", "${term}CS1001").put("course_no", "CS1001").put("semester", term)
                        .put("color_hex", "#123456").put("color_hex_hlc", 5)
                        .put("custom_names", JSONObject().put("en", "Server One")).put("custom_names_hlc", JSONObject().put("en", 6))
                        .put("ignore_assignments", false).put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
                )
                .put(
                    JSONObject().put("moodle_id", "${term}CS1002").put("course_no", "CS1002").put("semester", term)
                        .put("ignore_assignments", true).put("ignore_assignments_updated_at", "2026-10-14T00:00:00Z"),
                ),
        )
        .put(
            "course_tombstones",
            JSONArray().put(
                JSONObject().put("course_key", "client:$term:CS1003").put("course_no", "CS1003").put("semester", term)
                    .put("deleted_at", "2026-10-20T02:00:00+00:00").put("hlc", 7).put("kind", "hide"),
            ),
        )
        .put("holiday_overrides", JSONArray().put(JSONObject().put("holiday_id", 7).put("notify", true).put("hlc", 8)))
        .put("current_revision", 42)
        .toString()

    @Test
    fun `Home's v3 pull writes each reconciler's part from the snapshot`() = runBlocking {
        seed()
        backend.answer("GET /sync/full", 200, snapshot())

        rig.sync.pull(state, signIn)

        // Marks: the server's, a mark it lacks dropped, the contested one at this phone's value.
        assertEquals(setOf("7", "11"), w.dataCache.loadIgnoredAssignments())
        assertEquals(setOf("10"), w.dataCache.loadMarkedCompletedAssignments())
        assertEquals(setOf("7", "11"), state.ignoredAssignmentIds.value)
        assertEquals(setOf("10"), state.markedCompletedIds.value)
        assertEquals(listOf("11"), state.conflicts.value.map { it.id })
        // Ignored courses: the server decides the terms it has rows for.
        assertEquals(setOf("$term:CS1002"), w.dataCache.loadIgnoredCourses())
        assertEquals(setOf("$term:CS1002"), state.ignoredCourseKeys.value)
        // Colors and names: the server's where it has a row, this phone's elsewhere.
        assertEquals(
            mapOf("CS1001" to "#123456", "MAN01" to null, "CS1002" to null),
            w.dataCache.loadCourses(term).associate { it.courseNo to it.customColorHex },
        )
        assertEquals(
            mapOf("CS1001" to mapOf("en" to "Server One"), "CS1003" to mapOf("zh" to "Local Three")),
            w.dataCache.loadCourseCustomNames(),
        )
        // Holidays: the snapshot's set.
        assertEquals(setOf(7), w.academicCalendar.optedInHolidayIds)
        // The term: another device's row merged, the hidden one gone and hidden, the hand-added one kept.
        assertEquals(setOf("$term:CS1003"), w.dataCache.loadDeletedCourseNos())
        assertEquals(listOf("CS1001", "MAN01", "CS1002"), w.dataCache.loadCourses(term).map { it.courseNo })
        assertEquals(SyncCategory.entries.mapTo(HashSet()) { it.stored }, w.appPrefs.school.syncedCategories.get())
        assertTrue(w.appPrefs.school.assignmentMarksSyncedOnce.get())
    }

    @Test
    fun `Home's first v3 pull with marks the server lacks sends them up and keeps them`() = runBlocking {
        seed(syncedBefore = false)
        backend.answer(
            "GET /sync/full",
            200,
            JSONObject(snapshot()).put("assignment_overrides", JSONArray()).toString(),
        )

        rig.sync.pull(state, signIn)

        assertEquals(setOf("7", "8", "11"), w.dataCache.loadIgnoredAssignments())
        assertEquals(setOf("9"), w.dataCache.loadMarkedCompletedAssignments())
        assertEquals(
            listOf("ignored", "ignored", "ignored", "locally_completed"),
            backend.received("PATCH /sync/assignments/7/override").map { JSONObject(it.body).getString("local_status") } +
                backend.received("PATCH /sync/assignments/8/override").map { JSONObject(it.body).getString("local_status") } +
                backend.received("PATCH /sync/assignments/11/override").map { JSONObject(it.body).getString("local_status") } +
                backend.received("PATCH /sync/assignments/9/override").map { JSONObject(it.body).getString("local_status") },
        )
        assertTrue(w.appPrefs.school.assignmentMarksSyncedOnce.get())
    }

    @Test
    fun `the background's v3 pull writes each reconciler's part from the snapshot`() {
        seed()
        backend.answer("GET /sync/full", 200, snapshot())

        offMain { rig.synchronizer.sync(SyncMode.BACKGROUND, signIn) }

        runBlocking {
            // Marks: the server's, with no conflict to leave.
            assertEquals(setOf("7"), w.dataCache.loadIgnoredAssignments())
            assertEquals(setOf("10", "11"), w.dataCache.loadMarkedCompletedAssignments())
            assertEquals(setOf("$term:CS1002"), w.dataCache.loadIgnoredCourses())
            // Over the current term's cached courses, the names stamped onto them.
            val cached = w.dataCache.loadCourses(term)
            assertEquals(
                mapOf("CS1001" to "#123456", "MAN01" to null),
                cached.associate { it.courseNo to it.customColorHex },
            )
            assertEquals(
                mapOf("CS1001" to mapOf("en" to "Server One"), "CS1003" to mapOf("zh" to "Local Three")),
                w.dataCache.loadCourseCustomNames(),
            )
            assertEquals(setOf(7), w.academicCalendar.optedInHolidayIds)
            // The current term: the hidden one gone and hidden, nothing merged down.
            assertEquals(setOf("$term:CS1003"), w.dataCache.loadDeletedCourseNos())
            assertEquals(listOf("CS1001", "MAN01"), cached.map { it.courseNo })
            assertEquals(emptySet<String>(), w.appPrefs.school.syncedCategories.get())
        }
    }

    /** [block] off the main thread, which the background's holidays step posts to, idled meanwhile. */
    private fun offMain(block: suspend () -> Unit) {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val run = thread.submit(Callable { runBlocking { block() } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!run.isDone) {
                check(System.nanoTime() < deadline) { "the background pass never finished" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            run.get()
        } finally {
            thread.shutdownNow()
        }
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
