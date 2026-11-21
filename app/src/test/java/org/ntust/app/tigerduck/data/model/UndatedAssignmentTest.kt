package org.ntust.app.tigerduck.data.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.util.Date

/**
 * An assignment Moodle gives no due date (`duedate` 0), listed as iOS lists it (tigerduck-app#1029):
 * after every dated one, by title; never overdue or late; due on an extension a teacher grants it.
 */
class UndatedAssignmentTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = Date(1_800_000_000_000L)

    private fun assignment(
        id: String,
        title: String = "Task $id",
        due: Date? = null,
        extension: Date? = null,
        completed: Boolean = false,
        submittedAt: Date? = null,
    ) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = title,
        dueDate = due,
        isCompleted = completed,
        submittedAt = submittedAt,
        extensionDueDate = extension,
    )

    @After
    fun tearDown() = AppClock.setOverride(null)

    @Test
    fun `an undated assignment is due on an extension, else never`() {
        assertNull(assignment("1").studentDueDate)
        val extension = Date(now.time + day)
        assertEquals(extension, assignment("1", extension = extension).studentDueDate)
    }

    @Test
    fun `an undated assignment is never overdue, and handed in it is on time`() {
        AppClock.setOverride(ClockOverride(now.time, frozen = true, savedAtRealMillis = 0L))

        assertEquals(AssignmentStatus.PENDING, assignment("1").status(now))
        assertFalse(assignment("1").isOverdue)
        assertEquals(AssignmentStatus.SUBMITTED, assignment("1", completed = true, submittedAt = now).status(now))
    }

    @Test
    fun `the undated come after every dated one, by title, a number in it by its value`() {
        val all = listOf(
            assignment("a", title = "Report 10"),
            assignment("b", due = Date(now.time + 2 * day)),
            assignment("c", title = "report 9"),
            assignment("d", due = Date(now.time - day)),
            assignment("e", title = "Essay"),
        )

        assertEquals(listOf("d", "b", "e", "c", "a"), all.sortedByDueDate().map { it.assignmentId })
        assertEquals(listOf("b", "d", "e", "c", "a"), all.partitionedByDueDate(now).map { it.assignmentId })
    }

    @Test
    fun `dated ones keep their order on a tie`() {
        val due = Date(now.time + day)
        val all = listOf(assignment("b", due = due), assignment("a", due = due))

        assertEquals(listOf("b", "a"), all.sortedByDueDate().map { it.assignmentId })
        assertEquals(listOf("b", "a"), all.partitionedByDueDate(now).map { it.assignmentId })
    }
}
