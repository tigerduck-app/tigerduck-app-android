package org.ntust.app.tigerduck.ui.screen.classtable

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.shared.Course

/**
 * The GE dimension and the course's term span, carried from QueryCourse into
 * the detail dialog. Mirrors iOS 69c530ad.
 */
class CourseDetailMetadataTest {

    /** QueryCourse spells AllYear "F" (full year) or "H" (one semester). */
    @Test
    fun `AllYear maps to a duration label, anything else to none`() {
        assertEquals(R.string.course_detail_duration_full_year, CourseDetailMetadata.durationLabel("F"))
        assertEquals(R.string.course_detail_duration_one_semester, CourseDetailMetadata.durationLabel("H"))
        assertEquals(R.string.course_detail_duration_one_semester, CourseDetailMetadata.durationLabel("h"))
        assertNull(CourseDetailMetadata.durationLabel(""))
        assertNull(CourseDetailMetadata.durationLabel(null))
        assertNull(CourseDetailMetadata.durationLabel("X"))
    }

    /** Only GE courses carry a dimension; the rest send an empty string. */
    @Test
    fun `an empty dimension is no dimension`() {
        assertEquals("C", CourseDetailMetadata.dimension("C"))
        assertNull(CourseDetailMetadata.dimension(""))
        assertNull(CourseDetailMetadata.dimension("  "))
        assertNull(CourseDetailMetadata.dimension(null))
    }

    /**
     * A course split across QueryCourse rows only names its dimension on some
     * of them, so the first non-empty value wins, not the first row's.
     */
    @Test
    fun `the first non-empty value across rows wins`() {
        assertEquals("B", CourseService.firstNonEmpty(listOf("", null, "B", "C")))
        assertNull(CourseService.firstNonEmpty(listOf("", null)))
    }

    /**
     * A cache row written before these fields existed must still read back.
     * Gson's Unsafe path leaves them null, which is why they are nullable.
     */
    @Test
    fun `a cache row without dimension or allYear still deserializes`() {
        val legacyRow = """
            [{"courseNo":"CS101","courseName":"Algorithms","credits":3,
              "scheduleJson":"{\"1\":[\"2\",\"3\"]}","classroomMapJson":"{}",
              "isManual":false}]
        """.trimIndent()
        val type = object : TypeToken<List<Course>>() {}.type
        val course: Course = Gson().fromJson<List<Course>>(legacyRow, type).single()
        assertNull(course.dimension)
        assertNull(course.allYear)
    }

    @Test
    fun `dimension and allYear survive a cache round trip`() {
        val course = Course.fromSchedule(
            courseNo = "GE1001",
            courseName = "通識課",
            dimension = "C",
            allYear = "H",
        )
        val type = object : TypeToken<List<Course>>() {}.type
        val back: Course = Gson().fromJson<List<Course>>(Gson().toJson(listOf(course)), type).single()
        assertEquals("C", back.dimension)
        assertEquals("H", back.allYear)
    }
}
