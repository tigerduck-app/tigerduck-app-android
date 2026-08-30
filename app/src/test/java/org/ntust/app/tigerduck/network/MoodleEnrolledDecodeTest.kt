package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the null-row hazard in `core_enrol_get_users_courses` decoding.
 *
 * Gson does not strip nulls out of a JSON array, so a payload containing a
 * null row decodes into a list holding a null while Kotlin's type system
 * insists the element type is non-null. `ClassTableViewModel.fetchData`
 * dereferences those elements outside any try/catch, so a single null row
 * used to escape to the default uncaught handler and kill the process.
 */
class MoodleEnrolledDecodeTest {

    @Test
    fun `drops null rows from the array`() {
        val json = """
            [
              {"id":1,"fullname":"1151 AT1234 課程","idnumber":"1151AT1234"},
              null,
              {"id":2,"fullname":"1151 AT5678 課程","idnumber":"1151AT5678"}
            ]
        """.trimIndent()

        val decoded = MoodleService.decodeEnrolledCourses(json)

        assertEquals(2, decoded.size)
        assertEquals(listOf("1151AT1234", "1151AT5678"), decoded.map { it.idnumber })
    }

    /**
     * The regression itself: every element must survive a dereference of the
     * fields `fetchData` reads un-guarded.
     */
    @Test
    fun `every surviving row can be dereferenced without NPE`() {
        val json = """[null, {"id":1,"idnumber":"1151AT1234"}, null]"""

        val decoded = MoodleService.decodeEnrolledCourses(json)

        // Would have thrown NullPointerException before the filter landed.
        val semesters = decoded.map { it.semesterCode }
        val courseNos = decoded.map { it.courseNo }

        assertEquals(listOf("1151"), semesters)
        assertEquals(listOf("AT1234"), courseNos)
    }

    @Test
    fun `an all-null array decodes to empty rather than throwing`() {
        assertTrue(MoodleService.decodeEnrolledCourses("[null,null]").isEmpty())
    }

    @Test
    fun `a JSON null body decodes to empty`() {
        assertTrue(MoodleService.decodeEnrolledCourses("null").isEmpty())
    }

    @Test
    fun `well-formed payloads are unchanged`() {
        val json = """[{"id":7,"idnumber":"1142PE139B022","fullname":"1142 PE139B022 體育"}]"""

        val decoded = MoodleService.decodeEnrolledCourses(json)

        assertEquals(1, decoded.size)
        assertEquals(7, decoded.first().id)
        assertEquals("1142", decoded.first().semesterCode)
    }
}
