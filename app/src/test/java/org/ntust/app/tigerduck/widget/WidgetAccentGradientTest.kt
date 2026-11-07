package org.ntust.app.tigerduck.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.ui.AccentSetting
import org.ntust.app.tigerduck.widget.content.WidgetAccentMark
import org.ntust.app.tigerduck.widget.content.accentBitmap
import org.ntust.app.tigerduck.widget.content.accentImage
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The widgets' accent with a gradient picked: its first stop until Settings' widgets switch is on,
 * then the gradient in its style, drawn into a bitmap for each mark, as Glance has no brush.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetAccentGradientTest {

    private lateinit var prefs: AppPreferences

    private val blue = 0x007AFF
    private val purple = 0xAF52DE

    private fun gradient(vararg stops: Int, style: AccentGradientStyle = AccentGradientStyle.Linear) =
        requireNotNull(AccentGradient.of(stops.toList(), style, AccentGradientDirection.LeftToRight))

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    @Test
    fun `with the widgets switch off a gradient draws its first stop alone`() {
        AccentSetting(prefs).pickAccent(gradient(blue, purple))

        val accent = resolveWidgetAccent(prefs, isDark = false)
        assertNull(accent.gradient)
        assertEquals(AccentPolicy.fixedColor(blue, isDark = false), accent.color)
    }

    @Test
    fun `with the switch on the widgets draw every stop, as the theme's mode draws it`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(blue, 0xFFFFFF, style = AccentGradientStyle.Radial))
            gradientOnWidgets = true
        }

        listOf(false, true).forEach { isDark ->
            val accent = resolveWidgetAccent(prefs, isDark)
            val drawn = listOf(blue, 0xFFFFFF).map { AccentPolicy.fixedColor(it, isDark).toArgb() and 0xFFFFFF }
            assertEquals(drawn, accent.gradient?.stops)
            assertEquals(AccentGradientStyle.Radial, accent.gradient?.style)
            assertEquals(AccentPolicy.fixedColor(blue, isDark), accent.color)
        }
    }

    @Test
    fun `a single color draws alone, the switch on or not`() {
        AccentSetting(prefs).apply {
            pick(blue)
            gradientOnWidgets = true
        }
        assertNull(resolveWidgetAccent(prefs, isDark = false).gradient)
    }

    // Mint into black: dark text reads on the mint, but half way, under an "ongoing" badge's
    // label, it all but vanishes.
    @Test
    fun `text on a gradient fill reads on its middle`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(0x98FFC8, 0x000000))
            gradientOnWidgets = true
        }
        assertEquals(Color(0xFF1C1C1E), resolveWidgetOnAccentColor(prefs, isDark = false))
        assertEquals(Color.White, resolveWidgetAccent(prefs, isDark = false).onColor)
    }

    @Test
    fun `a mark gets an image of the gradient only while there is one`() {
        assertNull(accentImage(null, WidgetAccentMark.Badge))
        assertNotNull(accentImage(gradient(blue, purple), WidgetAccentMark.Badge))
    }

    private fun Bitmap.near(x: Int, y: Int, rgb: Int): Boolean {
        val pixel = getPixel(x, y)
        return listOf(16, 8, 0).all { abs(((pixel shr it) and 0xFF) - ((rgb shr it) and 0xFF)) <= 24 }
    }

    @Test
    fun `each mark's image is the gradient in its style, at the mark's own proportions`() {
        val linear = accentBitmap(gradient(0xFF0000, 0x0000FF), WidgetAccentMark.Bar)
        assertTrue(linear.width > linear.height * 10)
        assertTrue(linear.near(0, 0, 0xFF0000))
        assertTrue(linear.near(linear.width - 1, 0, 0x0000FF))

        // A mesh, which a bitmap draws pixel by pixel on every Android: each corner its stop.
        val mesh = accentBitmap(gradient(0xFF0000, 0x00FF00, 0x0000FF, 0x000000, style = AccentGradientStyle.Mesh), WidgetAccentMark.Tile)
        assertEquals(mesh.width, mesh.height)
        assertTrue(mesh.near(0, 0, 0xFF0000))
        assertTrue(mesh.near(mesh.width - 1, 0, 0x00FF00))
        assertTrue(mesh.near(mesh.width - 1, mesh.height - 1, 0x0000FF))
    }

    // A widget redraws on every tick: the same image each time, which the widget host is sent once.
    @Test
    fun `the same gradient and mark give the same image`() {
        assertSame(accentBitmap(gradient(blue, purple), WidgetAccentMark.Badge), accentBitmap(gradient(blue, purple), WidgetAccentMark.Badge))
    }
}
