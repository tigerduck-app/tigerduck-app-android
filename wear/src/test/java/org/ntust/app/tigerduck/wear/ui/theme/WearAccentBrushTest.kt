package org.ntust.app.tigerduck.wear.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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

/** The brush a watch mark of the accent paints with: the color alone, or the gradient across the mark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearAccentBrushTest {

    private val blue = Color(0xFF007AFF)

    private fun gradient(style: AccentGradientStyle, direction: AccentGradientDirection = AccentGradientDirection.LeftToRight) =
        requireNotNull(AccentGradient.of(listOf(0xFF0000, 0x00FF00, 0x0000FF), style, direction))

    /** [gradient]'s brush laid across [bounds] in a 120 by 240 canvas. */
    private fun draw(gradient: AccentGradient?, bounds: Rect): Bitmap {
        val image = ImageBitmap(120, 240)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(120f, 240f)) {
            drawRect(accentBrush(blue, gradient, bounds))
        }
        return image.asAndroidBitmap()
    }

    private fun Bitmap.channel(x: Int, y: Int, shift: Int) = (getPixel(x, y) shr shift) and 0xFF

    @Test
    fun `a single color is the color alone`() {
        val bounds = Rect(Offset(10f, 20f), Size(100f, 200f))
        assertEquals(SolidColor(blue), accentBrush(blue, null, bounds))
        assertEquals(SolidColor(blue), accentBrush(blue, AccentGradient.color(0x007AFF), bounds))
    }

    @Test
    fun `a linear gradient runs through each stop across the mark's own bounds`() {
        val bitmap = draw(gradient(AccentGradientStyle.Linear), Rect(Offset(10f, 20f), Size(100f, 200f)))

        assertTrue(bitmap.channel(11, 100, 16) > 200) // red at the mark's left
        assertTrue(bitmap.channel(60, 100, 8) > 200) // green half way
        assertTrue(bitmap.channel(108, 100, 0) > 200) // blue at its right
    }

    @Test
    fun `an angular gradient goes clockwise from the top of the mark`() {
        val bitmap = draw(gradient(AccentGradientStyle.Angular), Rect(Offset(0f, 0f), Size(120f, 240f)))

        assertTrue(bitmap.channel(62, 0, 16) > 200) // red just right of the top
        // Half way round at the bottom: between green, a third of the way, and blue, two thirds.
        assertTrue(bitmap.channel(60, 239, 8) in 100..160 && bitmap.channel(60, 239, 0) in 100..160)
        assertTrue(bitmap.channel(60, 239, 16) < 30)
    }

    @Test
    fun `two brushes of the same gradient over the same bounds are equal`() {
        val bounds = Rect(Offset(10f, 20f), Size(100f, 200f))
        assertEquals(accentBrush(blue, gradient(AccentGradientStyle.Radial), bounds), accentBrush(blue, gradient(AccentGradientStyle.Radial), bounds))
    }
}
