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
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.util.Date

/**
 * When an upload derived from a school or Moodle fetch goes (`sync-order`, "A refresh reads the
 * backend before it uploads school data"), against a scripted backend: the background's fetch of a
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
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
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
        uploads = SchoolUploads(gate, appScope, cache) { assignments, signIn -> api.uploadAssignments(assignments, signIn) }
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** The app's wiring of [TermCourseSync] but its sources: the upload, as the app's, after the first pull. */
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

    @Test
    fun `a waiting term whose course selection answer a newer one replaced sends nothing`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)
        untilParked(1)
        cache.recordSelectionRoster(TERM, listOf(CS1), signIn)

        gate.notePull(signIn)

        until("the waiting upload let go") { uploads.waitingUploads == 0 && gate.waiting == 0 }
        delay(300)
        assertEquals(emptyList<Any>(), termUploads())
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
        const val UPLOAD = "POST /sync/courses/upload"
        const val ASSIGNMENTS = "POST /sync/assignments/upload"
        const val TERM = "1151"
        const val STUDENT = "B00015999"
        const val PASSWORD = "not-a-password"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
        const val HAND = "XH0000008"
    }
}
