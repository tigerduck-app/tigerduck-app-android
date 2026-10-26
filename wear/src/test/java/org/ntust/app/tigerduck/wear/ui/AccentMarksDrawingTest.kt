package org.ntust.app.tigerduck.wear.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.wear.ui.theme.WearAccentGradient
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The watch's marks, drawn for real: the library QR's countdown ring in the
 * accent, which runs from its start to its end across the ring when the phone
 * picked a gradient; the screen padding ring and the QR padding corners in one
 * neutral color.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentMarksDrawingTest {

    private val red = Color(0xFFFF0000)
    private val blue = Color(0xFF0000FF)

    private fun render(width: Int, height: Int, draw: DrawScope.() -> Unit): Bitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), draw,
        )
        return image.asAndroidBitmap()
    }

    /** How far the pixel at [x], [y] is from red toward blue: 0 red, 1 blue. */
    private fun Bitmap.towardBlue(x: Int, y: Int): Float {
        val pixel = Color(getPixel(x, y))
        assertTrue("($x, $y) is drawn", pixel.alpha > 0.1f)
        return pixel.blue / (pixel.red + pixel.blue)
    }

    private fun Bitmap.alpha(x: Int, y: Int): Float = Color(getPixel(x, y)).alpha

    @Test
    fun `the countdown ring runs from the start on its left to the end on its right`() {
        val ring = render(100, 100) {
            drawCountdownRing(1f, red, WearAccentGradient(blue, AccentGradientDirection.LeftToRight), strokePx = 10f)
        }
        assertTrue(ring.towardBlue(2, 50) < 0.15f)
        assertTrue(ring.towardBlue(97, 50) > 0.85f)
    }

    @Test
    fun `with a single color the countdown ring is that color all round`() {
        val ring = render(100, 100) { drawCountdownRing(1f, red, null, strokePx = 10f) }
        assertEquals(0f, ring.towardBlue(2, 50), 0.01f)
        assertEquals(0f, ring.towardBlue(97, 50), 0.01f)
    }

    // A quarter left: the arc from the top round to the right, over a faint
    // track of the gradient the rest of the way.
    @Test
    fun `a quarter of the countdown is drawn solid over a faint track`() {
        val ring = render(100, 100) {
            drawCountdownRing(0.25f, red, WearAccentGradient(blue, AccentGradientDirection.LeftToRight), strokePx = 10f)
        }
        // Up and to the right, on the arc.
        assertEquals(1f, ring.alpha(84, 16), 0.05f)
        assertTrue(ring.towardBlue(84, 16) > 0.7f)
        // Left and bottom, on the track alone.
        assertEquals(0.18f, ring.alpha(2, 50), 0.05f)
        assertTrue(ring.towardBlue(2, 50) < 0.15f)
        assertEquals(0.18f, ring.alpha(50, 97), 0.05f)
    }

    // The padding previews are in one neutral color: the accent is the bar's alone (Sam's call).
    private val gray = Color(0xFF808080)

    private fun Bitmap.isGray(x: Int, y: Int): Boolean {
        val pixel = Color(getPixel(x, y))
        return pixel.alpha > 0.1f && kotlin.math.abs(pixel.red - pixel.blue) < 0.05f && kotlin.math.abs(pixel.red - pixel.green) < 0.05f
    }

    @Test
    fun `the padding ring is one color all round, at the padding's inset`() {
        val ring = render(300, 200) { drawPaddingEdgeRing(paddingPx = 20f, strokePx = 4f, color = gray) }
        // A ring of radius 80 round the middle, 150, 100.
        assertTrue(ring.isGray(70, 100))
        assertTrue(ring.isGray(230, 100))
        assertEquals(0.6f, ring.alpha(70, 100), 0.05f)
    }

    @Test
    fun `the QR corners are one color at each corner of the square`() {
        val corners = render(300, 200) { drawQrCornerBrackets(sidePx = 100f, legPx = 20f, strokePx = 4f, color = gray) }
        // The square spans 100 to 200 across and 50 to 150 down; each corner's top or bottom leg.
        assertTrue(corners.isGray(104, 50))
        assertTrue(corners.isGray(196, 150))
        assertEquals(0.85f, corners.alpha(104, 50), 0.05f)
    }
}
