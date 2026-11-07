// Draws the accent's brush in each of its four styles and reads the pixels
// back, against where the iPhone puts each color.

package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.shared.AccentShader
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentStylesDrawingTest {

    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val yellow = 0xFFFF00
    private val white = 0xFFFFFF

    private fun gradient(
        vararg stops: Int,
        style: AccentGradientStyle,
        direction: AccentGradientDirection = AccentGradientDirection.LeftToRight,
    ) = requireNotNull(AccentGradient.of(stops.toList(), style, direction))

    /** [accent]'s brush drawn over a [width] by [height] bitmap. */
    private fun draw(accent: AccentGradient, width: Int = 101, height: Int = 101): Bitmap {
        val brush = accent.brush { it } as ShaderBrush
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = brush.createShader(Size(width.toFloat(), height.toFloat())) }
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun Bitmap.near(x: Int, y: Int, rgb: Int, tolerance: Int = 24): Boolean {
        val pixel = getPixel(x, y)
        return listOf(16, 8, 0).all { abs(((pixel shr it) and 0xFF) - ((rgb shr it) and 0xFF)) <= tolerance }
    }

    private fun Bitmap.assertNear(x: Int, y: Int, rgb: Int, tolerance: Int = 24) {
        val pixel = Integer.toHexString(getPixel(x, y))
        assertTrue("($x, $y) is $pixel, not near ${Integer.toHexString(rgb)}", near(x, y, rgb, tolerance))
    }

    @Test
    fun `a linear gradient runs through every stop along its direction`() {
        val bitmap = draw(gradient(red, green, blue, style = AccentGradientStyle.Linear))

        bitmap.assertNear(0, 50, red)
        bitmap.assertNear(50, 50, green)
        bitmap.assertNear(100, 50, blue)
        // Along its direction only: the same color top to bottom.
        bitmap.assertNear(50, 0, green)
        bitmap.assertNear(50, 100, green)
    }

    @Test
    fun `a radial gradient starts in the middle and reaches its last stop at each side`() {
        val bitmap = draw(gradient(red, blue, style = AccentGradientStyle.Radial), width = 201, height = 101)

        bitmap.assertNear(100, 50, red)
        bitmap.assertNear(200, 50, blue, tolerance = 40)
        bitmap.assertNear(0, 50, blue, tolerance = 40)
        // An ellipse touching every side, not a circle as wide as the box.
        bitmap.assertNear(100, 100, blue, tolerance = 40)
        bitmap.assertNear(100, 0, blue, tolerance = 40)
    }

    @Test
    fun `an angular gradient goes clockwise from the top, round to its first stop again`() {
        val bitmap = draw(gradient(red, green, blue, yellow, style = AccentGradientStyle.Angular))

        bitmap.assertNear(52, 0, red, tolerance = 40)
        bitmap.assertNear(100, 50, green)
        bitmap.assertNear(50, 100, blue)
        bitmap.assertNear(0, 50, yellow)
        bitmap.assertNear(48, 0, red, tolerance = 40)
    }

    // A bitmap is drawn in software, which takes no runtime shader: its mesh is worked out pixel by pixel.
    @Test
    fun `a mesh drawn into a bitmap puts its stops on their spots and blends between them`() {
        val accent = gradient(red, green, blue, 0x000000, white, style = AccentGradientStyle.Mesh)
        val bitmap = AccentShader.bitmap(accent, 101, 101)

        bitmap.assertNear(0, 0, red)
        bitmap.assertNear(100, 0, green)
        bitmap.assertNear(100, 100, blue)
        bitmap.assertNear(0, 100, 0x000000)
        bitmap.assertNear(50, 50, white)
        bitmap.assertNear(50, 0, accent.meshColors[1], tolerance = 2)
    }

    // A screen draws in hardware, which Robolectric does not: this checks the shader compiles and
    // takes its colors, and AccentColorsTest the colors it works out, which meshAt shares.
    @Test
    fun `on a screen from Android 13 a mesh is an AGSL shader through its nine colors`() {
        val accent = gradient(red, green, blue, 0x000000, white, style = AccentGradientStyle.Mesh)

        assertTrue(AccentShader.create(accent, 0f, 0f, 100f, 100f) is RuntimeShader)
    }

    @Test
    fun `a linear gradient drawn into a bitmap is the one drawn on a screen`() {
        val accent = gradient(red, green, blue, style = AccentGradientStyle.Linear)
        val bitmap = AccentShader.bitmap(accent, 101, 101)

        bitmap.assertNear(0, 50, red)
        bitmap.assertNear(50, 50, green)
        bitmap.assertNear(100, 50, blue)
    }

    // Android 12 has no runtime shaders: the iPhone's own stand-in for a mesh.
    @Config(sdk = [31])
    @Test
    fun `below Android 13 a mesh is a linear gradient of its stops from the top left`() {
        val bitmap = draw(gradient(red, green, blue, 0x000000, white, style = AccentGradientStyle.Mesh))

        bitmap.assertNear(0, 0, red)
        bitmap.assertNear(100, 100, white)
        bitmap.assertNear(50, 50, blue)
    }
}
