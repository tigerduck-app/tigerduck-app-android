package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.widget.WidgetTheme

/**
 * Covers [weekCourseFill] and [weekCourseTextColor]: the Week widget keeps its
 * own tint under TigerDuck and takes the class table's pastel under TAT, while
 * a class that is on keeps the highlight in either.
 */
class WeekCourseColorsTest {

    private val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
    private val tat = VisualStylePolicy(VisualPreset.Tat)

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val light = courseColorPalette[2]
    private val dark = courseColorPaletteDark[2]
    private val courseColors = mapOf(calculus.courseNo to light)

    private val lightTheme = WidgetTheme.Light.copy(highlight = Color(0xFF007AFF))
    private val darkTheme = WidgetTheme.Dark.copy(highlight = Color(0xFF0A84FF))

    @Test
    fun `TigerDuck keeps the widget's tint and text`() {
        assertEquals(light.copy(alpha = 0.55f), weekCourseFill(calculus, false, lightTheme, tigerDuck, courseColors))
        assertEquals(dark, weekCourseFill(calculus, false, darkTheme, tigerDuck, courseColors))
        assertEquals(Color(0xFF1C1C1E), weekCourseTextColor(false, lightTheme, tigerDuck))
        assertEquals(Color.White, weekCourseTextColor(false, darkTheme, tigerDuck))
    }

    @Test
    fun `TAT takes the light colour's pastel with dark text in either theme`() {
        assertEquals(light.pastel(), weekCourseFill(calculus, false, lightTheme, tat, courseColors))
        assertEquals(light.pastel(), weekCourseFill(calculus, false, darkTheme, tat, courseColors))
        assertEquals(VisualStylePolicy.TextOnPastel, weekCourseTextColor(false, lightTheme, tat))
        assertEquals(VisualStylePolicy.TextOnPastel, weekCourseTextColor(false, darkTheme, tat))
    }

    @Test
    fun `a class that is on keeps the highlight with white text under either style`() {
        listOf(tigerDuck, tat).forEach { style ->
            listOf(lightTheme, darkTheme).forEach { theme ->
                assertEquals(theme.highlight, weekCourseFill(calculus, true, theme, style, courseColors))
                assertEquals(Color.White, weekCourseTextColor(true, theme, style))
            }
        }
    }

    @Test
    fun `a course in session is named in the text on the accent`() {
        val dark = Color(0xFF1C1C1E)
        assertEquals(dark, weekCourseTextColor(true, lightTheme.copy(onHighlight = dark), tigerDuck))
        assertEquals(Color.White, weekCourseTextColor(true, lightTheme, tigerDuck))
    }
}
