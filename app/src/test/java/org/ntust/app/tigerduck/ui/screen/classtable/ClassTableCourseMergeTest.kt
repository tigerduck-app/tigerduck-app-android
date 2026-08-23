package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * Everything the user did to a timetable has to survive the next refresh.
 * Each case here is one thing they could lose.
 */
class ClassTableCourseMergeTest {

    private fun course(
        no: String,
        name: String = "Course $no",
        color: String? = null,
        manual: Boolean = false,
        override: String? = null,
    ) = Course(
        courseNo = no,
        courseName = name,
        customColorHex = color,
        isManual = manual,
        customCourseName = override,
    )

    private fun merge(
        fetched: List<Course>,
        cached: List<Course> = emptyList(),
        names: CustomNameMap = emptyMap(),
        locale: String = "zh",
        deletedNos: Set<String> = emptySet(),
    ) = ClassTableCourseMerge.mergeFetched(fetched, cached, names, locale, deletedNos)

    @Test
    fun `the colour the user picked is carried across`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("A", color = "#FF0000")),
        )
        assertEquals("#FF0000", out.single().customColorHex)
    }

    @Test
    fun `the manual flag is carried across when the school also lists the course`() {
        // Losing it would send a later delete down the wrong path.
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("A", manual = true)),
        )
        assertTrue(out.single().isManual)
    }

    @Test
    fun `a course only the user knows about is kept`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("MINE", manual = true)),
        )
        assertEquals(setOf("A", "MINE"), out.map { it.courseNo }.toSet())
    }

    @Test
    fun `a non-manual cached course the roster dropped is gone`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("A"), course("STALE")),
        )
        assertEquals(listOf("A"), out.map { it.courseNo })
    }

    @Test
    fun `the rename for the current locale is applied`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "A", "en", "Renamed")
        val out = merge(fetched = listOf(course("A")), names = names, locale = "en")
        assertEquals("Renamed", out.single().customCourseName)
    }

    @Test
    fun `a rename in another locale is not applied`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "A", "en", "Renamed")
        val out = merge(fetched = listOf(course("A")), names = names, locale = "zh")
        assertNull(out.single().customCourseName)
    }

    @Test
    fun `a stale override on the fetched course is cleared, not kept`() {
        // The fetched rows can carry an override from an earlier locale's
        // cache; the map is authoritative, so an unset name must clear.
        val out = merge(fetched = listOf(course("A", override = "stale")), names = emptyMap())
        assertNull(out.single().customCourseName)
    }

    @Test
    fun `a manual leftover still gets its rename`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "MINE", "zh", "我的課")
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("MINE", manual = true)),
            names = names,
        )
        assertEquals("我的課", out.single { it.courseNo == "MINE" }.customCourseName)
    }

    @Test
    fun `a deleted course does not come back through the roster`() {
        val out = merge(fetched = listOf(course("A"), course("GONE")), deletedNos = setOf("GONE"))
        assertEquals(listOf("A"), out.map { it.courseNo })
    }

    @Test
    fun `a deleted course does not come back through the manual leftovers`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("GONE", manual = true)),
            deletedNos = setOf("GONE"),
        )
        assertEquals(listOf("A"), out.map { it.courseNo })
    }

    @Test
    fun `a course absent from cache gets no colour and is not manual`() {
        val out = merge(fetched = listOf(course("NEW", color = "#00FF00", manual = true)))
        assertNull(out.single().customColorHex)
        assertFalse(out.single().isManual)
    }
}
