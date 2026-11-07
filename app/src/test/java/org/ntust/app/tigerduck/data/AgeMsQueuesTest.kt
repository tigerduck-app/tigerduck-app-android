package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The queues on `age_ms` (backend 4.3.0): a queue file an older build wrote, with its wall times
 * alone, still loads and sends a wall clock age; a 409 is a refusal like any other, counted, never
 * a retry of its own; and a pull keeps a holiday's choice for a pending edit or a write it
 * acknowledged since the snapshot, never for a device time against the backend's.
 */
class AgeMsQueuesTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current
    private val v4 = "http://127.0.0.1:9/v4"
    private val backend = RecordingBackend()
    private val client = pushApiClientAt(v4, backend.client, prefs)
    private val term = "1151"

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    private fun ageOf(sent: RecordingBackend.Sent): Long? = sent.body?.let { JSONObject(it).opt("age_ms") as? Number }?.toLong()

    private fun refusedAsAhead(path: String) = backend.answer(path, 409, syncFixture("v4/error-clock-ahead.json"))

    @Test
    fun `a mark an older build queued, with its wall time alone, loads and goes with its wall clock age`() = runTest {
        val madeAtMs = System.currentTimeMillis() - 90_000
        File(context.filesDir, "TigerDuckData/unsent_marks.json").apply { parentFile?.mkdirs() }
            .writeText("""[{"assignmentId":"7","status":"ignored","attempts":0,"lastRefusedAtMs":0,"madeAtMs":$madeAtMs}]""")

        UnsentMarks(cache, client, prefs).drain(signIn)

        val age = ageOf(backend.sent.single())
        assertTrue("a wall clock age of a minute and a half, got $age", age != null && age in 90_000L..150_000L)
        assertFalse(backend.sent.single().body!!.contains("hlc"))
    }

    @Test
    fun `a mark refused with a 409 is counted as a refusal, and goes once`() = runTest {
        refusedAsAhead("/v4/sync/assignments/7/override")

        UnsentMarks(cache, client, prefs).send("7", "ignored", signIn)

        assertEquals(1, backend.sent.size)
        assertEquals(1, cache.loadUnsentMarks().single().attempts)
    }

    @Test
    fun `a course color refused with a 409 is counted as a refusal, and goes once`() = runTest {
        refusedAsAhead("/v4/sync/courses/client:$term:CS1/override")

        PendingCourseOverrides(cache, client, prefs).send("${term}CS1", colorHex = "#FF0000", semester = term, courseNo = "CS1", signIn = signIn)

        assertEquals(1, backend.sent.size)
        assertEquals(1, cache.loadPendingCourseOverrides().single().attempts)
    }

    @Test
    fun `a course ignore refused with a 409 is counted as a refusal, and goes once`() = runTest {
        refusedAsAhead("/v4/sync/courses/client:$term:CS1/override")
        val ignores = CourseIgnoreSync(cache, CourseIgnoreWriter(client::patchCourseIgnoreAnswered), CourseIgnoreEffects {}, { true }, { 9_000L })

        ignores.setIgnored(term, "CS1", "${term}CS1", null, ignore = true, signIn = signIn)

        assertEquals(1, backend.sent.size)
        assertEquals(1, cache.loadPendingCourseIgnores().single().attempts)
    }

    @Test
    fun `a course delete refused with a 409 does not stop the next from going`() = runTest {
        refusedAsAhead("/v4/sync/courses/client:$term:CS1")
        val adds = PendingCourseAdds(cache, client)
        val deletes = PendingCourseDeletes(cache, adds, client)
        assertTrue(deletes.keep(term, "CS1", EditTime(5_000L), signIn))
        assertTrue(deletes.keep(term, "CS2", EditTime(5_001L), signIn))

        deletes.drain(signIn)

        assertEquals(listOf("CS1", "CS2"), backend.sent.map { it.url.substringBefore('?').substringAfterLast(':') })
        assertEquals(mapOf(term to setOf("CS1")), cache.loadPendingCourseDeletes().mapValues { it.value.keys })
    }

    private fun TestScope.holidays() = PendingHolidayOverrides(
        prefs,
        HolidayOverrideWriter { _, notify, _, _ ->
            WriteAnswer(sent = true, serverClockUs = null, applied = setOf("notify"), values = mapOf("notify" to notify))
        },
        syncs = { true },
        scope = backgroundScope,
    )

    @Test
    fun `a pull keeps a holiday choice that waits, never one sent by a device clock ahead of the backend's`() = runTest {
        val holidays = holidays()
        prefs.holidayNotifyOverrides = setOf(5)
        holidays.send(5, signIn)
        prefs.holidayNotifyOverrides = setOf(5, 6)
        prefs.holidayOverridesAwaitingUpload = setOf(6)
        // A snapshot an hour behind this phone's clock, read after choice 5 landed, keeps only 6.
        assertEquals(setOf(6), holidays.keptOver())
    }

    @Test
    fun `a holiday choice an older build left with no time, and no first run known, goes as the oldest, hlc 0`() = runTest {
        prefs.holidayNotifyOverrides = setOf(5)
        prefs.holidayOverridesAwaitingUpload = setOf(5)

        PendingHolidayOverrides(prefs, client).drain(signIn)

        assertEquals("""{"notify":true,"hlc":0}""", backend.sent.single().body)
    }

    @Test
    fun `an older build's course override row, with its wall time alone, still loads`() = runTest {
        File(context.filesDir, "TigerDuckData/pending_course_overrides.json").apply { parentFile?.mkdirs() }.writeText(
            """[{"moodleId":"${term}CS1","colorHex":"#FF0000","changedAtMs":5,"semester":"$term","courseNo":"CS1","attempts":0,"seq":3,"madeAtMs":5}]""",
        )

        assertEquals(
            listOf(PendingCourseOverride("${term}CS1", "#FF0000", null, null, 5, term, "CS1", seq = 3, madeAtMs = 5)),
            cache.loadPendingCourseOverrides(),
        )
    }
}
