package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import java.util.Calendar
import java.util.Date

class CourseRosterMergeTest {

    private fun assignment(id: String, completed: Boolean = false) = Assignment(
        assignmentId = id,
        courseNo = "CS101",
        courseName = "CS",
        title = "HW $id",
        dueDate = Date(0),
        isCompleted = completed,
    )

    /**
     * `courseNo` and `semesterCode` are derived from `idnumber`, not stored,
     * so a fixture has to build the prefixed id the way Moodle does.
     */
    private fun moodle(no: String, semester: String) = MoodleEnrolledCourse(
        id = 1,
        fullname = no,
        shortname = no,
        idnumber = "$semester$no",
        startdate = null,
        enddate = null,
    )

    /** An admin shell or similar: enrolled, but carries no NTUST course id. */
    private fun moodleWithoutCourseNo() = MoodleEnrolledCourse(
        id = 2,
        fullname = "Announcements",
        shortname = "ANN",
        idnumber = null,
        startdate = null,
        enddate = null,
    )

    @Test
    fun `calendar weekdays map to monday-first schedule keys`() {
        assertEquals(1, CourseRosterMerge.weekdayIndex(Calendar.MONDAY))
        assertEquals(5, CourseRosterMerge.weekdayIndex(Calendar.FRIDAY))
        // Sunday is 1 in Calendar and 7 here — the off-by-one that makes this
        // worth a test at all, and the reason both screens now share one copy.
        assertEquals(7, CourseRosterMerge.weekdayIndex(Calendar.SUNDAY))
    }

    // --- roster ------------------------------------------------------------

    @Test
    fun `selection order leads and moodle fills the gaps`() {
        val order = CourseRosterMerge.rosterOrder(
            selectionCourseNos = listOf("B", "A"),
            moodleForSemester = listOf(moodle("C", "1131"), moodle("A", "1131")),
        )
        assertEquals(listOf("B", "A", "C"), order)
    }

    @Test
    fun `a failed selection scrape still yields the moodle roster`() {
        val order = CourseRosterMerge.rosterOrder(
            selectionCourseNos = null,
            moodleForSemester = listOf(moodle("C", "1131")),
        )
        assertEquals(listOf("C"), order)
    }

    @Test
    fun `moodle courses from other terms and without a course number are ignored`() {
        val kept = CourseRosterMerge.moodleCoursesFor(
            semester = "1131",
            enrolled = listOf(
                moodle("A", "1131"),
                moodle("B", "1122"),
                moodleWithoutCourseNo(),
            ),
        )
        assertEquals(listOf("A"), kept.map { it.courseNo })
    }

    @Test
    fun `a null moodle response is not an error`() {
        assertTrue(CourseRosterMerge.moodleCoursesFor("1131", null).isEmpty())
    }

    // --- preserveConfirmedSubmissions --------------------------------------

    @Test
    fun `a confirmed submission is not walked back by a flaky fetch`() {
        val out = CourseRosterMerge.preserveConfirmedSubmissions(
            remote = listOf(assignment("1", completed = false)),
            previouslyCompleted = setOf("1"),
        )
        assertTrue(out.single().isCompleted)
    }

    @Test
    fun `remote still wins when it says completed`() {
        val out = CourseRosterMerge.preserveConfirmedSubmissions(
            remote = listOf(assignment("1", completed = true)),
            previouslyCompleted = emptySet(),
        )
        assertTrue(out.single().isCompleted)
    }

    @Test
    fun `an assignment we never confirmed is left alone`() {
        val out = CourseRosterMerge.preserveConfirmedSubmissions(
            remote = listOf(assignment("1", completed = false)),
            previouslyCompleted = setOf("2"),
        )
        assertTrue(!out.single().isCompleted)
    }

    @Test
    fun `completedIds picks out only the submitted ones`() {
        assertEquals(
            setOf("1"),
            CourseRosterMerge.completedIds(
                listOf(assignment("1", completed = true), assignment("2"))
            ),
        )
    }
}
