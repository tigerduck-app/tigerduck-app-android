package org.ntust.app.tigerduck.notification

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RosterRequestLog
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The background's fetch of a term, the worker's and a roster request's, against a backend that
 * reads `roster_terms` (3.10.1, tigerduck-app-android#595): course selection's answer goes up as
 * the term's roster, and a roster the backend asks for goes again even unchanged, past the upload
 * ledger, while one course selection does not answer sends nothing. As the iPhone's
 * `CourseRefreshUploadTests.requestedRosterResendsOnlyARoster` and
 * `UnchangedUploadTests.requestedRosterGoesAgainUnchanged` (tigerduck-app#914). Made-up course
 * numbers and account.
 */
class TermCourseSyncTest {

    private val context = JvmContext()
    private val backend = ScriptedBackend()
    private lateinit var cache: DataCache
    private lateinit var api: PushApiClient

    /** What course selection answers, or null for a fetch that fails. */
    private var answer: List<String>? = listOf(CS1, CS2)
    private val selectionAsks = mutableListOf<Boolean>()
    private var selectionTerm = TERM
    private var now = 1_800_000_000_000L

    private val sources = object : TermCourseSync.Sources {
        override fun servesSelection(semester: String) = semester == selectionTerm

        override suspend fun selection(studentId: String, password: String, fresh: Boolean): List<String> {
            selectionAsks += fresh
            return answer ?: error("course selection is down")
        }

        override suspend fun moodle(): List<MoodleEnrolledCourse> = emptyList()

        override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course =
            Course.fromSchedule(courseNo, courseNo, classroom = "IB-202", schedule = mapOf(2 to listOf("5")), moodleIdNumber = "$semester$courseNo")
    }

    private lateinit var sync: TermCourseSync

    @Before
    fun setUp() {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-term-course-sync-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        sync = TermCourseSync(
            cache, sources,
            uploader = { courses, semester, resend, rosterTerms ->
                api.uploadCourses(courses, semester, resend = resend, rosterTerms = rosterTerms)
            },
            waitingAdds = { emptySet() },
            courseLocale = { "zh" },
            uploadFailed = {},
            clock = { now },
        )
    }

    /** Each upload's courses and `roster_terms`, in order. */
    private fun uploads() = backend.received(UPLOAD).map { request ->
        val body = JSONObject(request.body)
        val courses = body.getJSONArray("courses")
        val terms = body.optJSONArray("roster_terms")
        List(courses.length()) { courses.getJSONObject(it).getString("course_no") } to
            terms?.let { List(it.length()) { i -> it.getString(i) } }
    }

    @Test
    fun `course selection's answer goes up as the term's roster, and an unchanged one only once`() = runTest {
        sync.sync(STUDENT, PASSWORD, TERM)
        sync.sync(STUDENT, PASSWORD, TERM)

        assertEquals(listOf(listOf(CS1, CS2) to listOf(TERM)), uploads())
        assertEquals(listOf(CS1, CS2), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(listOf(false, false), selectionAsks)
    }

    @Test
    fun `a roster the backend asks for is fetched afresh and goes again unchanged, roster_terms and all`() = runTest {
        sync.sync(STUDENT, PASSWORD, TERM)

        sync.sync(STUDENT, PASSWORD, TERM, rosterRequested = true)

        assertEquals(listOf(listOf(CS1, CS2) to listOf(TERM), listOf(CS1, CS2) to listOf(TERM)), uploads())
        assertEquals(listOf(false, true), selectionAsks)
    }

    /**
     * The transcript on disk lists the term, which a fetch course selection does not answer adds
     * to the term and uploads, as no roster. A roster request is no reason for that upload.
     */
    @Test
    fun `a roster request course selection does not answer sends nothing`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = CS1, courseName = CS1)), TERM)
        cache.saveScoreReport(transcriptListing(CS2), STUDENT)
        for (failed in listOf(null, emptyList<String>())) {
            answer = failed
            sync.sync(STUDENT, PASSWORD, TERM, rosterRequested = true)
        }
        // A term course selection does not serve is not even asked.
        selectionTerm = OTHER_TERM
        answer = listOf(CS1)
        sync.sync(STUDENT, PASSWORD, TERM, rosterRequested = true)

        assertEquals(emptyList<Any>(), uploads())
        assertEquals(listOf(true, true), selectionAsks)
        assertEquals(listOf(CS1), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `a term course selection does not answer goes up as no roster`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = CS1, courseName = CS1)), TERM)
        cache.saveScoreReport(transcriptListing(CS2), STUDENT)
        // Not served: the term only gains the transcript's class.
        selectionTerm = OTHER_TERM
        sync.sync(STUDENT, PASSWORD, TERM)
        assertEquals(listOf(listOf(CS2, CS1) to null), uploads())
        cache.saveCourses(listOf(Course(courseNo = CS1, courseName = CS1)), TERM)

        // Served, but one course's lookup has nothing to stand in for it: no roster may drop it.
        selectionTerm = TERM
        answer = listOf(CS1, CS2)
        val failing = object : TermCourseSync.Sources by sources {
            override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course? =
                if (courseNo == CS2) kept else sources.lookUp(semester, courseNo, moodle, kept)
        }
        TermCourseSync(
            cache, failing,
            uploader = { courses, semester, resend, rosterTerms -> api.uploadCourses(courses, semester, resend = resend, rosterTerms = rosterTerms) },
            waitingAdds = { emptySet() }, courseLocale = { "zh" }, uploadFailed = {},
        ).sync(STUDENT, PASSWORD, TERM, rosterRequested = true)

        assertEquals(listOf(CS1) to null, uploads().last())
    }

    @Test
    fun `each roster request goes to the school once per request, and again an hour on`() = runTest {
        val requests = listOf(TERM, TERM)

        val asked = listOf(
            sync.answerRosterRequests(requests, STUDENT, PASSWORD),
            sync.answerRosterRequests(requests, STUDENT, PASSWORD),
            sync.answerRosterRequests(requests, STUDENT, PASSWORD).also { now += RosterRequestLog.RETRY_INTERVAL_MS },
            sync.answerRosterRequests(requests, STUDENT, PASSWORD),
            sync.answerRosterRequests(emptyList(), STUDENT, PASSWORD),
        )

        assertEquals(listOf(true, false, false, true, false), asked)

        assertEquals(listOf(true, true), selectionAsks)
        assertEquals(2, uploads().size)
    }

    /** A running term's transcript: [TERM] is the newest it lists, so the term only gains from it. */
    private fun transcriptListing(vararg codes: String) = ScoreReport(
        student = "", currentTerm = "", rankings = emptyList(),
        courses = codes.map { CourseGrade(term = TERM, code = it, name = it, status = GradeStatus.PENDING) },
        creditSummary = CreditSummary.EMPTY,
    )

    private companion object {
        const val UPLOAD = "POST /sync/courses/upload"
        const val TERM = "1151"
        const val OTHER_TERM = "1152"
        const val STUDENT = "B00000000"
        const val PASSWORD = "not-a-password"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
    }
}
