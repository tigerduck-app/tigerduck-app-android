package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.CourseChangeInkDark
import org.ntust.app.tigerduck.ui.component.CourseChangeInkLight
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.WidgetTheme
import org.ntust.app.tigerduck.widget.widgetChangeInk

/**
 * How the class widgets mark the school's changes: the change ink, plain per theme as Glance
 * takes no theme, the room after `⇄`, a changed time as the iPhone's Week grid writes it, and a
 * cancelled Week block's fainter fill. Course numbers and rooms are made up.
 */
class WidgetChangeMarksTest {

    private val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
    private val tat = VisualStylePolicy(VisualPreset.Tat)

    private val compilers = Course.fromSchedule(
        "CC900", "Compilers", classroom = "T4-501", schedule = mapOf(4 to listOf("2", "3")),
    )
    private val light = courseColorPalette[2]
    private val courseColors = mapOf(compilers.courseNo to light)

    @Test
    fun `the change ink is the light or dark one of the widget's theme`() {
        assertEquals(CourseChangeInkLight, widgetChangeInk(isDark = false))
        assertEquals(CourseChangeInkDark, widgetChangeInk(isDark = true))
    }

    @Test
    fun `a Today row marks a change in the theme's ink, but in its own text on a class in session in full color`() {
        assertEquals(CourseChangeInkLight, todayRowInk(false, WidgetTheme.Light, tigerDuck))
        assertEquals(CourseChangeInkDark, todayRowInk(false, WidgetTheme.Dark, tigerDuck))
        assertEquals(Color.White, todayRowInk(true, WidgetTheme.Dark, tigerDuck))
        // A pastel row stays light in either theme, so its ink does too.
        assertEquals(CourseChangeInkLight, todayRowInk(true, WidgetTheme.Dark, tat))
        assertEquals(CourseChangeInkLight, todayRowInk(false, WidgetTheme.Dark, tat))
    }

    @Test
    fun `a Week block marks a change in the ink its fill takes`() {
        assertEquals(CourseChangeInkLight, weekChangeInk(false, WidgetTheme.Light, tigerDuck))
        assertEquals(CourseChangeInkDark, weekChangeInk(false, WidgetTheme.Dark, tigerDuck))
        assertEquals(CourseChangeInkLight, weekChangeInk(false, WidgetTheme.Dark, tat))
        assertEquals(WidgetTheme.Dark.onHighlight, weekChangeInk(true, WidgetTheme.Dark, tigerDuck))
    }

    @Test
    fun `a cancelled Week block keeps two fifths of its fill, as 0·1 to 0·25 on the iPhone`() {
        val plain = weekCourseFill(compilers, false, WidgetTheme.Light, tigerDuck, courseColors)
        val cancelled = weekCourseFill(compilers, false, WidgetTheme.Light, tigerDuck, courseColors, cancelled = true)
        assertEquals(plain.copy(alpha = plain.alpha * 0.4f), cancelled)
        assertEquals(
            light.pastel().copy(alpha = 0.4f),
            weekCourseFill(compilers, false, WidgetTheme.Dark, tat, courseColors, cancelled = true),
        )
    }

    @Test
    fun `a moved room follows the arrow and an unchanged one stands alone`() {
        val block = CourseBlock(compilers, 4, listOf("2", "3"))
        assertEquals("T4-501", block.roomLabel)
        assertEquals("⇄ TR-411", block.copy(movedRoom = "TR-411").roomLabel)
    }

    @Test
    fun `a Week block names its cancellation below its name, else its new time above and its new room below`() {
        val block = CourseBlock(compilers, 4, listOf("2", "3"))

        assertEquals(
            WeekBlockLines(null, TileLine("Cancelled", stepBack)),
            lines(block.copy(isCancelled = true, changedStart = "09:30")),
        )
        assertEquals(
            WeekBlockLines(TileLine("09:30", ink), TileLine("⇄ TR-411", ink)),
            lines(block.copy(changedStart = "09:30", movedRoom = "TR-411")),
        )
    }

    @Test
    fun `a Week block with no change names its room below its name in the name's color`() {
        assertEquals(WeekBlockLines(null, TileLine("T4-501", textColor)), lines(null))
    }

    @Test
    fun `a Week block with a changed time and no moved room keeps its room below`() {
        val block = CourseBlock(compilers, 4, listOf("2", "3"))
        assertEquals(
            WeekBlockLines(TileLine("09:30", ink), TileLine("T4-501", textColor)),
            lines(block.copy(changedStart = "09:30")),
        )
    }

    @Test
    fun `a Week block with an empty room has no line below its name`() {
        assertEquals(WeekBlockLines(null, null), lines(null, room = ""))
    }

    @Test
    fun `a Week block's room is the one of its own periods`() {
        val split = Course.fromSchedule(
            "CC901", "Networks", classroom = "T4-501", schedule = mapOf(4 to listOf("2", "3", "6", "7")),
            classroomMap = mapOf("4-2" to "T4-501", "4-3" to "T4-501", "4-6" to "R-201", "4-7" to "R-201"),
        )
        val roomless = Course.fromSchedule("CC902", "Seminar", schedule = mapOf(4 to listOf("2", "3")))
        assertEquals("T4-501", weekBlockRoom(split, 4, listOf("2", "3")))
        assertEquals("R-201", weekBlockRoom(split, 4, listOf("6", "7")))
        assertEquals("", weekBlockRoom(roomless, 4, listOf("2", "3")))
        assertEquals("", weekBlockRoom(split, 4, emptyList()))
    }

    @Test
    fun `a one-period block names the room the school moved it to, not the timetable's, and no time`() {
        val one = CourseBlock(compilers, 4, listOf("2"))
        assertEquals(
            WeekBlockLines(null, TileLine("⇄ TR-411", ink)),
            linesOf(one.copy(movedRoom = "TR-411", changedStart = "09:30"), length = 1),
        )
        assertEquals(WeekBlockLines(null, TileLine("T4-501", textColor)), linesOf(one, length = 1))
        assertEquals(WeekBlockLines(null, null), linesOf(one.copy(isCancelled = true), length = 1))
    }

    @Test
    fun `where the lines do not fit a one-period block keeps its name alone`() {
        val one = CourseBlock(compilers, 4, listOf("2"))
        assertEquals(WeekBlockLines(null, null), linesOf(one, length = 1) { false })
        assertEquals(WeekBlockLines(null, null), linesOf(one.copy(movedRoom = "TR-411"), length = 1) { false })
    }

    @Test
    fun `where the lines do not fit a longer block drops the timetable's room but keeps its own change`() {
        val two = CourseBlock(compilers, 4, listOf("2", "3"))
        assertEquals(
            WeekBlockLines(TileLine("09:30", ink), null),
            linesOf(two.copy(changedStart = "09:30"), length = 2) { false },
        )
        assertEquals(
            WeekBlockLines(TileLine("09:30", ink), TileLine("⇄ TR-411", ink)),
            linesOf(two.copy(changedStart = "09:30", movedRoom = "TR-411"), length = 2) { false },
        )
        assertEquals(WeekBlockLines(null, null), linesOf(two, length = 2) { false })
    }

    @Test
    fun `a block holds a line below its name only where its height has the room`() {
        // 40dp less 6dp of padding is 34dp: a 14.5dp name and one 11.6dp line, not two.
        assertEquals(true, blockLinesFit(40.dp, 1, 1f, 1f))
        assertEquals(false, blockLinesFit(40.dp, 2, 1f, 1f))
        // At 1.5 times the text, even one line no longer fits there.
        assertEquals(false, blockLinesFit(40.dp, 1, 1f, 1.5f))
        assertEquals(true, blockLinesFit(80.dp, 2, 1f, 1.5f))
    }

    private fun linesOf(change: CourseBlock?, length: Int, fits: (Int) -> Boolean = { true }) =
        weekBlockLinesFor(change, "T4-501", length, "Cancelled", ink, stepBack, textColor, fits)

    private val ink = CourseChangeInkLight
    private val stepBack = WidgetTheme.Light.onSurfaceVariant
    private val textColor = Color.White

    private fun lines(change: CourseBlock?, room: String = "T4-501") =
        weekBlockLines(change, room, "Cancelled", ink, stepBack, textColor)

    @Test
    fun `a changed time reads as both ends, the start alone or the end after a dash`() {
        val block = CourseBlock(compilers, 4, listOf("2", "3"))
        assertNull(block.changedTimeLabel)
        assertEquals("09:30–11:30", block.copy(changedStart = "09:30", changedEnd = "11:30").changedTimeLabel)
        assertEquals("09:30", block.copy(changedStart = "09:30").changedTimeLabel)
        assertEquals("–11:30", block.copy(changedEnd = "11:30").changedTimeLabel)
    }
}
