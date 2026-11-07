package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.QueueFile
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The course list's moments: each class-table action's place in the order of the adds and deletes,
 * kept apart from its time, so an action after the clock was set back still comes after the ones
 * before it and still goes with its own wall time, never one ahead of the clock that read it; and
 * the moments' clocks on disk before any moment that needs them, and let go only after, so a
 * process death between the files' writes never leaves a moment without its clocks.
 */
class CourseMomentsTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current
    private val term = "1151"
    private val course = Course(courseNo = "MY1", courseName = "Mine", isManual = true)

    // The wall clock, which a test sets back; no elapsed clock, as without BOOT_COUNT.
    private var wall = 1_790_000_100_000L

    private val hiddenAt = mutableListOf<EditTime>()
    private val addedAt = mutableListOf<EditTime>()
    private val adds = PendingCourseAdds(cache, { courses, _, _, _, times ->
        addedAt += times.values
        CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
    })

    private fun deletes() = PendingCourseDeletes(cache, adds, { _, _, at -> hiddenAt += at }, { wall })

    @Test
    fun `an action after the clock was set back goes with its own wall time`() = runTest {
        assertTrue(cache.saveCourses(listOf(course), term, signIn))
        val deletes = deletes()
        assertTrue(deletes.keep(term, "OLD1", deletes.stamp(), signIn))
        wall -= 60_000

        deletes.send(term, "MY1", signIn)
        adds.send(listOf(course), term, "MY1", deletes.stamp(), signIn)

        assertEquals("the delete's own wall time", listOf(wall), hiddenAt.map { it.wallMs })
        assertEquals("the add's own wall time", listOf(wall), addedAt.map { it.wallMs })
    }

    @Test
    fun `a delete kept after the clock was set back reads its own wall time in the next process`() = runTest {
        val first = deletes()
        assertTrue(first.keep(term, "OLD1", first.stamp(), signIn))
        wall -= 60_000
        assertTrue(first.keep(term, "MY1", first.stamp(), signIn))

        deletes().sendKept(term, "MY1", signIn)

        assertEquals(listOf(wall), hiddenAt.map { it.wallMs })
    }

    // [cache]'s moments' clocks file, written as [MomentClocks] writes it, with a process death at
    // its [dies]th write: before it lands, or after it lands when [landed].
    private inner class DiesAt(cache: DataCache, file: QueueFile, private val dies: Int, private val landed: Boolean) : MomentClocks(cache, file) {
        private var writes = 0

        override suspend fun save(clocks: Map<Long, EditClocks>, signIn: SignIn): Boolean {
            if (++writes == dies && !landed) series.end()
            return super.save(clocks, signIn).also { if (writes == dies && landed) series.end() }
        }
    }

    private val before = EditClocks(elapsedMs = 100, boot = 7)
    private val after = EditClocks(elapsedMs = 1_100, boot = 7)

    @Test
    fun `an add asked again keeps its clocks whichever write a process death comes between`() = runTest {
        for (dies in 1..3) {
            for (landed in listOf(false, true)) {
                val cache = DataCache(JvmContext(), AcademicCalendarStore(context, OkHttpClient(), prefs))
                series.open()
                assertTrue(CourseAddsStore(cache).save(listOf(CourseAdd(term, "MY1", 5_000, before)), signIn))

                CourseAddsStore(cache, DiesAt(cache, QueueFile.COURSE_ADD_CLOCKS, dies, landed))
                    .save(listOf(CourseAdd(term, "MY1", 6_000, after)), signIn)

                val read = CourseAddsStore(cache).load().single()
                assertEquals("death at clocks write $dies, landed $landed", mapOf(5_000L to before, 6_000L to after)[read.addedAtMs], read.clocks)
            }
        }
    }

    @Test
    fun `a delete asked again keeps its clocks whichever write a process death comes between`() = runTest {
        for (dies in 1..3) {
            for (landed in listOf(false, true)) {
                val cache = DataCache(JvmContext(), AcademicCalendarStore(context, OkHttpClient(), prefs))
                series.open()
                assertTrue(CourseDeletesStore(cache, cache::loadPendingCourseDeletes).save(listOf(CourseDelete(term, "MY1", 5_000, before)), signIn))

                CourseDeletesStore(cache, cache::loadPendingCourseDeletes, DiesAt(cache, QueueFile.COURSE_DELETE_CLOCKS, dies, landed))
                    .save(listOf(CourseDelete(term, "MY1", 6_000, after)), signIn)

                val read = CourseDeletesStore(cache, cache::loadPendingCourseDeletes).load().single()
                assertEquals("death at clocks write $dies, landed $landed", mapOf(5_000L to before, 6_000L to after)[read.queuedAt], read.clocks)
            }
        }
    }
}
