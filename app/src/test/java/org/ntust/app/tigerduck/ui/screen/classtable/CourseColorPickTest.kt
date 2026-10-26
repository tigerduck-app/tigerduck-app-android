package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import java.util.Locale

/**
 * A colour pick moves another course off the picked colour
 * ([CourseColorPick]). The moved course used to drop its pin here and never
 * reach the server, which kept it on the picked colour, so devices drifted
 * into two courses sharing one colour (tigerduck-app/tigerduck-app#252 on
 * iOS).
 */
class CourseColorPickTest {

    private val red = "#FF6B6B"
    private val teal = "#4ECDC4"

    private fun course(no: String, hex: String? = null) =
        Course(courseNo = no, courseName = no, moodleIdNumber = "1151$no", customColorHex = hex)

    private fun color(hex: String) = Color(0xFF000000 or hex.removePrefix("#").toLong(16))

    /** What each course shows now: its pin, or a palette colour of its own. */
    private fun shownBy(courses: List<Course>): (String) -> Color = { no ->
        val course = courses.first { it.courseNo == no }
        course.customColorHex?.let(::color) ?: courseColorPalette[courses.indexOf(course) + 5]
    }

    @Test
    fun `a pick moves the course pinned to that colour to a pinned colour of its own, and reports both`() {
        val courses = listOf(course("CS1", red), course("CS2", teal), course("CS3"))
        val shown = shownBy(courses)

        val pick = CourseColorPick.assign(courses, "CS1", teal, shown)

        val byNo = pick.courses.associateBy { it.courseNo }
        assertEquals(teal, byNo.getValue("CS1").customColorHex)
        val moved = byNo.getValue("CS2").customColorHex
        assertNotNull("pinned, so the server can be told", moved)
        assertNotEquals(teal, moved)
        assertNotEquals("not on a colour a third course shows", shown("CS3"), color(moved!!))
        assertEquals(mapOf("CS1" to teal, "CS2" to moved), pick.changed)
        assertEquals(teal, pick.hex)
        assertEquals(null, byNo.getValue("CS3").customColorHex)
    }

    @Test
    fun `picking the colour a course already has changes nothing`() {
        val courses = listOf(course("CS1", red), course("CS2", teal))

        val pick = CourseColorPick.assign(courses, "CS1", red.lowercase(), shownBy(courses))

        assertEquals(emptyMap<String, String>(), pick.changed)
        assertEquals(red, pick.hex)
        assertEquals(courses, pick.courses)
    }

    @Test
    fun `a course put back to its default is pinned to the colour that resolves to, and sends it`() {
        // The backend reads a missing or null color_hex as "unchanged", so
        // sending nothing left the old colour on the server for the next
        // sync to bring back (tigerduck-app-android#240).
        val courses = listOf(course("CS1", red), course("CS2", teal))
        val shown = shownBy(courses)

        val pick = CourseColorPick.assign(courses, "CS1", null, shown)

        assertEquals(mapOf("CS1" to pick.hex), pick.changed)
        assertEquals(pick.hex, pick.courses[0].customColorHex)
        assertNotEquals("not on a colour another course shows", shown("CS2"), color(pick.hex))
        assertEquals(teal, pick.courses[1].customColorHex)
    }

    @Test
    fun `courses already sharing the colour each get their own, the same way every time`() {
        // Two courses left on one colour by an older build.
        val courses = listOf(course("CS3", teal), course("CS1", red), course("CS2", teal))

        val first = CourseColorPick.assign(courses, "CS1", teal, shownBy(courses))
        val again = CourseColorPick.assign(courses, "CS1", teal, shownBy(courses))

        val colors = first.courses.map { it.customColorHex }
        assertEquals("every course on a colour of its own", colors.size, colors.toSet().size)
        assertEquals(setOf("CS1", "CS2", "CS3"), first.changed.keys)
        assertEquals(first, again)
    }

    @Test
    fun `a pick that takes an unpinned course's colour sends only the pick`() {
        // The unpinned course has no colour on the server to correct; the
        // class table recolours it as the palette goes.
        val courses = listOf(course("CS1", red), course("CS2"))
        val shown = shownBy(courses)
        val itsColor = shown("CS2")
        val hex = String.format(Locale.ROOT, "#%06X", itsColor.toArgb() and 0xFFFFFF)

        val pick = CourseColorPick.assign(courses, "CS1", hex, shown)

        assertEquals(setOf("CS1"), pick.changed.keys)
        assertTrue(pick.courses[1].customColorHex == null)
    }

    @Test
    fun `the pick sends the picked course and every course it moved`() {
        val courses = listOf(course("CS1", red), course("CS3", teal), course("CS2", teal))

        val pick = CourseColorPick.assign(courses, "CS1", teal, shownBy(courses))

        assertEquals(
            listOf("CS1" to teal, "CS2" to pick.changed.getValue("CS2"), "CS3" to pick.changed.getValue("CS3")),
            pick.toSync,
        )
    }

    @Test
    fun `a pick of the colour a course already has still sends it`() {
        val courses = listOf(course("CS1", red))

        assertEquals(listOf("CS1" to red), CourseColorPick.assign(courses, "CS1", red, shownBy(courses)).toSync)
    }
}
