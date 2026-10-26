package org.ntust.app.tigerduck.data

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment

class OutstandingAssignmentsTest {

    private fun assignment(
        id: String,
        completed: Boolean = false,
        courseNo: String = "CS1001301",
    ) = Assignment(
        assignmentId = id,
        courseNo = courseNo,
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(1_700_000_000_000),
        isCompleted = completed,
    )

    @Test
    fun `submitted, ignored and marked-done work is left out`() {
        val all = listOf(
            assignment("open"),
            assignment("submitted", completed = true),
            assignment("ignored"),
            assignment("marked"),
        )

        val outstanding = OutstandingAssignments.of(
            all,
            ignoredIds = setOf("ignored"),
            markedCompletedIds = setOf("marked"),
        )

        assertEquals(listOf("open"), outstanding.map { it.assignmentId })
    }

    @Test
    fun `a course whose open work is all ignored or marked done carries no badge`() {
        val all = listOf(
            assignment("ignored", courseNo = "AAA"),
            assignment("marked", courseNo = "AAA"),
            assignment("submitted", completed = true, courseNo = "BBB"),
            assignment("open", courseNo = "CCC"),
        )

        val courseNos = OutstandingAssignments.courseNos(
            all,
            ignoredIds = setOf("ignored"),
            markedCompletedIds = setOf("marked"),
        )

        assertEquals(setOf("CCC"), courseNos)
    }

    @Test
    fun `the order of the input is kept`() {
        val all = listOf(assignment("b"), assignment("a"), assignment("c"))

        val outstanding = OutstandingAssignments.of(all, emptySet(), emptySet())

        assertEquals(listOf("b", "a", "c"), outstanding.map { it.assignmentId })
    }
}
