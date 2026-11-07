package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.testSignIn
import java.io.File

/**
 * An update that finds edits queued: the files and preference keys this build wrote, the
 * fixtures `AccountFixturesTest` pins, are read by a new process and sent under its first
 * sign-in, stamps as stored, with no sign-out and no migration. A queue that stopped reading
 * them would lose edits the student made offline before the update. An entry an older build kept
 * with no time goes with its old-entry time (X1), here none known, so 0, the oldest;
 * `V4QueueFixturesTest` sends the same fixtures with the times an update knows.
 */
class QueueUpgradeTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private lateinit var cache: DataCache

    // A new process's first sign-in.
    private val signIn = SignInSeries().current

    @Before
    fun setUp() {
        val folder = File(context.filesDir, "TigerDuckData").apply { mkdirs() }
        // As the update found them: nothing in this process has touched them.
        QUEUE_FILES.forEach { name -> File(folder, name).writeText(fixture("queues/$name")) }
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/account/$name")) { "missing fixture $name" }.readText().trimEnd()

    @Test
    fun `the queued adds go up, each with the time it was stored with`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "One"), Course(courseNo = "CS1002", courseName = "Two")), "1141", testSignIn())
        val sent = mutableListOf<Triple<List<String>, List<String>, Map<String, Long>>>()
        val adds = PendingCourseAdds(cache, { courses, semester, forceKeys, _, addedAt ->
            assertEquals("1141", semester)
            // An older install's moments, with no device clocks: each ages by the wall clock.
            assertTrue(addedAt.values.all { it.elapsedMs == null && it.boot == null })
            sent += Triple(courses.map { it.courseNo }, forceKeys, addedAt.mapValues { it.value.wallMs })
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        })

        adds.drain(signIn)

        assertEquals(
            listOf(
                // In one upload, each with its moment; one an older install kept with none has no
                // old-entry time known here, so 0, the oldest (X1).
                Triple(
                    listOf("CS1001", "CS1002"), listOf("client:1141:CS1001", "client:1141:CS1002"),
                    mapOf("CS1001" to 1_700_000_000_000L, "CS1002" to 0L),
                ),
            ),
            sent,
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

    @Test
    fun `the queued deletes go, each with the moment it was asked`() = runTest {
        val sent = mutableListOf<Pair<String, Long>>()
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(
            cache, adds,
            deleter = { courseKey, _, hiddenAt -> sent += courseKey to hiddenAt.also { assertEquals(null, it.elapsedMs) }.wallMs },
        )

        deletes.drain(signIn)

        assertEquals(listOf("client:1141:CS1003" to 1_700_000_001_000L), sent)
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseDeletes())
    }

    @Test
    fun `the queued colors and names go, a row from before the stamp as the oldest`() = runTest {
        val sent = mutableListOf<Triple<String, String?, Long?>>()
        val overrides = PendingCourseOverrides(
            cache,
            { change, _, _, stillAllowed, madeAt ->
                assertEquals(true, stillAllowed())
                assertEquals(null, madeAt?.elapsedMs)
                sent += Triple(change.moodleId.orEmpty(), change.colorHex, madeAt?.wallMs)
                TOOK
            },
            records = { true },
            sends = { true },
        )

        overrides.drain(signIn)

        assertEquals(listOf(Triple("1141CS1001", "#336699", 0L)), sent)
        assertEquals(emptyList<Any>(), cache.loadPendingCourseOverrides())
    }

    @Test
    fun `the queued marks go, stamped when they were tapped`() = runTest {
        val sent = mutableListOf<Triple<Int, String, Long?>>()
        val marks = UnsentMarks(
            cache,
            writer = { id, status, _, madeAt ->
                assertEquals(null, madeAt?.elapsedMs)
                sent += Triple(id, status, madeAt?.wallMs)
                TOOK
            },
            records = { true },
            sends = { true },
        )

        marks.drain(signIn)

        assertEquals(listOf(Triple(7, "ignored", 1_700_000_004_000L)), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `the queued course ignores go, stamped when they were made`() = runTest {
        val sent = mutableListOf<List<Any?>>()
        val ignores = CourseIgnoreSync(
            cache,
            writer = { target, ignore, moodleCourseId, _, madeAt ->
                assertEquals(null, madeAt?.elapsedMs)
                sent += listOf(target.courseKey, ignore, moodleCourseId, madeAt?.wallMs)
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to ignore))
            },
            effects = {},
            sends = { true },
            clock = { 2_000_000_000_000L },
        )

        ignores.retryPending(signIn)

        assertEquals(listOf(listOf<Any?>("client:1141:CS1001", true, 42, 1_700_000_005_000L)), sent)
        assertEquals(emptyList<Any>(), cache.loadPendingCourseIgnores())
    }

    @Test
    fun `the queued holiday choices go, read from the keys as stored`() = runTest {
        // The two keys as school-prefs.txt holds them.
        context.getSharedPreferences("tigerduck_prefs", 0).edit()
            .putStringSet("holidayNotifyOverrides", setOf("3", "5"))
            .putStringSet("holidayOverridesAwaitingUpload", setOf("5"))
            .apply()
        val sent = mutableListOf<Triple<Int, Boolean, Long?>>()
        val holidays = PendingHolidayOverrides(
            prefs,
            writer = { id, notify, _, madeAt ->
                assertEquals(null, madeAt?.elapsedMs)
                sent += Triple(id, notify, madeAt?.wallMs)
                TOOK
            },
            syncs = { true },
        )

        holidays.drain(signIn)

        // Its time unknown here: 0, the oldest.
        assertEquals(listOf(Triple(5, true, 0L)), sent)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    private companion object {
        val QUEUE_FILES = listOf(
            "pending_course_adds.json",
            "pending_course_add_times.json",
            "pending_course_deletes.json",
            "pending_course_overrides.json",
            "unsent_marks.json",
            "pending_course_ignores.json",
        )
    }
}
