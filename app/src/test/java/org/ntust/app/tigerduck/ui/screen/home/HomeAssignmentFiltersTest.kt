package org.ntust.app.tigerduck.ui.screen.home

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
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
    fun `ISO timestamps parse as UTC, and anything unparseable reads as zero`() {
        assertEquals(0L, HomeAssignmentFilters.parseIsoTimestamp(""))
        assertEquals(0L, HomeAssignmentFilters.parseIsoTimestamp("not a date"))
        assertEquals(1_700_000_000_000L, HomeAssignmentFilters.parseIsoTimestamp("2023-11-14T22:13:20"))
        // A fractional part is truncated rather than rejected.
        assertEquals(
            1_700_000_000_000L,
            HomeAssignmentFilters.parseIsoTimestamp("2023-11-14T22:13:20.123456Z"),
        )
    }
}
