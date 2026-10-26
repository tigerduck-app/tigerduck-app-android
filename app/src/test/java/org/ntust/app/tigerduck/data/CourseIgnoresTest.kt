package org.ntust.app.tigerduck.data

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment

class CourseIgnoresTest {

    private fun assignment(id: String, courseNo: String) = Assignment(
        assignmentId = id,
        courseNo = courseNo,
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(1_700_000_000_000),
    )

    private val all = listOf(
        assignment("a1", "AAA"),
        assignment("a2", "AAA"),
        assignment("b1", "BBB"),
    )

    @Test
    fun `every assignment of an ignored course counts, and no other`() {
        val ids = CourseIgnores.assignmentIds(all, setOf(CourseIgnores.key("1141", "AAA")), "1141")

        assertEquals(setOf("a1", "a2"), ids)
    }

    @Test
    fun `a course ignored in another term leaves this term's assignments alone`() {
        // The same course number taken again: last term's choice does not carry over.
        val ids = CourseIgnores.assignmentIds(all, setOf(CourseIgnores.key("1132", "AAA")), "1141")

        assertEquals(emptySet<String>(), ids)
    }

    @Test
    fun `an assignment kept from last term stays under last term's ignore`() {
        // The cache keeps it while Moodle has no course for the new term yet.
        val kept = listOf(assignment("a1", "AAA").copy(semester = "1132"))

        assertEquals(
            setOf("a1"),
            CourseIgnores.assignmentIds(kept, setOf(CourseIgnores.key("1132", "AAA")), "1141"),
        )
        assertEquals(
            "nor does this term's choice for the same course number reach it",
            emptySet<String>(),
            CourseIgnores.assignmentIds(kept, setOf(CourseIgnores.key("1141", "AAA")), "1141"),
        )
    }

    @Test
    fun `the course numbers of one term come back from its keys`() {
        val keys = setOf("1141:AAA", "1141:BBB", "1132:CCC")

        assertEquals(setOf("AAA", "BBB"), CourseIgnores.courseNosIn("1141", keys))
        assertEquals("1132", CourseIgnores.semesterOf("1132:CCC"))
    }
}
