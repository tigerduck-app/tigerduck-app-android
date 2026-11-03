package org.ntust.app.tigerduck.sync

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.accountEventStream
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.RecordedSelection
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.util.Date

/**
 * When an upload derived from a school or Moodle fetch goes (`sync-order`, "A refresh reads the
 * backend before it uploads school data"; on `/v4` only, on `/v3` at once), against a scripted backend: the background's fetch of a
 * term as the app wires it, and the assignments, through [SchoolUploads] over a real first-pull
 * gate. Waiting uploads run on [Dispatchers.Default] and the cache writes on IO, so each may land
 * at any moment; every assertion waits for what it reads. Made-up account and course numbers.
 */
class SchoolUploadsTest {

    private val context = JvmContext()
    private val backend = ScriptedBackend()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = accountEventStream()
    private lateinit var cache: DataCache
    private lateinit var api: PushApiClient
    private lateinit var tokens: V3TokenStore
    private lateinit var auth: AuthTokenManager
    private lateinit var prefs: AppPreferences
    private lateinit var gate: FirstPullGate
    private lateinit var uploads: SchoolUploads

    /** What course selection answers. */
    private var answer = listOf(CS1, CS2)

    private val sources = object : TermCourseSync.Sources {
        override fun servesSelection(semester: String) = semester == TERM
        override suspend fun selection(studentId: String, password: String, fresh: Boolean) = answer
        override suspend fun moodle(): List<MoodleEnrolledCourse> = emptyList()
        override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course =
            Course.fromSchedule(courseNo, courseNo, classroom = "IB-202", schedule = mapOf(2 to listOf("5")), moodleIdNumber = "$semester$courseNo")
    }

    private val signIn get() = auth.session.signIns.current

    private val adds by lazy { PendingCourseAdds(cache, api) }

    @Before
    fun setUp() {
        // The gate holds school uploads on /v4 alone, whose base no build speaks yet; each /v3 test sets /v3.
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v4" }
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-school-uploads-test", Context.MODE_PRIVATE))
        tokens.save("access-a", "refresh-a", Long.MAX_VALUE)
        auth = AuthTokenManager(tokens, backend.client, prefs, "device-1")
        api = PushApiClient(backend.client, prefs, auth)
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        gate = FirstPullGate(events, appScope)
        uploads = SchoolUploads(gate, appScope, cache, ::speaks) { assignments, signIn -> api.uploadAssignments(assignments, signIn) }
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** The API the base speaks as each upload decides, as the app reads it. */
    private fun speaks() = BackendApi.of(resolveAnnouncementEndpoint(prefs).url.trimEnd('/'))

    /** The app's wiring of [TermCourseSync] but its sources: the upload, as the app's, after the first pull on /v4. */
    private fun termSync() = TermCourseSync(
        cache, sources,
        uploader = TermCourseSync.Uploader { courses, semester, resend, rosterTerms, signIn ->
            api.uploadCourses(courses, semester, signIn, resend = resend, rosterTerms = rosterTerms)
        },
        waitingAdds = { emptySet() },
        uploading = { block -> adds.uploading(block) },
        courseLocale = { "zh" },
        uploadFailed = {},
        uploads = uploads,
    )

    /** Each course upload's courses and `roster_terms`, in order. */
    private fun termUploads() = backend.received(UPLOAD).map { request ->
        val body = JSONObject(request.body)
        val courses = body.getJSONArray("courses")
        val terms = body.optJSONArray("roster_terms")
        List(courses.length()) { courses.getJSONObject(it).getString("course_no") } to
            terms?.let { List(it.length()) { i -> it.getString(i) } }
    }

    /** Each assignments upload's ids, in order. */
    private fun assignmentUploads() = backend.received(ASSIGNMENTS).map { request ->
        val items = JSONObject(request.body).getJSONArray("assignments")
        List(items.length()) { items.getJSONObject(it).getInt("moodle_assignment_id") }
    }

    private fun assignment(id: String) = Assignment(
        assignmentId = id, courseNo = CS1, courseName = "DS", title = "A$id", dueDate = Date(1_790_600_000_000L),
    )

    /**
     * Until [count] uploads wait, their [parked] runs at the gate (one replaced still parks), so
     * none can go before the test says.
     */
    private suspend fun untilParked(count: Int, parked: Int = count) {
        withTimeoutOrNull(10_000) { while (gate.waiting != parked || uploads.waitingUploads != count) delay(1) }
        assertEquals("runs parked at the gate", parked, gate.waiting)
        assertEquals("uploads waiting", count, uploads.waitingUploads)
    }

    private suspend fun until(what: String, done: () -> Boolean) {
        withTimeoutOrNull(10_000) { while (!done()) delay(1) }
        assertEquals(what, true, done())
    }

    /** The school's sign-out as its plan tells it: the sign-in ends, then the event goes out. */
    private fun signOut() {
        val ended = signIn
        auth.logout()
        events.tryEmit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, ended, address = null))
    }

    /** The next student, after a sign-out: a sign-in opened, with a pair of its own. */
    private suspend fun nextSignIn() {
        cache.clearAllUserData()
        auth.session.open()
        tokens.save("access-b", "refresh-b", Long.MAX_VALUE)
    }

    // --- /v3: no gate (the coordinator's ruling, fix round 3) ---

    /** Each `/v3` course upload's courses and `roster_terms`, in order. */
    private fun v3TermUploads() = backend.received(V3_UPLOAD).map { request ->
        val body = JSONObject(request.body)
        val courses = body.getJSONArray("courses")
        val terms = body.optJSONArray("roster_terms")
        List(courses.length()) { courses.getJSONObject(it).getString("course_no") } to
            terms?.let { List(it.length()) { i -> it.getString(i) } }
    }

    /**
     * On `/v3` nothing waits for the first pull: the class table's and TermCourseSync's term, and
     * the assignments, go at once, in the caller, as on dev, though this process has not pulled.
     * `/v3`'s absence rule hides a fetched course the server has never carried, so an upload held
     * past the pull would fight it.
     */
    @Test
    fun `on v3 a fetch's uploads go at once, though this process has not pulled`() = runBlocking {
        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        assertEquals("TermCourseSync's term", listOf(listOf(CS1, CS2) to listOf(TERM)), v3TermUploads())

        val classTable = listOf(Course(courseNo = CS3, courseName = "School $CS3", moodleIdNumber = "$OTHER$CS3"))
        cache.saveCourses(classTable, OTHER, signIn)
        uploads.term(signIn, OTHER, classTable, listOf(CS3), { block -> adds.uploading(block) }, stillWanted = { true }) { upload ->
            api.uploadCourses(upload.courses, OTHER, signIn, rosterTerms = upload.rosterTerms)
        }
        assertEquals("the class table's term", listOf(CS3) to listOf(OTHER), v3TermUploads().last())

        uploads.assignments(listOf(assignment("701")), signIn)
        assertEquals(1, backend.received(V3_ASSIGNMENTS).size)
        assertEquals("nothing waits", 0, uploads.waitingUploads)
        assertFalse(gate.hasPulled(signIn))
    }

    @Test
    fun `the school answers first, and its upload goes after the pull`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        assertEquals("before the pull", emptyList<Any>(), termUploads())

        gate.notePull(signIn)

        until("the term went up after the pull") { termUploads().size == 1 }
        assertEquals(listOf(listOf(CS1, CS2) to listOf(TERM)), termUploads())
        assertEquals("Bearer access-a", backend.received(UPLOAD).single().authorization)
    }

    @Test
    fun `the backend is unreachable, and nothing derived goes`() = runBlocking {
        cache.saveAssignments(listOf(assignment("701")), signIn)
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        uploads.assignments(listOf(assignment("701")), signIn)
        untilParked(2)
        // Long enough for anything not held to have reached the scripted backend.
        delay(300)

        assertEquals(emptyList<Any>(), termUploads())
        assertEquals(emptyList<Any>(), assignmentUploads())
        assertEquals("the fetch still committed its term", listOf(CS1, CS2), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `a new sign-in sends no course upload before its first pull`() = runBlocking {
        gate.notePull(signIn)
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        assertEquals("the first sign-in had pulled: at once", 1, termUploads().size)
        signOut()
        nextSignIn()
        answer = listOf(CS1)

        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        assertEquals("before the new sign-in's pull", 1, termUploads().size)

        gate.notePull(signIn)
        until("the new sign-in's term went up after its pull") { termUploads().size == 2 }
        assertEquals(listOf(CS1) to listOf(TERM), termUploads().last())
        assertEquals("Bearer access-b", backend.received(UPLOAD).last().authorization)
    }

    /** `sync-order`, "A local edit SHALL be sent at once, without waiting for a pull". */
    @Test
    fun `a local edit goes at once`() = runBlocking {
        val added = Course(courseNo = HAND, courseName = "Added here", isManual = true)
        cache.saveCourses(listOf(added), TERM, signIn)

        adds.send(listOf(added), TERM, HAND, addedAt = 1_800_000_000_000L, signIn = signIn)

        assertFalse("this process has not pulled", gate.hasPulled(signIn))
        assertEquals(listOf(listOf(HAND)), termUploads().map { it.first })
        assertEquals(0, uploads.waitingUploads)
    }

    /**
     * Two fetches of the term before the pull, and the pull's own change to the term: once it
     * lands, one upload goes, of the term as this phone then holds it.
     */
    @Test
    fun `a waiting term goes once, as this phone holds it when the pull lands`() = runBlocking {
        val sync = termSync()
        sync.sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        sync.sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1, parked = 2)
        // What the pull merges in: a course another device added by hand.
        cache.saveCourses(cache.loadCourses(TERM) + Course(courseNo = HAND, courseName = "Added elsewhere", isManual = true), TERM, signIn)

        gate.notePull(signIn)

        until("the term went up") { termUploads().isNotEmpty() }
        delay(300)
        assertEquals(listOf(listOf(CS1, CS2, HAND) to listOf(TERM)), termUploads())
    }

    /**
     * Waiting, the term goes under the adds' send lock again, as the fetch's own upload did: a
     * course delete or a term reset under way when the pull lands goes first, and the term is read
     * after it.
     */
    @Test
    fun `a waiting term goes in its turn behind a delete or reset under way, and reads the term after it`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        val resetting = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val reset = appScope.launch {
            adds.removing {
                holding.complete(Unit)
                resetting.await()
                cache.saveCourses(cache.loadCourses(TERM).filter { it.courseNo != CS2 }, TERM, signIn)
            }
        }
        withTimeoutOrNull(10_000) { holding.await() }

        gate.notePull(signIn)
        until("the waiting upload left the gate") { gate.waiting == 0 }
        delay(300)
        assertEquals("not past the reset under way", emptyList<Any>(), termUploads())

        resetting.complete(Unit)
        reset.join()
        until("the term went up") { termUploads().isNotEmpty() }
        assertEquals(listOf(CS1), termUploads().single().first)
    }

    /**
     * Codex on PR R, finding 2: Home's fetch records a later course selection answer, the same
     * courses, before the first pull, and uploads no roster of its own. The waiting term still
     * goes once the pull lands, as the term's roster.
     */
    @Test
    fun `a waiting term still goes once a later answer that uploads nothing is recorded`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        cache.recordSelectionRoster(TERM, listOf(CS1, CS2), signIn)

        gate.notePull(signIn)

        until("the term went up") { termUploads().isNotEmpty() }
        assertEquals(listOf(listOf(CS1, CS2) to listOf(TERM)), termUploads())
    }

    /**
     * Codex on PR R, fix round 1, finding 2: Home records a newer course selection answer, one that
     * lists a course this phone has not got, and is still looking its courses up when the first
     * pull lets the waiting term go. The term goes with the answer it was committed for, as the
     * fetch's at-once upload went on dev, not paired with the newer answer, whose fetch commits no
     * term yet; Home's commit sends no course upload, as on dev.
     */
    @Test
    fun `a waiting term goes with the answer it was committed for while a newer answer's fetch has not committed`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        // Home's fetch, held between recording its answer and committing the term.
        cache.recordSelectionRoster(TERM, listOf(CS1, CS3), signIn)

        gate.notePull(signIn)

        until("the term went up") { termUploads().isNotEmpty() }
        delay(300)
        assertEquals(listOf(listOf(CS1, CS2) to listOf(TERM)), termUploads())
    }

    /**
     * A later answer that dropped a course, recorded and committed as Home's fetch does before the
     * first pull, its commit told: the waiting term goes with that answer's roster, of the term as
     * it stands.
     */
    @Test
    fun `a waiting term goes with the answer of the fetch that committed it last`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        val newer = cache.recordSelectionRoster(TERM, listOf(CS1), signIn) as RecordedSelection.Kept
        cache.saveCourses(cache.loadCourses(TERM).filter { it.courseNo == CS1 }, TERM, signIn)
        uploads.committedFor(signIn, TERM, newer.answer)

        gate.notePull(signIn)

        until("the term went up") { termUploads().isNotEmpty() }
        assertEquals(listOf(listOf(CS1) to listOf(TERM)), termUploads())
    }

    @Test
    fun `a waiting upload is dropped when its sign-in ends, and the next sign-in's fetch sends its own`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)

        signOut()
        until("the departing sign-in's upload let go") { uploads.waitingUploads == 0 && gate.waiting == 0 }
        nextSignIn()
        gate.notePull(signIn)
        delay(300)
        assertEquals("nothing of the departing sign-in's", emptyList<Any>(), termUploads())

        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        assertEquals(listOf(listOf(CS1, CS2) to listOf(TERM)), termUploads())
        assertEquals("Bearer access-b", backend.received(UPLOAD).single().authorization)
    }

    /**
     * Two writes' uploads waited, the older asked for last, and the scope of the caller that asked
     * first was cancelled since. Once the pull lands, the assignments go once, as stored: the
     * newest written.
     */
    @Test
    fun `waiting assignments go once, as stored when the pull lands, whoever asked`() = runBlocking {
        cache.saveAssignments(listOf(assignment("701"), assignment("702")), signIn)
        val caller = CoroutineScope(Job())
        caller.launch { uploads.assignments(listOf(assignment("701"), assignment("702")), signIn) }.join()
        caller.cancel()
        uploads.assignments(listOf(assignment("701")), signIn)
        untilParked(1, parked = 2)

        gate.notePull(signIn)

        until("the assignments went up") { assignmentUploads().isNotEmpty() }
        delay(300)
        assertEquals(listOf(listOf(701, 702)), assignmentUploads())
    }

    /**
     * Codex on PR R, finding 5: a released waiting upload reads the assignments, then pauses before
     * it sends them; a newer status write stores others and uploads them at once. The released one
     * must not land after the newer, or the backend keeps the older list.
     */
    @Test
    fun `a released upload of the assignments never lands after a newer one sent at once`() = runBlocking {
        val sent = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val released = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var calls = 0
        val held = SchoolUploads(gate, appScope, cache, ::speaks) { assignments, _ ->
            // The first send, the released one, pauses with what it read.
            if (synchronized(sent) { calls++ } == 0) {
                released.complete(Unit)
                resume.await()
            }
            sent += assignments.map { it.assignmentId }
        }
        cache.saveAssignments(listOf(assignment("701")), signIn)
        held.assignments(listOf(assignment("701")), signIn)
        withTimeoutOrNull(10_000) { while (gate.waiting != 1) delay(1) }
        gate.notePull(signIn)
        withTimeoutOrNull(10_000) { released.await() }

        // A newer write, sent at once now the gate is open.
        val newer = listOf(assignment("701"), assignment("702"))
        cache.saveAssignments(newer, signIn)
        val atOnce = appScope.launch { held.assignments(newer, signIn) }
        delay(300)
        resume.complete(Unit)
        withTimeoutOrNull(10_000) { atOnce.join() }

        until("both went") { sent.size == 2 }
        assertEquals("the last to land is the newest list", listOf("701", "702"), sent.last())
    }

    @Test
    fun `an upload asked for once this process has pulled goes at once, in its caller`() = runBlocking {
        gate.notePull(signIn)
        var failed: Exception? = null
        backend.answer(ASSIGNMENTS, 500)

        uploads.assignments(listOf(assignment("701")), signIn) { failed = it }

        assertEquals(listOf(listOf(701)), assignmentUploads())
        assertEquals("the failure reached the caller", true, failed != null)
        assertEquals(0, uploads.waitingUploads)
    }

    private companion object {
        const val UPLOAD = "POST /v4/sync/courses/upload"
        const val V3_UPLOAD = "POST /sync/courses/upload"
        const val V3_ASSIGNMENTS = "POST /sync/assignments/upload"
        const val OTHER = "1152"
        const val ASSIGNMENTS = "POST /v4/sync/assignments/upload"
        const val TERM = "1151"
        const val STUDENT = "B00015999"
        const val PASSWORD = "not-a-password"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
        const val CS3 = "XC0000003"
        const val HAND = "XH0000008"
    }
}
