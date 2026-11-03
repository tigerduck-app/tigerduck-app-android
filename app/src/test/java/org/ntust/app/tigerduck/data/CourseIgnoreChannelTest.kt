package org.ntust.app.tigerduck.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The course ignores' queue ([CourseIgnoreSync]) as the app builds it, over the real backend client
 * and real files, against a backend that records what reaches it. On Robolectric, for the reminders
 * a change re-arms.
 *
 * What it sends on `/v3` is pinned request by request, as the build before the outbox sent it: the
 * method, the address, every header the client builds, the body and its type, for a tap, a waiting
 * change with its time or with none, a resend after a `clock_ahead` and the numeric Moodle id's
 * fill-in, and what an outage, a refusal, an older backend's answer and the switches leave waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseIgnoreChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    private val v3Backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1141"
    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"
    private val stored = """{"ignore_assignments":true}"""

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // TigerSync is off for good on F-Droid, where none of this goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    private val v3Client: PushApiClient by lazy { pushApiClientAt(v3, v3Backend.client, prefs) }

    /** The queue as the app builds it, over the client at `/v3`: one per process. */
    private fun v3Ignores() = CourseIgnoreSync(cache, v3Client, prefs, AssignmentNotificationScheduler(context))

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun v3Shapes() = v3Backend.sent.map { it.shape() }

    private fun patch(moodleId: String, body: String) = listOf("PATCH", "$v3/sync/courses/$moodleId/override", listOf(bearer), json, body)

    private fun pathOf(courseNo: String) = "/v3/sync/courses/$term$courseNo/override"

    private fun change(courseNo: String, ignore: Boolean = true, changedAtMs: Long = 1_000L, madeAtMs: Long? = this.madeAtMs, moodleCourseId: Int? = null) =
        PendingCourseIgnore(term, courseNo, "$term$courseNo", moodleCourseId, ignore, changedAtMs, madeAtMs = madeAtMs)

    private suspend fun queue(vararg rows: PendingCourseIgnore) = assertTrue(cache.savePendingCourseIgnores(rows.toList(), signIn))

    private suspend fun waiting() = cache.loadPendingCourseIgnores()

    @Test
    fun `on v3 a tap goes at once as a PATCH of the setting, stamped with the wall clock as it was made`() = runTest {
        v3Backend.answer(pathOf("AAA"), 200, stored)
        val before = System.currentTimeMillis()
        v3Ignores().setIgnored(term, "AAA", "${term}AAA", 7, ignore = true, signIn = signIn)
        val after = System.currentTimeMillis()

        val sent = v3Backend.sent.single()
        assertEquals(listOf("PATCH", "$v3/sync/courses/1141AAA/override", listOf(bearer), json), sent.shape().take(4))
        val stamp = Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()
        assertTrue("stamped ${sent.body}", stamp != null && stamp in before * 1_000..after * 1_000)
        assertEquals("""{"ignore_assignments":true,"moodle_course_id":7,"hlc":$stamp}""", sent.body)
        assertEquals(setOf("$term:AAA"), cache.loadIgnoredCourses())
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    @Test
    fun `on v3 waiting changes go oldest first, each with its time or with none`() = runTest {
        // In the file the newer comes first; an older build's row has no time.
        queue(change("BBB", ignore = false, changedAtMs = 2_000L), change("AAA", changedAtMs = 1_000L, madeAtMs = null, moodleCourseId = 9))
        v3Backend.answer(pathOf("BBB"), 200, """{"ignore_assignments":false}""")
        v3Backend.answer(pathOf("AAA"), 200, stored)

        v3Ignores().retryPending(signIn)

        assertEquals(
            listOf(
                patch("1141AAA", """{"ignore_assignments":true,"moodle_course_id":9}"""),
                patch("1141BBB", """{"ignore_assignments":false,"hlc":1790000000123000}"""),
            ),
            v3Shapes(),
        )
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    @Test
    fun `on v3 an answer without the setting, from an older backend, is counted as a refusal`() = runTest {
        queue(change("AAA"))

        v3Ignores().retryPending(signIn)

        assertEquals(listOf(patch("1141AAA", """{"ignore_assignments":true,"hlc":1790000000123000}""")), v3Shapes())
        assertEquals(listOf(1), waiting().map { it.attempts })
    }

    @Test
    fun `on v3 a change refused as ahead goes once more without its time`() = runTest {
        queue(change("AAA"))
        v3Backend.answer(pathOf("AAA"), 409, """{"detail":"clock_ahead"}""")
        v3Backend.answer(pathOf("AAA"), 200, stored)

        v3Ignores().retryPending(signIn)

        assertEquals(
            listOf(
                patch("1141AAA", """{"ignore_assignments":true,"hlc":1790000000123000}"""),
                patch("1141AAA", """{"ignore_assignments":true}"""),
            ),
            v3Shapes(),
        )
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    @Test
    fun `on v3 a change the server fails waits uncounted`() = runTest {
        queue(change("AAA"))
        v3Backend.answer(pathOf("AAA"), 503, """{"detail":"unavailable"}""")

        v3Ignores().retryPending(signIn)

        assertEquals(1, v3Backend.sent.size)
        assertEquals(listOf(0), waiting().map { it.attempts })
    }

    @Test
    fun `on v3 a change refused five times is parked, and goes once more in the next process only`() = runTest {
        queue(change("AAA"))
        repeat(CourseIgnoreSync.MAX_ATTEMPTS + 3) { v3Backend.answer(pathOf("AAA"), 404, """{"detail":"course_not_found"}""") }
        val ignores = v3Ignores()

        repeat(CourseIgnoreSync.MAX_ATTEMPTS + 2) { ignores.retryPending(signIn) }
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS, v3Backend.sent.size)
        assertEquals(listOf(CourseIgnoreSync.MAX_ATTEMPTS), waiting().map { it.attempts })

        val next = v3Ignores()
        repeat(2) { next.retryPending(signIn) }
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS + 1, v3Backend.sent.size)
        assertEquals(listOf(CourseIgnoreSync.MAX_ATTEMPTS), waiting().map { it.attempts })
    }

    @Test
    fun `on v3 a snapshot's ignored course without the numeric Moodle id gets it from this phone, unstamped`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = "AAA", courseName = "A", moodleIdNumber = "${term}AAA", moodleNumericCourseId = 41)), term, signIn)
        val snapshot = BackendSyncResult(
            ignoredIds = emptySet(),
            completedIds = emptySet(),
            courseOverrides = listOf(
                CourseOverrideResult(
                    moodleCourseId = "${term}AAA", courseNo = "AAA", colorHex = null, semester = term,
                    ignoreAssignments = true, ignoreAssignmentsUpdatedAt = "2026-10-13T00:00:00Z",
                ),
            ),
            serverCourseNos = setOf("AAA"),
            serverCourses = listOf(ServerCourse(courseNo = "AAA", courseName = "A", semester = term)),
            currentRevision = 0,
            fetchedAtMs = 5_000L,
        )

        v3Ignores().applySnapshot(snapshot, signIn)

        assertEquals(listOf(patch("1141AAA", """{"moodle_course_id":41}""")), v3Shapes())
        assertEquals(setOf("$term:AAA"), cache.loadIgnoredCourses())
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    @Test
    fun `on v3 nothing goes while course sync is off, and the change waits`() = runTest {
        prefs.syncCourses = false

        v3Ignores().setIgnored(term, "AAA", "${term}AAA", null, ignore = true, signIn = signIn)

        assertEquals(emptyList<RecordingBackend.Sent>(), v3Backend.sent.toList())
        assertEquals(listOf("$term:AAA" to true), waiting().map { it.key to it.ignore })
    }
}
