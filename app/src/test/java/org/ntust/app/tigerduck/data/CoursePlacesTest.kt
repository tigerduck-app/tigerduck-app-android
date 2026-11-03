package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * Where a change an older build kept with no term goes on `/v4`: the course with its Moodle id in
 * the current term, else in the newest other term this phone caches, as iOS looks one up in its own
 * term and then in the catalogue's, newest first (`OutboxSender.courseKey`). Made-up courses.
 */
class CoursePlacesTest {

    private fun course(courseNo: String, moodleId: String?) = Course(courseNo = courseNo, courseName = courseNo, moodleIdNumber = moodleId)

    private val everyTerm = mapOf(
        "1132" to listOf(course("XA1", "PAST01"), course("XA2", "BOTH01")),
        "1141" to listOf(course("XB1", "PAST01"), course("XB2", "")),
        "1151" to listOf(course("XC1", "BOTH01"), course("XC2", null)),
        "1142" to listOf(course("XD1", "PAST01")),
    )

    @Test
    fun `an id's courses go the current term's first, then the other terms' newest first`() {
        val places = placesByMoodleId(everyTerm, current = "1151")

        assertEquals(
            mapOf(
                "BOTH01" to listOf(CoursePlace("1151", "XC1"), CoursePlace("1132", "XA2")),
                "PAST01" to listOf(CoursePlace("1142", "XD1"), CoursePlace("1141", "XB1"), CoursePlace("1132", "XA1")),
            ),
            places.toSortedMap(),
        )
    }

    @Test
    fun `with no current term, or one this phone caches nothing of, the newest term goes first`() {
        assertEquals(listOf("1151", "1132"), placesByMoodleId(everyTerm)["BOTH01"]?.map { it.semester })
        assertEquals(listOf("1151", "1132"), placesByMoodleId(everyTerm, current = "1152")["BOTH01"]?.map { it.semester })
    }

    @Test
    fun `a course with no Moodle id, or an empty one, is no id's`() {
        assertEquals(setOf("BOTH01", "PAST01"), placesByMoodleId(everyTerm, current = "1151").keys)
    }
}
