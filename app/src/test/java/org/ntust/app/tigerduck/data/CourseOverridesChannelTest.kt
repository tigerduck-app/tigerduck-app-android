package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The course colors and names' queue ([PendingCourseOverrides]) over the real backend client and
 * real files, against a backend that records what reaches it.
 *
 * What it sends on `/v3` is pinned request by request, as the build before the outbox sent it: the
 * method, the address, every header the client builds, the body and its type, for a pick, a waiting
 * change with its time or with none, a resend after a `clock_ahead`, and what an outage, a refusal,
 * a course gone from this phone, the switches and the re-enable hold leave waiting.
 */
class CourseOverridesChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val v3Backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val calculus = Course(courseNo = "MA1010002", courseName = "Calculus", moodleIdNumber = "1151MA1010002")
    private val physics = Course(courseNo = "PH1010003", courseName = "Physics", moodleIdNumber = "1151PH1010003")
    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // TigerSync is off for good on F-Droid, where none of this goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    /** The queue as the app builds it, over the client at `/v3`. */
    private fun v3Overrides() = PendingCourseOverrides(cache, pushApiClientAt(v3, v3Backend.client, prefs), prefs)

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun v3Shapes() = v3Backend.sent.map { it.shape() }

    private fun patch(moodleId: String, body: String) = listOf("PATCH", "$v3/sync/courses/$moodleId/override", listOf(bearer), json, body)

    private fun pathOf(course: Course) = "/v3/sync/courses/${course.moodleIdNumber}/override"

    private fun color(course: Course, hex: String, madeAtMs: Long? = this.madeAtMs, semester: String? = null) = PendingCourseOverride(
        course.moodleIdNumber, hex, null, null, changedAtMs = 1_000L,
        semester = semester, courseNo = semester?.let { course.courseNo }, seq = 1L, madeAtMs = madeAtMs,
    )

    private fun name(course: Course, name: String, locale: String, madeAtMs: Long? = this.madeAtMs) = PendingCourseOverride(
        course.moodleIdNumber, null, name, locale, changedAtMs = 1_000L, seq = 1L, madeAtMs = madeAtMs,
    )

    private suspend fun queue(vararg rows: PendingCourseOverride) = assertTrue(cache.savePendingCourseOverrides(rows.toList(), signIn))

    private suspend fun waiting() = cache.loadPendingCourseOverrides()

    @Test
    fun `on v3 each waiting color or name goes as before, a PATCH by its Moodle id with its time or with none`() = runTest {
        queue(color(calculus, "#FF0000"), name(calculus, "Calc", "en"), color(physics, "#00FF00", madeAtMs = null))

        v3Overrides().retry(signIn)

        assertEquals(
            listOf(
                patch("1151MA1010002", """{"color_hex":"#FF0000","hlc":1790000000123000}"""),
                patch("1151MA1010002", """{"custom_name":"Calc","locale":"en","hlc":1790000000123000}"""),
                patch("1151PH1010003", """{"color_hex":"#00FF00"}"""),
            ),
            v3Shapes(),
        )
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v3 a pick goes at once, stamped with the wall clock as it was made`() = runTest {
        val before = System.currentTimeMillis()
        v3Overrides().send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn)
        val after = System.currentTimeMillis()

        val sent = v3Backend.sent.single()
        assertEquals(listOf("PATCH", "$v3/sync/courses/1151MA1010002/override", listOf(bearer), json), sent.shape().take(4))
        val stamp = Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()
        assertTrue("stamped ${sent.body}", stamp != null && stamp in before * 1_000..after * 1_000)
        assertEquals("""{"color_hex":"#FF0000","hlc":$stamp}""", sent.body)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v3 a change refused as ahead goes once more without its time`() = runTest {
        queue(name(calculus, "Calc", "en"))
        v3Backend.answer(pathOf(calculus), 409, """{"detail":"clock_ahead"}""")

        v3Overrides().retry(signIn)

        assertEquals(
            listOf(
                patch("1151MA1010002", """{"custom_name":"Calc","locale":"en","hlc":1790000000123000}"""),
                patch("1151MA1010002", """{"custom_name":"Calc","locale":"en"}"""),
            ),
            v3Shapes(),
        )
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v3 a change the server fails waits uncounted and goes at the next retry`() = runTest {
        queue(color(calculus, "#FF0000"))
        v3Backend.answer(pathOf(calculus), 503, """{"detail":"unavailable"}""")
        val overrides = v3Overrides()

        overrides.retry(signIn)
        assertEquals(0, waiting().single().attempts)
        overrides.retry(signIn)

        assertEquals(List(2) { patch("1151MA1010002", """{"color_hex":"#FF0000","hlc":1790000000123000}""") }, v3Shapes())
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v3 a change the backend refuses is counted on disk and kept`() = runTest {
        queue(color(calculus, "#FF0000"))
        v3Backend.answer(pathOf(calculus), 422, """{"detail":"invalid_request"}""")

        v3Overrides().retry(signIn)

        assertEquals(1, v3Backend.sent.size)
        assertEquals(listOf(1), waiting().map { it.attempts })
    }

    @Test
    fun `on v3 a 404 for a course gone from this phone lets its change go at once`() = runTest {
        // Calculus is no longer cached in the term the change was made in.
        cache.saveCourses(listOf(physics), term, testSignIn())
        queue(color(calculus, "#FF0000", semester = term))
        v3Backend.answer(pathOf(calculus), 404, """{"detail":"course_not_found"}""")

        v3Overrides().retry(signIn)

        assertEquals(1, v3Backend.sent.size)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v3 nothing goes while the colors re-enable check holds them, and the names still go`() = runTest {
        queue(color(calculus, "#FF0000"), name(calculus, "Calc", "en"))
        prefs.school.pendingConflictCategories.set(setOf(PendingCourseOverrides.category(color = true)), testSignIn())

        v3Overrides().retry(signIn)

        assertEquals(listOf(patch("1151MA1010002", """{"custom_name":"Calc","locale":"en","hlc":1790000000123000}""")), v3Shapes())
        assertEquals(listOf("#FF0000"), waiting().map { it.colorHex })
    }

    @Test
    fun `on v3 a pick made with the colors switch off goes nowhere and is not kept`() = runTest {
        queue(color(calculus, "#FF0000"))
        prefs.syncCourseColors = false

        v3Overrides().send(calculus.moodleIdNumber!!, colorHex = "#0000FF", signIn = signIn)

        assertEquals(emptyList<RecordingBackend.Sent>(), v3Backend.sent.toList())
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }
}
