package org.ntust.app.tigerduck.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.AccentGradientDirection

/** The accent gradient's eight directions, as stored and as drawn. */
class AccentBrushTest {

    private val size = Size(100f, 50f)

    @Test
    fun `each direction is stored as a code of its own`() {
        val codes = AccentGradientDirection.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `each direction reads back from its code`() {
        AccentGradientDirection.entries.forEach {
            assertEquals(it, AccentGradientDirection.fromCode(it.code))
        }
    }

    @Test
    fun `a code this build does not know reads as top left to bottom right`() {
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, AccentGradientDirection.fromCode(99))
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, AccentGradientDirection.fromCode(-1))
    }

    // Row by row in the page's grid of four: each direction above its reverse.
    @Test
    fun `the page offers the directions in two rows of four, each above its reverse`() {
        assertEquals(
            listOf(
                AccentGradientDirection.TopToBottom,
                AccentGradientDirection.LeftToRight,
                AccentGradientDirection.BottomLeftToTopRight,
                AccentGradientDirection.TopLeftToBottomRight,
                AccentGradientDirection.BottomToTop,
                AccentGradientDirection.RightToLeft,
                AccentGradientDirection.TopRightToBottomLeft,
                AccentGradientDirection.BottomRightToTopLeft,
            ),
            AccentGradientDirection.entries,
        )
    }

    @Test
    fun `each direction runs from its start side of the shape to the opposite one`() {
        val expected = mapOf(
            AccentGradientDirection.TopToBottom to (Offset(0f, 0f) to Offset(0f, 50f)),
            AccentGradientDirection.BottomToTop to (Offset(0f, 50f) to Offset(0f, 0f)),
            AccentGradientDirection.LeftToRight to (Offset(0f, 0f) to Offset(100f, 0f)),
            AccentGradientDirection.RightToLeft to (Offset(100f, 0f) to Offset(0f, 0f)),
            AccentGradientDirection.TopRightToBottomLeft to (Offset(100f, 0f) to Offset(0f, 50f)),
            AccentGradientDirection.TopLeftToBottomRight to (Offset(0f, 0f) to Offset(100f, 50f)),
            AccentGradientDirection.BottomLeftToTopRight to (Offset(0f, 50f) to Offset(100f, 0f)),
            AccentGradientDirection.BottomRightToTopLeft to (Offset(100f, 50f) to Offset(0f, 0f)),
        )
        assertEquals(AccentGradientDirection.entries.toSet(), expected.keys)
        expected.forEach { (direction, points) ->
            assertEquals(direction.name, points, direction.endpoints(size))
        }
    }

    // The shader runs the gradient in sRGB: sampled any other way, its middle
    // looks lighter than it draws, and dark text would be picked to vanish.
    @Test
    fun `text on a gradient is picked against its middle as the shader draws it`() {
        assertEquals(Color.White, readableOnGradient(Color(0xFFBC00FB), Color(0xFF069DBB), onStart = Color.White))
    }

    @Test
    fun `text on a gradient that darkens toward its end turns white`() {
        assertEquals(Color.White, readableOnGradient(Color(0xFF808080), Color.Black, onStart = Color(0xFF1C1C1E)))
    }

    @Test
    fun `two gradients of the same colors and direction are equal, and another direction is not`() {
        val a = AccentGradientBrush(Color.Red, Color.Blue, AccentGradientDirection.TopToBottom)
        assertEquals(a, AccentGradientBrush(Color.Red, Color.Blue, AccentGradientDirection.TopToBottom))
        assertEquals(a.hashCode(), AccentGradientBrush(Color.Red, Color.Blue, AccentGradientDirection.TopToBottom).hashCode())
        assertNotEquals(a, AccentGradientBrush(Color.Red, Color.Blue, AccentGradientDirection.BottomToTop))
    }
}
