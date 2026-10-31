package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.Course

/**
 * A query that failed says nothing of the course (tigerduck-app-android#417, as iOS
 * tigerduck-app#243): the row the term already has stands, and Moodle's stand-in, which has no
 * times and no room, only takes the place of none.
 */
class CourseFailedLookupTest {

    private val moodle = MoodleEnrolledCourse(7, "Calculus (I)", "CALC", "1151CS1001301", 0, 0)

    @Test
    fun `a failed query keeps the row the term has`() {
        val kept = Course.fromSchedule("CS1001301", "Calculus (I)", classroom = "TR-313", schedule = mapOf(3 to listOf("6")))
        assertEquals(kept, CourseService.afterFailedLookup("CS1001301", moodle, kept))
    }

    @Test
    fun `with no row to keep, Moodle stands in, or nothing does`() {
        assertEquals("Calculus (I)", CourseService.afterFailedLookup("CS1001301", moodle, kept = null)?.courseName)
        assertNull(CourseService.afterFailedLookup("CS1001301", moodle = null, kept = null))
    }

    // --- An empty answer, as iOS `AppServiceBridge.refreshedCourseData` takes one (tigerduck-app#890) ---

    private fun row(courseNo: String) = CourseSearchResult(
        semester = "1151", courseNo = courseNo, courseName = "Now $courseNo", courseTeacher = "",
        dimension = null, creditPoint = "3", requireOption = null, allYear = null, chooseStudent = null,
        restrict1 = null, restrict2 = null, courseTimes = null, practicalTimes = null, classRoomNo = null,
        node = null, contents = null,
    )

    private fun answer(results: List<CourseSearchResult>, kept: Course?, moodle: MoodleEnrolledCourse? = this.moodle) =
        CourseService.lookedUp("CS1001301", results, moodle, kept) { rows, _ -> Course.fromSchedule(rows.first().courseNo, rows.first().courseName) }

    /**
     * QueryCourse has no rows for a course it stopped indexing, or for a while for one it is
     * re-indexing, and that says no more of the course than a failure does. Taken for an answer,
     * the course selection term's Moodle stand-in replaced the row it had, times and room with it.
     */
    @Test
    fun `an empty answer is no answer, and keeps the row the term has`() {
        val kept = Course.fromSchedule("CS1001301", "Calculus (I)", classroom = "TR-313", schedule = mapOf(3 to listOf("6")))

        val lookup = answer(emptyList(), kept)

        assertFalse(lookup.answered)
        assertEquals(kept, lookup.course)
        assertEquals("CS1001301", lookup.courseNo)
    }

    @Test
    fun `an empty answer with no row to keep takes Moodle's stand-in, or nothing`() {
        assertEquals(LookedUpCourse("CS1001301", CourseService.fallbackCourseFromMoodle("CS1001301", moodle), false), answer(emptyList(), null))
        assertEquals(LookedUpCourse("CS1001301", null, false), answer(emptyList(), null, moodle = null))
    }

    @Test
    fun `rows are an answer, whatever the term had`() {
        val lookup = answer(listOf(row("CS1001301")), kept = Course.fromSchedule("CS1001301", "Old"))

        assertTrue(lookup.answered)
        assertEquals("Now CS1001301", lookup.course?.courseName)
    }
}
