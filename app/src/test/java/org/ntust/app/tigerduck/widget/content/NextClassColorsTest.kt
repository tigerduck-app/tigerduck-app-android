package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.widget.WidgetTheme

/**
 * The Next Class widget under TAT is its course's card, as Home's time machine card is: the
 * class table's pastel with dark text in either theme, and the badge and the progress in that dark
 * text's color, which the badge's white text reads on whatever color the course was given.
 * TigerDuck, and a widget with no course to show, keep the theme's colors.
 */
class NextClassColorsTest {

    private val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
    private val tat = VisualStylePolicy(VisualPreset.Tat)

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val light = courseColorPalette[2]
    private val courseColors = mapOf(calculus.courseNo to light)

    @Test
    fun `TAT draws the widget in its course's pastel in either theme, in dark text`() {
        listOf(WidgetTheme.Light, WidgetTheme.Dark).forEach { theme ->
            val drawn = nextClassColors(calculus, theme, tat, courseColors)

            assertEquals(light.pastel(), drawn.background)
            assertEquals(VisualStylePolicy.TextOnPastel, drawn.onSurface)
            assertEquals(VisualStylePolicy.TextOnPastelSecondary, drawn.onSurfaceVariant)
            assertEquals(VisualStylePolicy.TextOnPastel, drawn.highlight)
            assertFalse(drawn.isDark)
        }
    }

    /** The badge's 12sp bold text is not large text: it needs 4.5:1, on a picked color too. */
    @Test
    fun `the badge's white text reads on the brightest colors a course can be given`() {
        listOf(Color(0xFFFFFF00), Color(0xFFFFFFFF), Color(0xFF00FFFF)).forEach { picked ->
            val drawn = nextClassColors(calculus, WidgetTheme.Light, tat, mapOf(calculus.courseNo to picked))

            assertTrue("on $picked", contrast(Color.White, drawn.highlight) >= 4.5)
        }
    }

    @Test
    fun `TigerDuck keeps the theme's colors`() {
        listOf(WidgetTheme.Light, WidgetTheme.Dark).forEach { theme ->
            assertEquals(theme, nextClassColors(calculus, theme, tigerDuck, courseColors))
        }
    }

    @Test
    fun `with no course to show the widget keeps the theme's colors`() {
        assertEquals(WidgetTheme.Dark, nextClassColors(null, WidgetTheme.Dark, tat, courseColors))
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }
}
