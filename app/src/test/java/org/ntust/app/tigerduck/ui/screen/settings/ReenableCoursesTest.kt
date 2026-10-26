package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.ntust.app.tigerduck.push.ServerCourse

/**
 * The courses re-enable check compares every term Use Local acts on
 * ([courseListDifference]), not only the current one
 * (tigerduck-app-android#297).
 */
class ReenableCoursesTest {

    private fun row(semester: String, no: String, moodleId: String? = null) =
        ServerCourse(courseNo = no, courseName = no, semester = semester, moodleId = moodleId)

    @Test
    fun `a course deleted here in an earlier term counts as a difference`() {
        val diff = courseListDifference(
            serverCourses = listOf(row("1141", "A"), row("1141", "B"), row("1142", "C")),
            cached = mapOf("1141" to listOf("A", "B"), "1142" to listOf("C", "D")),
            hiding = setOf("1141:B"),
        )

        assertEquals(CourseListDifference(localOnly = 1, serverOnly = 1), diff)
    }

    @Test
    fun `a term only one side lists courses for is left to the reconcile`() {
        val diff = courseListDifference(
            serverCourses = listOf(row("1141", "A"), row("1132", "Z")),
            cached = mapOf("1132" to emptyList(), "1141" to listOf("A"), "1142" to listOf("C")),
            hiding = emptySet(),
        )

        assertFalse(diff.differs)
    }

    @Test
    fun `a row filed under the wrong term is not the server listing it there`() {
        val diff = courseListDifference(
            serverCourses = listOf(row("1142", "A"), row("1142", "B", moodleId = "1151B")),
            cached = mapOf("1142" to listOf("A")),
            hiding = emptySet(),
        )

        assertFalse(diff.differs)
    }

    @Test
    fun `a legacy bare tombstone hides its course in every term`() {
        val diff = courseListDifference(
            serverCourses = listOf(row("1141", "A"), row("1142", "A")),
            cached = mapOf("1141" to listOf("A", "B"), "1142" to listOf("A", "B")),
            hiding = setOf("B"),
        )

        assertFalse(diff.differs)
    }

    @Test
    fun `Use Server hides what only this phone lists, in every term both sides list`() {
        val hides = coursesUseServerHides(
            serverCourses = listOf(row("1141", "A"), row("1142", "C"), row("1142", "B", moodleId = "1151B")),
            cached = mapOf("1132" to listOf("Z"), "1141" to listOf("A", "X"), "1142" to listOf("B", "C")),
        )

        // 1132 has no server rows: left to the reconcile. 1142's B row is
        // misfiled, so the server does not list B there.
        assertEquals(mapOf("1141" to setOf("X"), "1142" to setOf("B")), hides)
    }
}
