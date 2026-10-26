package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.robolectric.RobolectricTestRunner

/** The phone's accent gradient as the watch reads it from a snapshot and paints it. */
@RunWith(RobolectricTestRunner::class)
class WearAccentBrushTest {

    private val blue = Color(0xFF007AFF)
    private val purple = Color(0xFFAF52DE)
    private val bounds = Rect(left = 10f, top = 20f, right = 110f, bottom = 220f)

    @Test
    fun `with no gradient the accent is one color`() {
        assertEquals(SolidColor(blue), accentBrush(blue, null, bounds))
    }

    // On the screen's own sides, as the phone draws it, across the mark's bounds.
    @Test
    fun `each direction runs across the bounds from its start side to its end side`() {
        val expected = mapOf(
            AccentGradientDirection.TopToBottom to (Offset(10f, 20f) to Offset(10f, 220f)),
            AccentGradientDirection.BottomToTop to (Offset(10f, 220f) to Offset(10f, 20f)),
            AccentGradientDirection.LeftToRight to (Offset(10f, 20f) to Offset(110f, 20f)),
            AccentGradientDirection.RightToLeft to (Offset(110f, 20f) to Offset(10f, 20f)),
            AccentGradientDirection.TopRightToBottomLeft to (Offset(110f, 20f) to Offset(10f, 220f)),
            AccentGradientDirection.TopLeftToBottomRight to (Offset(10f, 20f) to Offset(110f, 220f)),
            AccentGradientDirection.BottomLeftToTopRight to (Offset(10f, 220f) to Offset(110f, 20f)),
            AccentGradientDirection.BottomRightToTopLeft to (Offset(110f, 220f) to Offset(10f, 20f)),
        )
        assertEquals(AccentGradientDirection.entries.toSet(), expected.keys)
        expected.forEach { (direction, ends) ->
            assertEquals(
                direction.name,
                Brush.linearGradient(listOf(blue, purple), start = ends.first, end = ends.second),
                accentBrush(blue, WearAccentGradient(purple, direction), bounds),
            )
        }
    }

    @Test
    fun `a snapshot's gradient is its end color and direction`() {
        assertEquals(
            WearAccentGradient(purple, AccentGradientDirection.LeftToRight),
            wearAccentGradient("#AF52DE", AccentGradientDirection.LeftToRight),
        )
    }

    @Test
    fun `an end with no direction runs top left to bottom right`() {
        assertEquals(
            WearAccentGradient(purple, AccentGradientDirection.TopLeftToBottomRight),
            wearAccentGradient("#AF52DE", null),
        )
    }

    @Test
    fun `no end, or an end that is no color, is no gradient`() {
        assertNull(wearAccentGradient(null, AccentGradientDirection.LeftToRight))
        assertNull(wearAccentGradient("#AF52", AccentGradientDirection.LeftToRight))
        assertNull(wearAccentGradient("not a color", AccentGradientDirection.LeftToRight))
        assertNull(wearAccentGradient("", AccentGradientDirection.LeftToRight))
    }
}
