package org.ntust.app.tigerduck.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class VisualStylePolicyTest {

    private val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
    private val tat = VisualStylePolicy(VisualPreset.Tat)

    /** Every palette colour, plus picked colours at the edges: black, white, pure blue. */
    private val colours = courseColorPalette + listOf(Color.Black, Color.White, Color(0xFF0000FF))

    // ---- preset ----

    @Test
    fun `presets store the same strings as the iPhone app and read unknown ones as TigerDuck`() {
        assertEquals("default", VisualPreset.TigerDuck.rawValue)
        assertEquals("tat", VisualPreset.Tat.rawValue)
        assertEquals(VisualPreset.Tat, VisualPreset.fromRawValue("tat"))
        assertEquals(VisualPreset.TigerDuck, VisualPreset.fromRawValue("default"))
        assertEquals(VisualPreset.TigerDuck, VisualPreset.fromRawValue(null))
        // The iPhone's Apple preset has no Android counterpart.
        assertEquals(VisualPreset.TigerDuck, VisualPreset.fromRawValue("iosInspired"))
        assertEquals(VisualPreset.TigerDuck, VisualPreset.Default)
    }

    @Test
    fun `preset names are the same in every language`() {
        assertEquals("TigerDuck", VisualPreset.TigerDuck.displayName)
        assertEquals("TAT", VisualPreset.Tat.displayName)
    }

    // ---- TigerDuck keeps today's class table ----

    @Test
    fun `TigerDuck draws blocks with the period times`() {
        assertEquals(VisualStylePolicy.ClassTableGridStyle.Blocks, tigerDuck.classTableGridStyle)
        assertTrue(tigerDuck.classTableShowsPeriodTimes)
    }

    @Test
    fun `TigerDuck fills and text are the ones the class table always had`() {
        val colour = Color(0xFFFF6B6B)
        val dark = Color(0xFF994747)
        assertEquals(colour.copy(alpha = 0.5f), tigerDuck.classTableCourseFill(colour, dark, isDark = false))
        assertEquals(dark, tigerDuck.classTableCourseFill(colour, dark, isDark = true))
        assertEquals(Color(0xFF1C1C1E), tigerDuck.classTableCourseTextColor(isDark = false))
        assertEquals(Color.White, tigerDuck.classTableCourseTextColor(isDark = true))
        assertEquals(Color.White.copy(alpha = 0.7f), tigerDuck.classTableCourseSecondaryTextColor(isDark = true))
    }

    @Test
    fun `TigerDuck keeps the faint empty slot and has no row bands`() {
        val surfaceVariant = Color(0xFFE5E5EA)
        assertEquals(surfaceVariant.copy(alpha = 0.3f), tigerDuck.classTableEmptySlotColor(surfaceVariant))
        assertNull(tigerDuck.classTableRowBand(0, Color.Black))
        assertNull(tigerDuck.classTableRowBand(1, Color.Black))
    }

    // ---- TAT ----

    @Test
    fun `TAT draws period tiles without the period times`() {
        assertEquals(VisualStylePolicy.ClassTableGridStyle.PeriodTiles, tat.classTableGridStyle)
        assertFalse(tat.classTableShowsPeriodTimes)
    }

    @Test
    fun `TAT leaves empty slots bare and alternates row bands from a stronger first row`() {
        assertEquals(Color.Transparent, tat.classTableEmptySlotColor(Color(0xFFE5E5EA)))
        val ink = Color(0xFF1C1C1E)
        assertEquals(ink.copy(alpha = 0.08f), tat.classTableRowBand(0, ink))
        assertEquals(ink.copy(alpha = 0.04f), tat.classTableRowBand(1, ink))
        assertEquals(ink.copy(alpha = 0.08f), tat.classTableRowBand(2, ink))
    }

    @Test
    fun `TAT fills a course with an opaque lighter pastel of its own hue`() {
        colours.forEach { colour ->
            val fill = tat.classTableCourseFill(colour, Color.Magenta, isDark = false)
            assertEquals("opacity of $colour", 1f, fill.alpha)
            assertTrue("lighter than $colour", fill.red >= colour.red && fill.green >= colour.green && fill.blue >= colour.blue)
            // Within a hundredth: an sRGB Color holds 8 bits a channel, and
            // rounding the lifted channels moves the hue a little.
            hue(colour)?.let { assertEquals("hue of $colour", it, hue(fill)!!, 0.01) }
        }
    }

    @Test
    fun `TAT mixes each sRGB channel 65 percent toward white, as the iPhone app does`() {
        val fill = Color.Black.pastel()
        assertEquals(0.65f, fill.red, 0.001f)
        assertEquals(0.65f, fill.green, 0.001f)
        assertEquals(0.65f, fill.blue, 0.001f)
        val coral = Color(0xFFFF6B6B).pastel()
        assertEquals(1f, coral.red, 0.001f)
        assertEquals(0x6B / 255f + (1 - 0x6B / 255f) * 0.65f, coral.green, 0.001f)
    }

    @Test
    fun `TAT tiles stay the same light pastel with dark text in dark mode`() {
        colours.forEach { colour ->
            assertEquals(
                tat.classTableCourseFill(colour, Color.Magenta, isDark = false),
                tat.classTableCourseFill(colour, Color.Magenta, isDark = true),
            )
        }
        assertEquals(Color(0xFF1A1A1A), tat.classTableCourseTextColor(isDark = true))
        assertEquals(tat.classTableCourseTextColor(false), tat.classTableCourseTextColor(true))
    }

    @Test
    fun `TAT text reads at 7 to 1 and the room hint at 4_5 to 1 on every pastel`() {
        colours.forEach { colour ->
            val fill = tat.classTableCourseFill(colour, colour, isDark = false)
            val text = contrast(tat.classTableCourseTextColor(isDark = true), fill)
            val hint = contrast(tat.classTableCourseSecondaryTextColor(isDark = true), fill)
            assertTrue("text on the pastel of $colour: $text", text >= 7.0)
            assertTrue("hint on the pastel of $colour: $hint", hint >= 4.5)
        }
    }

    // ---- required courses ----

    @Test
    fun `TigerDuck outlines a required course in its own colour`() {
        colours.forEach { colour ->
            assertEquals(colour, tigerDuck.classTableCourseOutline(colour))
        }
    }

    @Test
    fun `TAT outlines a required course so it stands out from its pastel`() {
        colours.forEach { colour ->
            val fill = tat.classTableCourseFill(colour, Color.Magenta, isDark = false)
            val outline = contrast(tat.classTableCourseOutline(colour), fill)
            assertTrue("outline on the pastel of $colour: $outline", outline >= 3.0)
        }
    }

    @Test
    fun `the pastel outline mixes each sRGB channel half toward black, as the iPhone app does`() {
        // Within one step: an sRGB Color holds 8 bits a channel, so 0.5 lands on 128/255.
        val step = 1 / 255f
        val white = Color.White.pastelOutline()
        assertEquals(0.5f, white.red, step)
        assertEquals(0.5f, white.green, step)
        assertEquals(0.5f, white.blue, step)
        assertEquals(1f, white.alpha, 0f)
        val coral = Color(0xFFFF6B6B).pastelOutline()
        assertEquals(0.5f, coral.red, step)
        assertEquals(0x6B / 255f * 0.5f, coral.green, step)
    }

    // ---- course cards ----

    @Test
    fun `TigerDuck cards keep the tint each card picks, laid over the surface in dark mode`() {
        val colour = Color(0xFFFF6B6B)
        val surface = Color(0xFF1C1C1E)
        assertEquals(VisualStylePolicy.CourseColorStyle.Tint, tigerDuck.courseColorStyle)
        assertEquals(
            colour.copy(alpha = 0.5f),
            tigerDuck.courseCardFill(colour, tintAlpha = 0.5f, muted = false, surface = surface, isDark = false),
        )
        // Muted changes nothing here: each card already picks a fainter tint for it.
        assertEquals(
            colour.copy(alpha = 0.35f),
            tigerDuck.courseCardFill(colour, tintAlpha = 0.35f, muted = true, surface = surface, isDark = false),
        )
        assertEquals(
            colour.copy(alpha = 0.55f).compositeOver(surface),
            tigerDuck.courseCardFill(colour, tintAlpha = 0.55f, muted = false, surface = surface, isDark = true),
        )
    }

    @Test
    fun `TigerDuck cards keep the theme's text and the grey mark`() {
        val onSurface = Color(0xFFF5F5F5)
        assertEquals(onSurface, tigerDuck.courseCardTextColor(onSurface))
        assertEquals(onSurface.copy(alpha = ContentAlpha.SECONDARY), tigerDuck.courseCardSecondaryTextColor(onSurface))
        assertEquals(Color.Gray.copy(alpha = 0.5f), tigerDuck.courseCardMarkColor)
    }

    @Test
    fun `TAT cards take the class table's pastel in either theme, whatever tint a card picks`() {
        assertEquals(VisualStylePolicy.CourseColorStyle.Pastel, tat.courseColorStyle)
        colours.forEach { colour ->
            val tile = tat.classTableCourseFill(colour, Color.Magenta, isDark = false)
            listOf(false, true).forEach { isDark ->
                listOf(0.35f, 0.5f, 0.7f).forEach { tintAlpha ->
                    assertEquals(
                        "$colour at $tintAlpha, dark $isDark",
                        tile,
                        tat.courseCardFill(colour, tintAlpha, muted = false, surface = Color.Black, isDark = isDark),
                    )
                }
            }
        }
    }

    @Test
    fun `TAT mutes a card with a paler opaque pastel of the same hue`() {
        colours.forEach { colour ->
            val card = tat.courseCardFill(colour, 0.5f, muted = false, surface = Color.Black, isDark = false)
            val muted = tat.courseCardFill(colour, 0.5f, muted = true, surface = Color.Black, isDark = false)
            assertEquals("opacity of $colour", 1f, muted.alpha)
            assertTrue(
                "paler than the card for $colour",
                muted.red >= card.red && muted.green >= card.green && muted.blue >= card.blue,
            )
            hue(colour)?.let { assertEquals("hue of $colour", it, hue(muted)!!, 0.01) }
            // White has no paler pastel; every other colour has one to step back to.
            if (colour != Color.White) assertTrue("muted card stands apart for $colour", muted != card)
        }
    }

    @Test
    fun `TAT card text reads at 7 to 1 and its details and mark at 4_5 to 1 on a card or a muted one`() {
        // As they are: the slider's muted cards draw them so. A class already
        // over fades them on top, under either style, and reads as faded.
        val onSurface = Color(0xFFF5F5F5)
        assertEquals(VisualStylePolicy.TextOnPastel, tat.courseCardTextColor(onSurface))
        colours.forEach { colour ->
            listOf(false, true).forEach { muted ->
                val fill = tat.courseCardFill(colour, 0.5f, muted, surface = Color.Black, isDark = true)
                val text = contrast(tat.courseCardTextColor(onSurface), fill)
                val details = contrast(tat.courseCardSecondaryTextColor(onSurface), fill)
                val mark = contrast(tat.courseCardMarkColor, fill)
                assertTrue("text on $colour, muted $muted: $text", text >= 7.0)
                assertTrue("details on $colour, muted $muted: $details", details >= 4.5)
                assertTrue("mark on $colour, muted $muted: $mark", mark >= 4.5)
            }
        }
    }

    // ---- every other course color ----

    @Test
    fun `TigerDuck draws a course's color and its text as each place picks them`() {
        val color = Color(0xFFFF6B6B)
        val dark = Color(0xFF994747)
        assertEquals(dark, tigerDuck.courseAccentColor(color, tint = dark))
        assertEquals(color.copy(alpha = 0.85f), tigerDuck.courseAccentColor(color, tint = color.copy(alpha = 0.85f)))
        assertEquals(Color.White, tigerDuck.courseAccentTextColor(tintText = Color.White))
        assertEquals(Color.White.copy(alpha = 0.85f), tigerDuck.courseAccentSecondaryTextColor(Color.White.copy(alpha = 0.85f)))
    }

    @Test
    fun `TAT draws a course's color as the class table's pastel in either theme, with dark text`() {
        colours.forEach { color ->
            val tile = tat.classTableCourseFill(color, Color.Magenta, isDark = false)
            listOf(color, Color.Magenta, color.copy(alpha = 0.85f)).forEach { tint ->
                assertEquals("$color drawn for $tint", tile, tat.courseAccentColor(color, tint))
            }
            val text = contrast(tat.courseAccentTextColor(tintText = Color.White), tile)
            val details = contrast(tat.courseAccentSecondaryTextColor(tintText = Color.White), tile)
            assertTrue("text on $color: $text", text >= 7.0)
            assertTrue("details on $color: $details", details >= 4.5)
        }
        assertEquals(VisualStylePolicy.TextOnPastel, tat.courseAccentTextColor(Color.White))
        assertEquals(VisualStylePolicy.TextOnPastelSecondary, tat.courseAccentSecondaryTextColor(Color.White))
    }

    // ---- time slider track ----

    @Test
    fun `TigerDuck keeps the track's tints and the course-coloured dot, with no outline`() {
        val colour = Color(0xFFFF6B6B)
        assertEquals(colour.copy(alpha = 0.5f), tigerDuck.timeSliderSegmentColor(colour, tintAlpha = 0.5f))
        assertEquals(colour.copy(alpha = 0.3f), tigerDuck.timeSliderSegmentColor(colour, tintAlpha = 0.3f))
        assertNull(tigerDuck.timeSliderActiveOutline)
        assertEquals(colour, tigerDuck.timeSliderInClassDotColor(colour))
    }

    @Test
    fun `TAT draws every segment in the class's pastel, the active one outlined, with a dark dot`() {
        colours.forEach { colour ->
            val segment = tat.timeSliderSegmentColor(colour, tintAlpha = 0.5f)
            assertEquals(tat.classTableCourseFill(colour, Color.Magenta, isDark = false), segment)
            assertEquals(segment, tat.timeSliderSegmentColor(colour, tintAlpha = 0.3f))
            // A mark needs 3:1 against what it sits on.
            val outline = contrast(tat.timeSliderActiveOutline!!, segment)
            val dot = contrast(tat.timeSliderInClassDotColor(colour), segment)
            assertTrue("outline on $colour: $outline", outline >= 3.0)
            assertTrue("dot on $colour: $dot", dot >= 3.0)
        }
    }

    /** The HSV hue from 0 up to 1, or null for a grey, which has none. */
    private fun hue(colour: Color): Double? {
        val (r, g, b) = Triple(colour.red.toDouble(), colour.green.toDouble(), colour.blue.toDouble())
        val top = max(r, max(g, b))
        val spread = top - min(r, min(g, b))
        if (spread < 0.0001) return null
        val sector = when (top) {
            r -> (g - b) / spread
            g -> (b - r) / spread + 2
            else -> (r - g) / spread + 4
        }
        return ((sector / 6) % 1 + 1) % 1
    }

    /** The WCAG contrast ratio of [text], blended over the opaque [background]. */
    private fun contrast(text: Color, background: Color): Double {
        val a = text.alpha
        val blended = Color(
            red = text.red * a + background.red * (1 - a),
            green = text.green * a + background.green * (1 - a),
            blue = text.blue * a + background.blue * (1 - a),
        )
        val (lb, lt) = luminance(background) to luminance(blended)
        return (max(lb, lt) + 0.05) / (min(lb, lt) + 0.05)
    }

    private fun luminance(colour: Color): Double {
        fun linear(c: Float): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(colour.red) + 0.7152 * linear(colour.green) + 0.0722 * linear(colour.blue)
    }

    @Test
    fun `hue helper agrees with a known colour`() {
        // Pure red sits at hue 0, pure blue at two thirds: guards the helper the hue test leans on.
        assertEquals(0.0, hue(Color.Red)!!, 0.0001)
        assertEquals(2.0 / 3.0, hue(Color(0xFF0000FF))!!, 0.0001)
        assertTrue(abs(contrast(Color.Black, Color.White) - 21.0) < 0.01)
    }
}
