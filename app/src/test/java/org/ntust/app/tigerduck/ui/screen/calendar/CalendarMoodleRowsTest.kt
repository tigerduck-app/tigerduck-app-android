package org.ntust.app.tigerduck.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import java.util.Date

class CalendarMoodleRowsTest {

    private val school = CalendarEvent("school-1", "Midterms", Date(0), EventSource.SCHOOL.raw)
    private val holiday = CalendarEvent("holiday-1", "Holiday", Date(0), EventSource.HOLIDAY.raw)

    private fun moodleRow(id: String) =
        CalendarEvent("moodle-$id", "HW $id", Date(0), EventSource.MOODLE.raw)

    private fun assignment(id: String) = Assignment(
        assignmentId = id,
        courseNo = "CS101",
        courseName = "CS",
        title = "HW $id",
        dueDate = Date(0),
    )

    @Test
    fun `the Moodle rows follow the assignment cache`() {
        assertEquals(
            listOf(school, holiday, moodleRow("2")),
            withMoodleRows(listOf(school, moodleRow("1"), holiday), listOf(assignment("2"))),
        )
    }

    @Test
    fun `only a Moodle row names the assignment it is the deadline of`() {
        assertEquals("2", withMoodleRows(emptyList(), listOf(assignment("2"))).single().moodleAssignmentId)
        assertEquals(null, school.moodleAssignmentId)
        // A school row whose feed id happens to look like a Moodle one, and a Moodle row with no id.
        assertEquals(null, CalendarEvent("moodle-3", "Fair", Date(0), EventSource.SCHOOL.raw).moodleAssignmentId)
        assertEquals(null, moodleRow("").moodleAssignmentId)
    }

    @Test
    fun `an empty assignment cache takes the Moodle rows off`() {
        // A TA account's rows once Moodle answers with none of the courses it TAs.
        assertEquals(
            listOf(school, holiday),
            withMoodleRows(listOf(school, moodleRow("1"), holiday), emptyList()),
        )
    }

    @Test
    fun `an extended assignment sits on the calendar on its extension`() {
        val extended = assignment("2").copy(extensionDueDate = Date(86_400_000))

        val rows = withMoodleRows(emptyList(), listOf(extended))

        assertEquals(listOf(Date(86_400_000)), rows.map { it.date })
    }
}
