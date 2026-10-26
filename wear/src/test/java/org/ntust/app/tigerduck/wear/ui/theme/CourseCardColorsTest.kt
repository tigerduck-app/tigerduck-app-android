package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's course cards in each interface style the phone sends: TigerDuck's
 * tint over the black, or the TAT look's opaque pastel with dark text.
 */
class CourseCardColorsTest {

    // Bright, dark and pale course colors, the watch's palette ends among them.
    private val courseColors = listOf(
        Color(0xFFDC2626), Color(0xFFCA8A04), Color(0xFF16A34A), Color(0xFF2563EB), Color(0xFF7C3AED),
        Color(0xFFDB2777), Color(0xFF1A237E), Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFFFEB3B),
    )

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun `the phone's TAT style draws pastel cards, and anything else tints them`() {
        assertEquals(WatchCardStyle.Pastel, WatchCardStyle.fromVisualPreset("tat"))
        assertEquals(WatchCardStyle.Tint, WatchCardStyle.fromVisualPreset("default"))
        // A phone that predates the style sends none; a later style this build doesn't know.
        assertEquals(WatchCardStyle.Tint, WatchCardStyle.fromVisualPreset(null))
        assertEquals(WatchCardStyle.Tint, WatchCardStyle.fromVisualPreset("someLaterStyle"))
    }

    @Test
    fun `a TigerDuck card is the course color over the black, as it has always been`() {
        val colors = courseCardColors(WatchCardStyle.Tint, Color(0xFFDC2626), tintAlpha = 0.22f, muted = false)
        assertEquals(Color(0xFFDC2626).copy(alpha = 0.22f), colors.fill)
        assertEquals(Color(0xFFDC2626), colors.emphasis)
        assertEquals(Color.Unspecified, colors.text)
    }

    @Test
    fun `a TAT card is the course color's opaque pastel, paler when it steps back`() {
        val course = Color(0xFF2563EB)
        val card = courseCardColors(WatchCardStyle.Pastel, course, tintAlpha = 0.22f, muted = false)
        val muted = courseCardColors(WatchCardStyle.Pastel, course, tintAlpha = 0.08f, muted = true)
        assertEquals(1f, card.fill.alpha, 0f)
        assertEquals(0.65f + 0.35f * course.red, card.fill.red, 0.01f)
        assertTrue(muted.fill.luminance() > card.fill.luminance())
        assertEquals(Color(0xFF1A1A1A), card.text)
    }

    @Test
    fun `dark text reads on every pastel, and every pastel card stands out on the black`() {
        for (course in courseColors) for (muted in listOf(false, true)) {
            val colors = courseCardColors(WatchCardStyle.Pastel, course, tintAlpha = 0.22f, muted = muted)
            assertTrue("text on $course", contrast(colors.text, colors.fill) >= 7f)
            assertTrue("secondary on $course", contrast(colors.secondaryText.compositeOver(colors.fill), colors.fill) >= 4.5f)
            assertTrue("emphasis on $course", contrast(colors.emphasis.compositeOver(colors.fill), colors.fill) >= 4.5f)
            assertTrue("card on black, $course", contrast(colors.fill, Color.Black) >= 3f)
        }
    }
}
