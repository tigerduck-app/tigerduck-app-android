package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * Where a change an older build kept with no term goes on `/v4`: the course with its Moodle id in
 * the current term, else in the newest other term this phone caches, as iOS looks one up in its own
 * term and then in the catalogue's, newest first (`OutboxSender.courseKey`); with none, the term and
 * number the id names, as iOS's `termAndNumber` reads them. Made-up courses.
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

    @Test
    fun `an id reads as the first term it starts with and is longer than, and the rest its course number`() {
        val terms = listOf("1151", "1141")

        assertEquals(CoursePlace("1141", "CS1001"), termAndNumber("1141CS1001", terms))
        assertEquals(CoursePlace("1151", "MA1010002"), termAndNumber("1151MA1010002", terms))
        // Nothing of the number is checked, as on iOS.
        assertEquals(CoursePlace("1141", "1"), termAndNumber("11411", terms))
    }

    @Test
    fun `what the read rejects, as iOS's termAndNumber does, and a blank term`() {
        val terms = listOf("1151", "1141")
        val rejected = listOf(
            // Only a term: no course number follows it.
            "1141",
            "",
            // Begins with no term of the list: a term it does not offer, a number, a shorter code.
            "1132CS1001",
            "CS1001",
            "114CS1001",
            // A term inside, not at the start.
            "x1141CS1001",
        )

        assertEquals(rejected.associateWith { null }, rejected.associateWith { termAndNumber(it, terms) })
        // A blank term is no term, where iOS's hasPrefix("") would take the whole id as the number.
        assertEquals(null, termAndNumber("CS1001", listOf("")))
        assertEquals(CoursePlace("1141", "CS1001"), termAndNumber("1141CS1001", listOf("", "1141")))
    }

    @Test
    fun `a cached course of the id comes first, and the id's own term and number only with none, among the current term and the catalogue's`() {
        val cached = mapOf("1132" to listOf(course("XX1", "1141CS1001")))
        val catalogue = listOf("1151", "1141", "1132")

        assertEquals(CoursePlace("1132", "XX1"), legacyPlaceOf("1141CS1001", cached, current = "1151", catalogue = catalogue))
        // A term this phone never cached, which the catalogue offers.
        assertEquals(CoursePlace("1132", "CS1009"), legacyPlaceOf("1132CS1009", emptyMap(), current = "1151", catalogue = catalogue))
        // The current term, which the catalogue has yet to list.
        assertEquals(CoursePlace("1152", "CS1009"), legacyPlaceOf("1152CS1009", emptyMap(), current = "1152", catalogue = catalogue))
        // A term neither current nor the catalogue's: it parks.
        assertEquals(null, legacyPlaceOf("1122CS1009", emptyMap(), current = "1151", catalogue = catalogue))
    }
}
