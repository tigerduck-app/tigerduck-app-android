package org.ntust.app.tigerduck.sync

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.CourseUploader
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * When an upload derived from a school fetch goes, against a scripted backend: the background's
 * fetch of a term, as the app wires it, in a process that has not pulled. Made-up account and
 * course numbers.
 */
class SchoolUploadsTest {

    private val context = JvmContext()
    private val backend = ScriptedBackend()
    private lateinit var cache: DataCache
    private lateinit var api: PushApiClient
    private lateinit var auth: AuthTokenManager
    private lateinit var prefs: AppPreferences

    private val sources = object : TermCourseSync.Sources {
        override fun servesSelection(semester: String) = semester == TERM
        override suspend fun selection(studentId: String, password: String, fresh: Boolean) = listOf(CS1, CS2)
        override suspend fun moodle(): List<MoodleEnrolledCourse> = emptyList()
        override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course =
            Course.fromSchedule(courseNo, courseNo, classroom = "IB-202", schedule = mapOf(2 to listOf("5")), moodleIdNumber = "$semester$courseNo")
    }

    private val signIn get() = auth.session.signIns.current

    // Only its lock is used here: no add waits.
    private val adds by lazy { PendingCourseAdds(cache, CourseUploader { _, _, _, _, _ -> null }) }

    @Before
    fun setUp() {
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-school-uploads-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        auth = AuthTokenManager(tokens, backend.client, prefs, "device-1")
        api = PushApiClient(backend.client, prefs, auth)
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    }

    /** The app's wiring of [TermCourseSync] but its sources: the upload, as the app's, pinned to the sign-in. */
    private fun termSync() = TermCourseSync(
        cache, sources,
        uploader = TermCourseSync.Uploader { courses, semester, resend, rosterTerms, signIn ->
            api.uploadCourses(courses, semester, signIn, resend = resend, rosterTerms = rosterTerms)
        },
        waitingAdds = { emptySet() },
        uploading = { block -> adds.uploading(block) },
        courseLocale = { "zh" },
        uploadFailed = {},
    )

    /** Each course upload's courses, in order. */
    private fun uploads() = backend.received(UPLOAD).map { request ->
        val courses = JSONObject(request.body).getJSONArray("courses")
        List(courses.length()) { courses.getJSONObject(it).getString("course_no") }
    }

    @Test
    fun `a fetched term goes up as its fetch commits it, though this process has not pulled`() = runBlocking {
        termSync().sync(STUDENT, PASSWORD, TERM, signIn = signIn)

        assertEquals(listOf(listOf(CS1, CS2)), uploads())
    }

    private companion object {
        const val UPLOAD = "POST /sync/courses/upload"
        const val TERM = "1151"
        const val STUDENT = "B00015999"
        const val PASSWORD = "not-a-password"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
    }
}
