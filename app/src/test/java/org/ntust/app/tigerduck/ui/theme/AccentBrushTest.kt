package org.ntust.app.tigerduck.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle

/** The accent as a brush: one color as itself, a gradient as every stop in its style, and the text on it. */
class AccentBrushTest {

    private fun gradient(
        vararg stops: Int,
        style: AccentGradientStyle = AccentGradientStyle.Linear,
        direction: AccentGradientDirection = AccentGradientDirection.LeftToRight,
    ) = requireNotNull(AccentGradient.of(stops.toList(), style, direction))

    // Row by row in the editor's grid of four: each direction above its reverse.
    @Test
    fun `the editor offers the directions in two rows of four, each above its reverse`() {
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
    fun `one color is a plain color, as its tone draws it`() {
        assertEquals(SolidColor(Color(0xFFFF2D54)), AccentGradient.color(0xFF2D55).brush { it xor 1 })
    }

    @Test
    fun `a gradient is drawn in its tones, and equals another of the same tones and style only`() {
        val brush = gradient(0x111111, 0x222222).brush { it + 1 }
        assertEquals(AccentGradientBrush(gradient(0x111112, 0x222223)), brush)
        assertEquals(AccentGradientBrush(gradient(0x111112, 0x222223)).hashCode(), brush.hashCode())
        assertNotEquals(AccentGradientBrush(gradient(0x111112, 0x222223, style = AccentGradientStyle.Radial)), brush)
        assertNotEquals(
            AccentGradientBrush(gradient(0x111112, 0x222223, direction = AccentGradientDirection.BottomToTop)),
            brush,
        )
    }

    // The shader runs the gradient in sRGB: sampled any other way, its middle
    // looks lighter than it draws, and dark text would be picked to vanish.
    @Test
    fun `text on a gradient is picked against its middle as the shader draws it`() {
        assertEquals(Color.White, readableOnGradient(gradient(0xBC00FB, 0x069DBB), onFirst = Color.White))
    }

    @Test
    fun `text on a gradient that darkens toward its end turns white`() {
        assertEquals(Color.White, readableOnGradient(gradient(0x808080, 0x000000), onFirst = Color(0xFF1C1C1E)))
    }

    @Test
    fun `text on a radial gradient reads on its middle, its first color`() {
        val radial = gradient(0xFFFFFF, 0x000000, style = AccentGradientStyle.Radial)

        assertEquals(Color(0xFF1C1C1E), readableOnGradient(radial, onFirst = Color.White))
    }

    @Test
    fun `text on a mesh reads on the color at its middle`() {
        val mesh = gradient(0x000000, 0x000000, 0x000000, 0x000000, 0xFFFFFF, style = AccentGradientStyle.Mesh)

        assertEquals(Color(0xFF1C1C1E), readableOnGradient(mesh, onFirst = Color.White, meshDrawn = true))
        // Below Android 13 the stand-in runs from black to white, whose middle is grey, where white reads better.
        assertEquals(Color.White, readableOnGradient(mesh, onFirst = Color.White, meshDrawn = false))
    }
}
