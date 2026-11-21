package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.ui.theme.pastelOutline
import org.ntust.app.tigerduck.widget.WidgetTheme

/**
 * Covers [widgetTile], a class's tile on the Today and Next Class widgets in the interface style,
 * as Home's course cards draw it: under TigerDuck the course's own color laid over the widget,
 * with the theme's text and the course's color around a class in session; under the pastel style
 * its pastel in either theme, in dark text, with the pastel's darker outline. A class over or
 * cancelled steps back.
 */
class WidgetTileTest {

    private val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
    private val pastelStyle = VisualStylePolicy(VisualPreset.Tat)

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val light = courseColorPalette[2]
    private val courseColors = mapOf(calculus.courseNo to light)

    @Test
    fun `TigerDuck lays the course's color over the widget, with the theme's text and the course's color as its accent`() {
        val day = widgetTile(calculus, courseColors, WidgetTheme.Light, tigerDuck)
        val night = widgetTile(calculus, courseColors, WidgetTheme.Dark, tigerDuck)

        assertEquals(light.copy(alpha = 0.50f).compositeOver(WidgetTheme.Light.background), day.fill)
        assertEquals(light.copy(alpha = 0.55f).compositeOver(WidgetTheme.Dark.background), night.fill)
        assertEquals(WidgetTheme.Dark.onSurface, night.ink)
        assertEquals(light, night.accent)
        assertEquals(true, night.onDark)
    }

    @Test
    fun `TigerDuck fades a class that steps back, fill and name`() {
        val past = widgetTile(calculus, courseColors, WidgetTheme.Light, tigerDuck, stepsBack = true)

        assertEquals(light.copy(alpha = 0.35f).compositeOver(WidgetTheme.Light.background), past.fill)
        assertEquals(WidgetTheme.Light.onSurfaceVariant, past.ink)
    }

    @Test
    fun `the pastel style draws the course's pastel in either theme, in dark text, outlined in its darker mix`() {
        listOf(WidgetTheme.Light, WidgetTheme.Dark).forEach { theme ->
            val tile = widgetTile(calculus, courseColors, theme, pastelStyle)
            assertEquals(light.pastel(), tile.fill)
            assertEquals(VisualStylePolicy.TextOnPastel, tile.ink)
            assertEquals(light.pastelOutline(), tile.accent)
            assertEquals(false, tile.onDark)
        }
        val past = widgetTile(calculus, courseColors, WidgetTheme.Dark, pastelStyle, stepsBack = true)
        assertEquals(light.pastel(0.82f), past.fill)
        assertEquals(VisualStylePolicy.TextOnPastelSecondary, past.ink)
    }
}
