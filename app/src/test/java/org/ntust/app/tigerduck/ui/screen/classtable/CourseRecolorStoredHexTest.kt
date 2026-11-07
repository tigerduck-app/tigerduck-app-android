package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.MoodleColorBook
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark
import java.util.Locale

/**
 * What the class table's color sheet keeps for the color applied ([storedCourseColorHex]). In dark
 * mode the sheet opens on the color a course shows, a dark variant, and kept as is it stayed dark
 * in light mode too. A color typed in is kept as typed, though, a dark variant too.
 */
class CourseRecolorStoredHexTest {

    private fun color(rgb: Int) = Color(rgb or (0xFF shl 24))

    private fun hex(rgb: Int) = "#" + String.format(Locale.ROOT, "%06X", rgb and 0xFFFFFF)

    @Test
    fun `a Moodle color applied as the dark sheet shows it keeps the Moodle color`() {
        for (light in MoodleColorBook.READABLE) {
            val shown = checkNotNull(MoodleColorBook.darkFor(light))

            assertEquals(hex(light), storedCourseColorHex(color(shown), darkPalette = true, kept = color(light)))
        }
    }

    @Test
    fun `a Moodle color's dark variant typed in for a course not on that Moodle color is kept as typed`() {
        val light = MoodleColorBook.READABLE[0]
        val dark = checkNotNull(MoodleColorBook.darkFor(light))

        for (kept in listOf(courseColorPalette[2], color(dark), color(MoodleColorBook.READABLE[1]))) {
            assertEquals(hex(dark), storedCourseColorHex(color(dark), darkPalette = true, kept = kept))
        }
    }

    @Test
    fun `a dark preset keeps its light preset`() {
        assertEquals(
            hex(courseColorPalette[3].toArgb()),
            storedCourseColorHex(courseColorPaletteDark[3], darkPalette = true, kept = courseColorPalette[0]),
        )
    }

    @Test
    fun `a color of the sheet's own is kept as picked, in either palette`() {
        val kept = color(MoodleColorBook.READABLE[0])

        assertEquals("#123456", storedCourseColorHex(color(0x123456), darkPalette = true, kept = kept))
        assertEquals("#123456", storedCourseColorHex(color(0x123456), darkPalette = false, kept = kept))
    }

    @Test
    fun `the light sheet keeps a Moodle color as it is`() {
        val light = MoodleColorBook.READABLE[4]

        assertEquals(hex(light), storedCourseColorHex(color(light), darkPalette = false, kept = color(light)))
    }
}
