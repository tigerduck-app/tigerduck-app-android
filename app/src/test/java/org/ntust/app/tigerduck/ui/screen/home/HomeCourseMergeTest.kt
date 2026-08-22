package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.Course
import java.util.Calendar
import java.util.Date

/**
 * A refresh overwrites the user's course list with whatever the network
 * returned. These pin what is allowed to survive that.
 */
class HomeCourseMergeTest {

    private fun course(
        no: String,
        name: String = "Course $no",
        color: String? = null,
        manual: Boolean = false,
    ) = Course(courseNo = no, courseName = name, customColorHex = color, isManual = manual)

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

    // --- mergeRemote -------------------------------------------------------

    @Test
    fun `a colour picked locally survives a refresh that does not know it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A", color = "#FF0000")),
            deletedNos = emptySet(),
        )
        assertEquals("#FF0000", merged.single().customColorHex)
    }

    @Test
    fun `a manually added course the roster never heard of is kept`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A"), course("MINE", manual = true)),
            deletedNos = emptySet(),
        )
        assertEquals(setOf("A", "MINE"), merged.map { it.courseNo }.toSet())
    }

    @Test
    fun `a cached non-manual course absent from the roster is dropped`() {
        // Dropping an enrolment the roster no longer lists is the whole point
        // of a refresh; only manual courses are exempt.
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A"), course("OLD")),
            deletedNos = emptySet(),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `a deleted course stays deleted even when the roster returns it`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A"), course("B")),
            cached = emptyList(),
            deletedNos = setOf("B"),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `deletion also applies to a manual leftover`() {
        // The filter runs on the merged list, not on the fetched half, so a
        // manual course the user deleted cannot come back through the
        // leftover path.
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("MINE", manual = true)),
            deletedNos = setOf("MINE"),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    @Test
    fun `a manual course the roster now lists is not duplicated`() {
        val merged = HomeCourseMerge.mergeRemote(
            remote = listOf(course("A")),
            cached = listOf(course("A", manual = true)),
            deletedNos = emptySet(),
        )
        assertEquals(listOf("A"), merged.map { it.courseNo })
    }

    // --- preserveConfirmedSubmissions --------------------------------------

    @Test
    fun `a confirmed submission is not walked back by a flaky fetch`() {
        val out = HomeCourseMerge.preserveConfirmedSubmissions(
            remote = listOf(assignment("1", completed = false)),
            previouslyCompleted = setOf("1"),
        )
        assertTrue(out.single().isCompleted)
    }

    @Test
    fun `remote still wins when it says completed`() {
        val out = HomeCourseMerge.preserveConfirmedSubmissions(
            remote = listOf(assignment("1", completed = true)),
            previouslyCompleted = emptySet(),
        )
        assertTrue(out.single().isCompleted)
    }

    @Test
    fun `an assignment we never confirmed is left alone`() {
        val out = HomeCourseMerge.preserveConfirmedSubmissions(
            remote = listOf(assignment("1", completed = false)),
            previouslyCompleted = setOf("2"),
        )
        assertTrue(!out.single().isCompleted)
    }

    @Test
    fun `completedIds picks out only the submitted ones`() {
        assertEquals(
            setOf("1"),
            HomeCourseMerge.completedIds(
                listOf(assignment("1", completed = true), assignment("2"))
            ),
        )
    }

    // --- roster ------------------------------------------------------------

    @Test
    fun `selection order leads and moodle fills the gaps`() {
        val order = HomeCourseMerge.rosterOrder(
            selectionCourseNos = listOf("B", "A"),
            moodleForSemester = listOf(moodle("C", "1131"), moodle("A", "1131")),
        )
        assertEquals(listOf("B", "A", "C"), order)
    }

    @Test
    fun `a failed selection scrape still yields the moodle roster`() {
        val order = HomeCourseMerge.rosterOrder(
            selectionCourseNos = null,
            moodleForSemester = listOf(moodle("C", "1131")),
        )
        assertEquals(listOf("C"), order)
    }

    @Test
    fun `moodle courses from other terms and without a course number are ignored`() {
        val kept = HomeCourseMerge.moodleCoursesFor(
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
        assertTrue(HomeCourseMerge.moodleCoursesFor("1131", null).isEmpty())
    }

    // --- weekday -----------------------------------------------------------

    @Test
    fun `calendar weekdays map to monday-first schedule keys`() {
        assertEquals(1, HomeCourseMerge.weekdayIndex(Calendar.MONDAY))
        assertEquals(5, HomeCourseMerge.weekdayIndex(Calendar.FRIDAY))
        // Sunday is 1 in Calendar and 7 here — the off-by-one that makes this
        // worth a test at all.
        assertEquals(7, HomeCourseMerge.weekdayIndex(Calendar.SUNDAY))
    }
}
