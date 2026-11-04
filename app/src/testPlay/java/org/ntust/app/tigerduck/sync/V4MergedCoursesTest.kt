package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
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
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The courses a `/v4` snapshot merged down, as the device check on PR V found them: a freshly
 * signed-in phone takes each term's courses from the snapshot before the school's roster answers,
 * and keeps each as a manual row. Such a copy is no course added by hand. Its delete goes with no
 * kind, so the backend decides from its row and hides a school course rather than take back a
 * hand-add it never had; and a term's upload names it in its adds only when its row was added by
 * hand, on whichever device. Over the app's own clients, queues and synchronizer ([appSyncRig]),
 * the class table's delete and add driven through the calls it makes (`ClassTableWiringTest`).
 * Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V4MergedCoursesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val rules = V4WriteRules()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: AppSyncRig
    private lateinit var signIn: SignIn

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
        AppClock.setOverride(ClockOverride(IN_TERM.toInstant(ZoneOffset.ofHours(8)).toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
        val prefs = AppPreferences(context)
        prefs.announcementApiBaseUrlOverride = STORED
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        context.getSharedPreferences("academic_calendar", Context.MODE_PRIVATE).edit()
            .putString("payload", """{"revision":1,"semesters":[{"code":"$TERM","start":"2026-09-01","end":"2027-01-31"}],"holidays":[]}""")
            .commit()
        val clock = SyncClock(SyncClockStore(context), prefs)
        val client = appBackendClient(prefs, clock, backend.client, rules)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client, backendLogin = client)
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS))
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE, issuedBy = STORED, studentId = STUDENT)
        }
        signIn = w.sessions.school.signIns.current
        assertEquals(TERM, w.dataCache.currentSemesterCode())
        w.appPrefs.semesterCatalogTerms = listOf(TERM)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        AppClock.setOverride(null)
    }

    private fun row(no: String, source: String = "ntust_portal", addedHlc: Long? = null) = JSONObject()
        .put("id", no.hashCode().toLong() and 0xFFFF).put("semester", TERM).put("course_key", key(no)).put("course_no", no)
        .put("course_name", "Course $no").put("moodle_id", "$TERM$no").put("source", source).put("enrollment_status", "enrolled")
        .put("added_hlc", addedHlc ?: JSONObject.NULL)

    /** A `/v4` snapshot holding [rows] of [TERM] and nothing else. */
    private fun snapshot(vararg rows: JSONObject) = JSONObject()
        .put("current_revision", 7)
        .put("server_hlc", SERVER_HLC)
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put("courses", JSONArray().apply { rows.forEach(::put) })
        .put("course_overrides", JSONArray())
        .put("course_tombstones", JSONArray())
        .put("term_resets", JSONObject())
        .put("holiday_overrides", JSONArray())
        .toString()

    private fun key(no: String) = "client:$TERM:$no"

    /** The pull of a freshly signed-in phone, which takes the term's courses from [rows]. */
    private suspend fun pullFresh(vararg rows: JSONObject) {
        backend.answer("GET /v4/sync/full", 200, snapshot(*rows))
        rig.home.sync.pull(state, signIn)
        assertEquals("merged from the snapshot", rows.map { it.getString("course_no") }.sorted(), w.dataCache.loadCourses(TERM).map { it.courseNo }.sorted())
    }

    private fun uploadTaken(vararg nos: String) = backend.answer(
        "POST /v4/sync/courses/upload", 200,
        """{"upserted":${nos.size},"skipped_tombstoned":0,"accepted_keys":[${nos.joinToString(",") { "\"${key(it)}\"" }}]}""",
    )

    /**
     * The class table's delete of [courseNo] with its tombstone, through the calls
     * `ClassTableViewModel.deleteCourse` makes, in its order: the delete kept, its hide marked, the
     * term saved without it, the delete sent, and the term's upload.
     */
    private suspend fun deleteOnTheClassTable(courseNo: String) {
        val courses = w.dataCache.loadCourses(TERM)
        val deleted = courses.firstOrNull { it.courseNo == courseNo }
        val updated = courses.filter { it.courseNo != courseNo }
        backend.answer("DELETE /v4/sync/courses/${key(courseNo)}", 200, """{"deleted":1,"applied":true,"hlc":${SERVER_HLC + 1_000}}""")
        uploadTaken(*updated.map { it.courseNo }.toTypedArray())
        val queuedAt = rig.home.deletes.stamp()
        assertTrue(rig.home.deletes.keep(TERM, courseNo, queuedAt, signIn))
        w.dataCache.hideDeletedCourse(courseNo, TERM, deleted, signIn)
        assertTrue(w.dataCache.saveCourses(updated, TERM, signIn))
        rig.home.deletes.sendKept(TERM, courseNo, signIn)
        rig.pushApi.uploadCourses(updated, TERM, signIn)
    }

    /** The class table's add of [course] by hand, through the calls `ClassTableViewModel.addCourse` makes. */
    private suspend fun addOnTheClassTable(course: Course) {
        val updated = w.dataCache.loadCourses(TERM) + course.copy(isManual = true)
        backend.answer(
            "POST /v4/sync/courses/upload", 200,
            """{"upserted":1,"skipped_tombstoned":0,"accepted_keys":["${key(course.courseNo)}"],""" +
                """"adds":[{"course_key":"${key(course.courseNo)}","applied":true,"hlc":${SERVER_HLC + 2_000}}]}""",
        )
        val addedAt = rig.home.deletes.stamp()
        w.dataCache.addCourseBack(course.courseNo, TERM, signIn)
        assertTrue(w.dataCache.saveCourses(updated, TERM, signIn))
        rig.home.deletes.forget(TERM, course.courseNo, addedAt, signIn)
        rig.home.adds.send(updated, TERM, course.courseNo, addedAt = addedAt, signIn = signIn)
        w.dataCache.courseAddSent(course.courseNo, TERM)
    }

    private fun uploads(): List<JSONObject> = backend.received("POST /v4/sync/courses/upload").map { JSONObject(it.body) }

    private fun JSONObject.courseNos(): List<String> = getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_no") } }

    private fun JSONObject.adds(): List<String> = optJSONArray("adds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_key") } }.orEmpty()

    private fun deletes(): List<String> = backend.endpoints().filter { it.startsWith("DELETE /v4/sync/courses/") }

    @Test
    fun `a school course merged from the snapshot of a fresh sign-in goes with no kind once deleted`() = runBlocking {
        pullFresh(row("XA0000001"), row("XA0000002"))

        deleteOnTheClassTable("XA0000001")

        assertEquals(listOf("DELETE /v4/sync/courses/${key("XA0000001")}"), deletes())
        val query = backend.received("DELETE /v4/sync/courses/${key("XA0000001")}").single().query
        assertTrue("the delete goes stamped: $query", Regex("""^hlc=\d+$""").matches(query))
        assertEquals(emptyList<String>(), rules.refused)
    }

    @Test
    fun `the term's upload after a delete names no school course merged from the snapshot, and still names one added here`() =
        runBlocking {
            pullFresh(row("XA0000001"), row("XA0000002"), row("XA0000003"))
            addOnTheClassTable(Course(courseNo = "XH0000009", courseName = "Added here"))

            deleteOnTheClassTable("XA0000001")

            val (added, afterDelete) = uploads()
            assertEquals(listOf("XH0000009"), added.courseNos())
            assertEquals(listOf(key("XH0000009")), added.adds())
            assertEquals(listOf("XA0000002", "XA0000003", "XH0000009"), afterDelete.courseNos().sorted())
            assertEquals(listOf(key("XH0000009")), afterDelete.adds())
            assertEquals(emptyList<String>(), rules.refused)
        }

    @Test
    fun `a course another device added by hand, merged from the snapshot, is still named in the term's upload`() = runBlocking {
        pullFresh(row("XA0000001"), row("XA0000002"), row("XE0000005", source = "user_added", addedHlc = SERVER_HLC - 5_000))

        deleteOnTheClassTable("XA0000001")

        val upload = uploads().single()
        assertEquals(listOf("XA0000002", "XE0000005"), upload.courseNos().sorted())
        assertEquals(listOf(key("XE0000005")), upload.adds())
    }

    /**
     * A phone updated from the released build holds copies it merged on `/v3`, which kept nothing
     * of their rows. The first `/v4` pull that lists one tells it apart: the copy of a row another
     * device added by hand is named in the term's upload, a school course's is not.
     */
    @Test
    fun `a copy an older build merged of a row added by hand elsewhere is named once a pull lists it`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        val merged = { no: String -> Course(courseNo = no, courseName = "Course $no", moodleIdNumber = "$TERM$no", isManual = true) }
        assertTrue(w.dataCache.saveCourses(listOf(merged("XA0000002"), merged("XE0000005")), TERM, signIn))
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XA0000002"), row("XE0000005", source = "user_added", addedHlc = SERVER_HLC - 5_000)))
        rig.home.sync.pull(state, signIn)
        uploadTaken("XA0000002", "XE0000005")

        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)

        val upload = uploads().last()
        assertEquals(listOf("XA0000002", "XE0000005"), upload.courseNos().sorted())
        assertEquals(listOf(key("XE0000005")), upload.adds())
        assertEquals(emptyList<String>(), rules.refused)
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val STORED = "http://127.0.0.1:9/v3"
        const val TERM = "1151"
        const val SERVER_HLC = 1_793_000_000_000_000L
        val IN_TERM: LocalDateTime = LocalDateTime.of(2026, 10, 15, 10, 0)
        const val FIRST_RUN_MS = 1_760_000_000_000L
    }
}
