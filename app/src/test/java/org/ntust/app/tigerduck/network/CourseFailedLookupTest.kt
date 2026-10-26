package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
}
