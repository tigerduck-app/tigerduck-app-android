package org.ntust.app.tigerduck.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import java.util.Date

/**
 * Which deadline rows open their assignment's page, as the iPhone's calendar opens them
 * (tigerduck-app#999): those the page would find, of the courses the class table shows.
 */
class CalendarOpenableDeadlinesTest {

    private fun assignment(id: String, courseNo: String) = Assignment(
        assignmentId = id,
        courseNo = courseNo,
        courseName = courseNo,
        title = "HW $id",
        dueDate = Date(0),
    )

    private val listed = listOf(assignment("1", "CS101"), assignment("2", "MA201"))

    @Test
    fun `a deadline of a course the class table shows opens`() {
        assertEquals(setOf("1", "2"), openableAssignmentIds(listed, shownCourseNos = setOf("CS101", "MA201")))
    }

    @Test
    fun `a deadline of a hidden course opens nothing`() {
        assertEquals(setOf("1"), openableAssignmentIds(listed, shownCourseNos = setOf("CS101")))
    }

    @Test
    fun `with an empty class table every deadline opens`() {
        assertEquals(setOf("1", "2"), openableAssignmentIds(listed, shownCourseNos = emptySet()))
    }

    @Test
    fun `a row whose assignment left the cache opens nothing`() {
        // The page would find no record and no row for it, and close at once.
        val stale = CalendarEvent(CalendarEvent.moodleEventId("3"), "HW 3", Date(0), EventSource.MOODLE.raw)

        assertNull(stale.assignmentToOpen(openableAssignmentIds(listed, shownCourseNos = emptySet())))
    }

    @Test
    fun `a deadline that opens names its assignment, and a school row names none`() {
        val openable = openableAssignmentIds(listed, shownCourseNos = setOf("CS101"))
        val deadline = CalendarEvent(CalendarEvent.moodleEventId("1"), "HW 1", Date(0), EventSource.MOODLE.raw)
        val hidden = CalendarEvent(CalendarEvent.moodleEventId("2"), "HW 2", Date(0), EventSource.MOODLE.raw)
        val school = CalendarEvent("1", "Midterms", Date(0), EventSource.SCHOOL.raw)

        assertEquals("1", deadline.assignmentToOpen(openable))
        assertNull(hidden.assignmentToOpen(openable))
        assertNull(school.assignmentToOpen(openable))
    }
}
