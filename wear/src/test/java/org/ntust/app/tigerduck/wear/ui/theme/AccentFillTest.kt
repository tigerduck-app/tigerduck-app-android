package org.ntust.app.tigerduck.wear.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The accent as the watch's padding buttons and bars fill with it, gradient included. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentFillTest {

    private val orange = Color(0xFFFF6B35)
    private val purple = Color(0xFF7B2FF7)

    private fun gradient(vararg stops: Int, style: AccentGradientStyle = AccentGradientStyle.Linear) =
        requireNotNull(AccentGradient.of(stops.toList(), style, AccentGradientDirection.LeftToRight))

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun draw(fill: AccentFill, width: Int = 200, height: Int = 20): Bitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawRect(fill.brush)
        }
        return image.asAndroidBitmap()
    }

    @Test
    fun `a single color fills as it always has`() {
        val fill = accentFill(orange, gradient = null)
        assertEquals(SolidColor(readableOnBlack(orange)), fill.brush)
        assertEquals(textOn(readableOnBlack(orange)), fill.text)
    }

    @Test
    fun `a gradient runs from its start to its end across the fill`() {
        val fill = draw(accentFill(orange, gradient(0xFF6B35, 0x7B2FF7)))
        val left = Color(fill.getPixel(2, 10))
        val right = Color(fill.getPixel(197, 10))
        assertTrue("starts orange: $left", left.red > left.blue)
        assertTrue("ends purple: $right", right.blue > right.red && right.blue > right.green)
    }

    @Test
    fun `every part of a gradient reads on black, not only its ends`() {
        // Navy to dark red: neither end, nor anything between, reads on black as it is.
        val fill = draw(accentFill(Color(0xFF1A237E), gradient(0x1A237E, 0x8B0000)))
        for (x in 2 until 198 step 7) {
            assertTrue("($x)", contrast(Color(fill.getPixel(x, 10)), Color.Black) >= 4.4f)
        }
    }

    @Test
    fun `the text reads on both ends of the gradient`() {
        val fill = accentFill(orange, gradient(0xFF6B35, 0x7B2FF7))
        val ends = listOf(readableOnBlack(orange), readableOnBlack(purple))
        val worst = ends.minOf { contrast(fill.text, it) }
        val other = if (fill.text == Color.Black) Color.White else Color.Black
        assertTrue(worst >= ends.minOf { contrast(other, it) })
        assertTrue(worst >= 4.5f)
    }

    @Test
    fun `every stop of a longer gradient, and what lies between, reads on black`() {
        // Navy, dark red and dark green, none of which reads on black as it is.
        val fill = draw(accentFill(Color(0xFF1A237E), gradient(0x1A237E, 0x8B0000, 0x004D00, 0x1A237E, 0x3A0060)))
        for (x in 2 until 198 step 5) {
            assertTrue("($x)", contrast(Color(fill.getPixel(x, 10)), Color.Black) >= 4.4f)
        }
    }

    @Test
    fun `a radial or angular gradient reads on black too, in its own style`() {
        for (style in listOf(AccentGradientStyle.Radial, AccentGradientStyle.Angular)) {
            val fill = draw(accentFill(Color(0xFF1A237E), gradient(0x1A237E, 0x8B0000, style = style)), width = 60, height = 60)
            for (x in 2 until 58 step 6) for (y in 2 until 58 step 6) {
                assertTrue("$style ($x, $y)", contrast(Color(fill.getPixel(x, y)), Color.Black) >= 4.4f)
            }
        }
    }
}
