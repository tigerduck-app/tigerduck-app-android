package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * pull must not take the absence for a delete made elsewhere (`/v3`'s rule for a school course
 * the term's rows leave out), or the course is hidden for good and its upload leaves it out too.
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

    /** When set, the background's fetch holds its commit lock at its start until the second is completed. */
    private var fetchHold: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    private fun setUp(api: String) {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/$api"
        prefix = if (api == "v4") "/v4" else ""
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
            uploading = { block ->
                rig.adds.uploading {
                    fetchHold?.let { (entered, resume) ->
                        entered.complete(Unit)
                        resume.await()
                    }
                    block()
                }
            },
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

    // The scripted backend's endpoints name the version segment but /v3's.
    private var prefix = ""

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
        if (prefix == "/v4") snapshot.put("server_hlc", 1_793_000_000_000_000L).put("term_resets", JSONObject())
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

    @Test
    fun `on v3 the background's fetched course its upload waits for is not taken for one deleted elsewhere`() = runBlocking {
        setUp("v3")
        pullOver(::backgroundFetch)

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())

        gate.notePull(signIn)
        withTimeoutOrNull(10_000) { while (uploadedNos().isEmpty()) delay(5) }
        assertEquals("the term, the new course in it, went up after the pull", listOf(listOf(OLD, NEW)), uploadedNos().map { it.sorted() })
    }

    @Test
    fun `on v3 the class table's fetched course its upload waits for is not taken for one deleted elsewhere`() = runBlocking {
        setUp("v3")
        pullOver(::classTableFetch)

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
    }

    /** Absence only: a tombstone another device wrote still hides the course, as before. */
    @Test
    fun `on v3 a tombstone from another device still hides a course whose upload waits`() = runBlocking {
        setUp("v3")
        pullOver(::backgroundFetch, NEW)

        assertEquals(setOf(NEW), hidden())
    }

    /** `/v4` takes no absence for a delete: membership follows the tombstones (LWW D10). */
    @Test
    fun `on v4 a fetched course its upload waits for stays too`() = runBlocking {
        setUp("v4")
        pullOver(::backgroundFetch)

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
    }

    /**
     * Codex on PR R, fix round 1, finding 1: a fetch commits its term, then asks for the upload
     * that waits; a pull's course list step reading the term between the two saw the new course
     * and no upload waiting for it. [commitThenUpload] runs as the class table's commit does, under
     * the adds' send lock, the term saved, held there, then the upload asked for; [pull] runs
     * meanwhile. Whether it waits for the commit is read with a bound, not a race: on the base it
     * finishes at once, the course hidden.
     */
    private suspend fun pullDuringCommit(pull: suspend () -> Unit): Boolean = kotlinx.coroutines.coroutineScope {
        val saved = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val committed = listOf(OLD, NEW).map { Course(courseNo = it, courseName = "School $it", moodleIdNumber = "$term$it") }
        val commit = launch(Dispatchers.Default) {
            rig.adds.uploading {
                w.dataCache.saveCourses(committed, term, signIn)
                saved.complete(Unit)
                resume.await()
                uploads.term(signIn, term, committed, listOf(OLD, NEW), { block -> block() }, stillWanted = { true }) { upload ->
                    w.pushApi.uploadCourses(upload.courses, term, signIn, rosterTerms = upload.rosterTerms)
                }
            }
        }
        withTimeoutOrNull(10_000) { saved.await() }
        answerSnapshot()
        val pulling = launch(Dispatchers.Default) { pull() }
        val finished = withTimeoutOrNull(5_000) { pulling.join() } != null
        resume.complete(Unit)
        withTimeoutOrNull(10_000) {
            commit.join()
            pulling.join()
        }
        finished
    }

    @Test
    fun `on v3 Home's pull does not read a term between its fetch's commit and its upload's ask`() = runBlocking {
        setUp("v3")
        val finished = pullDuringCommit { rig.sync.pull(state, signIn) }

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
        assertEquals("the pull's course list waited for the commit and its upload's ask", false, finished)
    }

    @Test
    fun `on v3 the background's pull does not read a term between its fetch's commit and its upload's ask`() = runBlocking {
        setUp("v3")
        val finished = pullDuringCommit { rig.synchronizer.sync(SyncMode.BACKGROUND, signIn) }

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
        assertEquals("the pull's course list waited for the commit and its upload's ask", false, finished)
    }

    /** The background's term fetch commits and asks for its upload under the same lock, which the pull waits out. */
    @Test
    fun `on v3 Home's pull waits out the background's term fetch's commit`() = runBlocking {
        setUp("v3")
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fetchHold = entered to resume
        val fetch = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { backgroundFetch() }
        withTimeoutOrNull(10_000) { entered.await() }
        answerSnapshot()
        val pulling = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { rig.sync.pull(state, signIn) }
        val finished = withTimeoutOrNull(5_000) { pulling.join() } != null
        resume.complete(Unit)
        withTimeoutOrNull(10_000) {
            fetch.join()
            pulling.join()
        }

        assertEquals("hidden", emptySet<String>(), hidden())
        assertEquals(setOf(OLD, NEW), shown())
        assertEquals("the pull's course list waited for the fetch's commit", false, finished)
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val OLD = "XA0000001"
        const val NEW = "XN0000002"
    }
}
