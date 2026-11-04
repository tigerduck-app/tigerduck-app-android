package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.RecordedSelection
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Codex on PR R, finding 1: in a fresh process, a school fetch commits a course the student just
 * enrolled in and its upload waits for the first pull, so the backend does not list it yet. That
 * pull must not take the absence for a delete made elsewhere, or the course is hidden for good
 * and its upload leaves it out too.
 * The background's term fetch, and the class table's, as they ask for the upload; Home's pull over
 * the real stores. Made-up account and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FirstPullAbsenceTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig
    private lateinit var signIn: SignIn
    private lateinit var term: String
    private lateinit var gate: FirstPullGate
    private lateinit var uploads: SchoolUploads
    private lateinit var termSync: TermCourseSync

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    /** Over a backend at `/v4`. */
    private fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v4"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.dataCache.currentSemesterCode()
        w.appPrefs.semesterCatalogTerms = listOf(term)
        // The process's gate, shut: no pull has landed yet.
        gate = FirstPullGate(w.sessions, idleScope)
        uploads = SchoolUploads(gate, appScope, w.dataCache) { assignments, s -> w.pushApi.uploadAssignments(assignments, s) }
        termSync = TermCourseSync(
            w.dataCache,
            sources = object : TermCourseSync.Sources {
                override fun servesSelection(semester: String) = semester == term
                override suspend fun selection(studentId: String, password: String, fresh: Boolean) = listOf(OLD, NEW)
                override suspend fun moodle(): List<MoodleEnrolledCourse> = emptyList()
                override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?) =
                    Course(courseNo = courseNo, courseName = "School $courseNo", moodleIdNumber = "$semester$courseNo")
            },
            uploader = { courses, semester, resend, rosterTerms, s ->
                w.pushApi.uploadCourses(courses, semester, s, resend = resend, rosterTerms = rosterTerms)
            },
            waitingAdds = { emptySet() },
            uploading = { block -> block() },
            courseLocale = { "zh" },
            uploadFailed = {},
            uploads = uploads,
        )
        rig = homeSyncRig(context, w, backend.client, idleScope, termSync = termSync)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    // The version segment the requests' paths begin with, as the scripted backend names them.
    private val prefix = "/v4"

    private fun row(no: String) = JSONObject()
        .put("id", no.hashCode().toLong() and 0xFFFF).put("semester", term).put("course_key", "client:$term:$no").put("course_no", no)
        .put("course_name", "School $no").put("moodle_id", "$term$no").put("source", "roster").put("enrollment_status", "enrolled")

    private fun tombstone(no: String) = JSONObject()
        .put("course_key", "client:$term:$no").put("course_no", no).put("semester", term).put("deleted_at", "2026-10-20T02:00:00+00:00")
        .put("hlc", 40).put("kind", "hide")

    /** The backend's snapshot: [OLD] alone in the term, as before this phone's upload of [NEW]. */
    private fun answerSnapshot(vararg tombstones: String) {
        val snapshot = JSONObject()
            .put("current_revision", 3)
            .put("assignments", JSONArray())
            .put("assignment_overrides", JSONArray())
            .put("course_overrides", JSONArray())
            .put("courses", JSONArray().put(row(OLD)))
            .put("course_tombstones", JSONArray().apply { tombstones.forEach { put(tombstone(it)) } })
        // /v4's snapshot, which every base serves.
        snapshot.put("server_hlc", 1_793_000_000_000_000L).put("term_resets", JSONObject())
        backend.answer("GET $prefix/sync/full", 200, snapshot.toString())
    }

    private suspend fun hidden() = CourseTombstoneKeys.hiddenIn(term, w.dataCache.loadDeletedCourseNos())

    private suspend fun shown() = w.dataCache.loadCourses(term).map { it.courseNo }.toSet()

    /** The fetch's term, waiting at the gate, then the pull, then the gate opening. */
    private suspend fun pullOver(fetch: suspend () -> Unit, vararg tombstones: String) {
        fetch()
        withTimeoutOrNull(10_000) { while (gate.waiting != 1) delay(1) }
        assertEquals("the term waits for the first pull", 1, gate.waiting)
        answerSnapshot(*tombstones)
        rig.sync.pull(state, signIn)
    }

    private suspend fun backgroundFetch() {
        termSync.sync(STUDENT, "made-up-password", term, signIn = signIn)
    }

    /** As the class table's fetch commits a term and asks for its upload. */
    private suspend fun classTableFetch() {
        val committed = listOf(OLD, NEW).map { Course(courseNo = it, courseName = "School $it", moodleIdNumber = "$term$it") }
        w.dataCache.saveCourses(committed, term, signIn)
        uploads.term(signIn, term, committed, listOf(OLD, NEW), { block -> block() }, stillWanted = { true }) { upload ->
            w.pushApi.uploadCourses(upload.courses, term, signIn, rosterTerms = upload.rosterTerms)
        }
    }

    private fun uploadedNos(): List<List<String>> = backend.received("POST $prefix/sync/courses/upload").map { request ->
        val courses = JSONObject(request.body).getJSONArray("courses")
        List(courses.length()) { courses.getJSONObject(it).getString("course_no") }
    }

    /** The class table's fetch as the background's: its course stays through the first pull. */
    @Test
    fun `on v4 the class table's fetched course its upload waits for stays`() = runBlocking {
        setUp()
        pullOver(::classTableFetch)

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
    }

    /** `/v4` takes no absence for a delete: membership follows the tombstones (LWW D10). */
    @Test
    fun `on v4 a fetched course its upload waits for stays too`() = runBlocking {
        setUp()
        pullOver(::backgroundFetch)

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
    }

    /**
     * Codex on PR R, fix round 2, P1, on `/v4`: a fetch commits the new course, then a newer course
     * selection answer, recorded while it saved, makes it ask for no upload; the newer answer's
     * fetch is still looking its courses up as the first pull runs. The pull takes no absence for a
     * delete, so the course stays; the newer fetch's own upload, once it commits, is what goes, as
     * on dev, where the superseded fetch sent nothing either.
     */
    @Test
    fun `on v4 a fetch a newer answer stopped from asking for its upload loses no course to the first pull`() = runBlocking {
        setUp()
        val older = (w.dataCache.recordSelectionRoster(term, listOf(OLD, NEW), signIn) as RecordedSelection.Kept).answer
        val committed = listOf(OLD, NEW).map { Course(courseNo = it, courseName = "School $it", moodleIdNumber = "$term$it") }
        // The class table's commit, under its lock, its roster stored with the write.
        rig.adds.uploading {
            w.dataCache.updateCourses(term, signIn) { _, _ -> committed.also { w.dataCache.committedWith(term, older.courseNos, signIn) } }
        }
        // The newer answer, its fetch still looking up: the older one is no longer the latest, so it asks for nothing.
        w.dataCache.recordSelectionRoster(term, listOf(OLD, NEW), signIn)
        assertEquals(false, w.dataCache.isLatestAnswer(term, older))

        answerSnapshot()
        rig.sync.pull(state, signIn)

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())

        // The newer answer's fetch commits and asks for its upload, which waits for this process's gate.
        backgroundFetch()
        assertEquals("nothing went before the gate opened", emptyList<List<String>>(), uploadedNos())
        gate.notePull(signIn)
        withTimeoutOrNull(10_000) { while (uploadedNos().isEmpty()) delay(5) }
        assertEquals(listOf(listOf(OLD, NEW)), uploadedNos().map { it.sorted() })
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val OLD = "XA0000001"
        const val NEW = "XN0000002"
    }
}
