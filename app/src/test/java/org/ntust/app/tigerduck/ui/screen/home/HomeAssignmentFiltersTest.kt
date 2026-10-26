package org.ntust.app.tigerduck.ui.screen.home

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter

class HomeAssignmentFiltersTest {

    private val now = Date(1_700_000_000_000)
    private fun at(offsetHours: Long) = Date(now.time + offsetHours * 3_600_000)

    private fun assignment(id: String, due: Date, completed: Boolean = false) =
        Assignment(
            assignmentId = id,
            courseNo = "C1",
            courseName = "Course",
            title = "Task $id",
            dueDate = due,
            isCompleted = completed,
        )

    private fun ids(list: List<Assignment>) = list.map { it.assignmentId }

    @Test
    fun `未完成 hides done, locally-marked, and ignored work, soonest first`() {
        val all = listOf(
            assignment("late", at(48)),
            assignment("soon", at(1)),
            assignment("done", at(2), completed = true),
            assignment("marked", at(3)),
            assignment("ignored", at(4)),
        )
        val visible = HomeAssignmentFilters.visible(
            all, ignoredIds = setOf("ignored"), markedCompletedIds = setOf("marked"),
            filter = AssignmentFilter.INCOMPLETE, now = now,
        )
        assertEquals(listOf("soon", "late"), ids(visible))
    }

    @Test
    fun `全部 puts upcoming work first and pushes overdue below it, newest first`() {
        // The ordering that makes the tab readable: what is coming up, soonest
        // first; then what is already past, most recently due at the top of
        // that block — so the boundary sits at "just went overdue" rather than
        // burying it under a term's history.
        val all = listOf(
            assignment("past-old", at(-72)),
            assignment("future-far", at(72)),
            assignment("past-recent", at(-1)),
            assignment("future-near", at(1)),
        )
        val visible = HomeAssignmentFilters.visible(
            all, emptySet(), emptySet(), AssignmentFilter.ALL, now,
        )
        assertEquals(listOf("future-near", "future-far", "past-recent", "past-old"), ids(visible))
    }

    @Test
    fun `全部 deliberately keeps ignored and completed items`() {
        val all = listOf(
            assignment("ignored", at(1)),
            assignment("done", at(2), completed = true),
        )
        val visible = HomeAssignmentFilters.visible(
            all, ignoredIds = setOf("ignored"), markedCompletedIds = emptySet(),
            filter = AssignmentFilter.ALL, now = now,
        )
        assertEquals(listOf("ignored", "done"), ids(visible))
    }

    @Test
    fun `an assignment due exactly now counts as future, not overdue`() {
        val all = listOf(assignment("now", now), assignment("past", at(-1)))
        val visible = HomeAssignmentFilters.visible(
            all, emptySet(), emptySet(), AssignmentFilter.ALL, now,
        )
        assertEquals(listOf("now", "past"), ids(visible))
    }

    @Test
    fun `已忽略 shows only ignored items, including ones already done`() {
        val all = listOf(
            assignment("ignored-done", at(1), completed = true),
            assignment("ignored-open", at(2)),
            assignment("other", at(3)),
        )
        val visible = HomeAssignmentFilters.visible(
            all, ignoredIds = setOf("ignored-done", "ignored-open"),
            markedCompletedIds = emptySet(), filter = AssignmentFilter.IGNORED, now = now,
        )
        assertEquals(listOf("ignored-done", "ignored-open"), ids(visible))
    }

    @Test
    fun `the course dot ignores work the user has ignored or marked done`() {
        val all = listOf(
            assignment("a", at(1)),
            assignment("b", at(2)),
            assignment("c", at(3), completed = true),
        )
        assertEquals(
            listOf("a"),
            ids(HomeAssignmentFilters.unfinishedFor(all, "C1", setOf("b"), emptySet())),
        )
        assertEquals(
            emptyList<String>(),
            ids(HomeAssignmentFilters.unfinishedFor(all, "C1", setOf("b"), setOf("a"))),
        )
    }

    @Test
    fun `已忽略 has something to show only while an ignored id is on the list`() {
        val all = listOf(assignment("a", at(1)), assignment("b", at(2)))
        assertEquals(true, HomeAssignmentFilters.hasIgnored(all, setOf("b")))
        assertEquals(false, HomeAssignmentFilters.hasIgnored(all, emptySet()))
        // An ignored assignment that has since left the list: the tab would
        // stand over an empty list.
        assertEquals(false, HomeAssignmentFilters.hasIgnored(all, setOf("gone")))
    }

    // An ignored course reaches these filters as the ignored ids of its
    // assignments, which is how HomeViewModel hands them over.
    private val term = "1141"
    private val ignoredCourse = setOf(CourseIgnores.key(term, "C2"))

    private fun inCourse(id: String, courseNo: String, completed: Boolean = false) =
        assignment(id, at(1), completed).copy(courseNo = courseNo)

    private fun ignoredWithCourses(all: List<Assignment>, ignoredIds: Set<String> = emptySet()) =
        ignoredIds + CourseIgnores.assignmentIds(all, ignoredCourse, term)

    @Test
    fun `an ignored course's work leaves the incomplete tab for the ignored one`() {
        val all = listOf(inCourse("kept", "C1"), inCourse("new", "C2"), inCourse("marked", "C2"))
        val ignored = ignoredWithCourses(all)

        val incomplete = HomeAssignmentFilters.visible(
            all, ignored, markedCompletedIds = setOf("marked"),
            filter = AssignmentFilter.INCOMPLETE, now = now,
        )
        val ignoredTab = HomeAssignmentFilters.visible(
            all, ignored, markedCompletedIds = setOf("marked"),
            filter = AssignmentFilter.IGNORED, now = now,
        )

        assertEquals(listOf("kept"), ids(incomplete))
        assertEquals(setOf("new", "marked"), ids(ignoredTab).toSet())
        assertEquals(true, HomeAssignmentFilters.hasIgnored(all, ignored))
    }

    @Test
    fun `an ignored course's tile dot stays off`() {
        val all = listOf(inCourse("open", "C2"))

        assertEquals(
            false,
            HomeAssignmentFilters.anyUnfinishedFor(all, "C2", ignoredWithCourses(all), emptySet()),
        )
        assertEquals(
            emptyList<String>(),
            ids(HomeAssignmentFilters.unfinishedFor(all, "C2", ignoredWithCourses(all), emptySet())),
        )
    }

    @Test
    fun `an extended assignment is ordered and counted upcoming by the student's own date`() {
        val all = listOf(
            assignment("extended", at(-2)).copy(extensionDueDate = at(10)),
            assignment("soon", at(5)),
            assignment("past", at(-1)),
        )
        val incomplete = HomeAssignmentFilters.visible(all, emptySet(), emptySet(), AssignmentFilter.INCOMPLETE, now)
        assertEquals(listOf("past", "soon", "extended"), ids(incomplete))
        val everything = HomeAssignmentFilters.visible(all, emptySet(), emptySet(), AssignmentFilter.ALL, now)
        assertEquals(listOf("soon", "extended", "past"), ids(everything))
    }
}
