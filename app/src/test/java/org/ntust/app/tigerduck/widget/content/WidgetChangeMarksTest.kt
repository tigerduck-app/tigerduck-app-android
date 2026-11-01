package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
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
        val ink = CourseChangeInkLight
        val stepBack = WidgetTheme.Light.onSurfaceVariant
        val block = CourseBlock(compilers, 4, listOf("2", "3"))

        assertEquals(WeekBlockLines(null, null), weekBlockLines(null, "Cancelled", ink, stepBack))
        assertEquals(
            WeekBlockLines(null, TileLine("Cancelled", stepBack)),
            weekBlockLines(block.copy(isCancelled = true, changedStart = "09:30"), "Cancelled", ink, stepBack),
        )
        assertEquals(
            WeekBlockLines(TileLine("09:30", ink), TileLine("⇄ TR-411", ink)),
            weekBlockLines(block.copy(changedStart = "09:30", movedRoom = "TR-411"), "Cancelled", ink, stepBack),
        )
    }

    @Test
    fun `a changed time reads as both ends, the start alone or the end after a dash`() {
        val block = CourseBlock(compilers, 4, listOf("2", "3"))
        assertNull(block.changedTimeLabel)
        assertEquals("09:30–11:30", block.copy(changedStart = "09:30", changedEnd = "11:30").changedTimeLabel)
        assertEquals("09:30", block.copy(changedStart = "09:30").changedTimeLabel)
        assertEquals("–11:30", block.copy(changedEnd = "11:30").changedTimeLabel)
    }
}
