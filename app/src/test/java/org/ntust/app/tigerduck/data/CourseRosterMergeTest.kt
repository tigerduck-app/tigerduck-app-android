package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
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


    // --- roster ------------------------------------------------------------

    /**
     * The 加退選 case. "C" is a course the student dropped in 選課 that Moodle
     * has not let go of yet; topping the roster up with Moodle would put it
     * back on the timetable for however many days that takes.
     */
    @Test
    fun `a selection answer owns its term and moodle adds nothing`() {
        val order = CourseRosterMerge.rosterOrder(
            selectionCourseNos = listOf("B", "A", "B"),
            moodleForSemester = listOf(moodle("C", "1131"), moodle("A", "1131")),
        )
        assertEquals(listOf("B", "A"), order)
    }

    @Test
    fun `a failed selection scrape still yields the moodle roster`() {
        val order = CourseRosterMerge.rosterOrder(
            selectionCourseNos = null,
            moodleForSemester = listOf(moodle("C", "1131")),
        )
        assertEquals(listOf("C"), order)
    }

    /**
     * Empty is not "enrolled in nothing" — the scrape is a regex over HTML, so
     * a layout change reads as zero matches rather than as an error. It also
     * covers the terms 選課 does not serve, where the caller passes empty.
     */
    @Test
    fun `an empty selection answer falls back to moodle rather than blanking the term`() {
        val order = CourseRosterMerge.rosterOrder(
            selectionCourseNos = emptyList(),
            moodleForSemester = listOf(moodle("C", "1131"), moodle("D", "1131")),
        )
        assertEquals(listOf("C", "D"), order)
    }

    // --- 選課 drops -------------------------------------------------------

    /**
     * The drop this device witnessed: "C" was in the portal roster and this
     * answer no longer names it.
     */
    @Test
    fun `a course the answer no longer names is recorded as dropped`() {
        val dropped = CourseRosterMerge.selectionDrops(
            previous = emptySet(),
            localPortalNos = listOf("A", "B", "C"),
            roster = listOf("A", "B"),
        )
        assertEquals(setOf("C"), dropped)
    }

    /**
     * The reason drops are recorded from a diff rather than read off a
     * snapshot: a course another device added by hand is not in 選課 either,
     * and the uploaded rows are indistinguishable. It was never in this
     * device's portal roster, so it is never in the difference.
     */
    @Test
    fun `a course this device never held is not treated as a drop`() {
        val dropped = CourseRosterMerge.selectionDrops(
            previous = emptySet(),
            localPortalNos = listOf("A"),
            roster = listOf("A"),
        )
        assertTrue(dropped.isEmpty())
    }

    /** Re-adding in 加退選 clears the entry, so the course comes straight back. */
    @Test
    fun `a course the answer names again is cleared`() {
        val dropped = CourseRosterMerge.selectionDrops(
            previous = setOf("C", "D"),
            localPortalNos = listOf("A"),
            roster = listOf("A", "C"),
        )
        assertEquals(setOf("D"), dropped)
    }

    /**
     * An empty answer means 選課 was not consulted or the scrape drifted —
     * never "everything was dropped". Callers guard it too; this is the
     * second lock on a rule that would blank a whole term.
     */
    @Test
    fun `an empty answer records nothing and clears nothing`() {
        val dropped = CourseRosterMerge.selectionDrops(
            previous = setOf("C"),
            localPortalNos = listOf("A", "B"),
            roster = emptyList(),
        )
        assertEquals(setOf("C"), dropped)
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
