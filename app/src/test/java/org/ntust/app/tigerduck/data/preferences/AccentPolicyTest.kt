package org.ntust.app.tigerduck.data.preferences

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Covers [AccentPolicy], the rule for which accent each surface draws: the
 * wallpaper's colors on the app's own screens from Android 12, and the fixed
 * accent, the swatch last picked, everywhere else.
 */
class AccentPolicyTest {

    private val pink = 0xFF2D55
    private val blue = 0x007AFF
    private val purple = 0xAF52DE

    // Colors of the user's own: none is a preset.
    private val navy = 0x1A237E
    private val mint = 0x98FFC8
    private val yellow = 0xFFEB3B

    /** The dark theme's cards, which the accent is drawn on. */
    private val darkCard = Color(0xFF3A3A3C)

    private fun color(hex: Int) = Color(0xFF000000L or hex.toLong())

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun `a stored swatch reads as itself`() {
        assertEquals(pink, AccentPolicy.fixedLightHex(pink))
        assertEquals(0x007AFF, AccentPolicy.fixedLightHex(0x007AFF))
    }

    @Test
    fun `a stored -1, where a build of the wallpaper accent kept it, reads as Blue and not white`() {
        assertEquals(0x007AFF, AccentPolicy.fixedLightHex(-1))
        assertEquals(Color(0xFF007AFF), AccentPolicy.fixedColor(AccentPolicy.fixedLightHex(-1), isDark = false))
    }

    @Test
    fun `the fixed accent is drawn as its dark pair in dark mode`() {
        assertEquals(Color(0xFFFF2D55), AccentPolicy.fixedColor(pink, isDark = false))
        assertEquals(Color(0xFFFF375F), AccentPolicy.fixedColor(pink, isDark = true))
    }

    @Test
    fun `the app follows the wallpaper only where it is picked, from Android 12`() {
        assertTrue(AccentPolicy.appFollowsWallpaper(useWallpaperColors = true, sdkInt = 31))
        assertTrue(AccentPolicy.appFollowsWallpaper(useWallpaperColors = true, sdkInt = 36))
        assertFalse(AccentPolicy.appFollowsWallpaper(useWallpaperColors = true, sdkInt = 30))
        assertFalse(AccentPolicy.appFollowsWallpaper(useWallpaperColors = false, sdkInt = 31))
    }

    @Test
    fun `a gradient counts while the accent is still the start it was picked with`() {
        assertEquals(purple, AccentPolicy.gradientEnd(blue, storedStart = blue, storedEnd = purple))
    }

    @Test
    fun `no stored end is a single color`() {
        assertNull(AccentPolicy.gradientEnd(blue, storedStart = blue, storedEnd = AccentPolicy.NO_GRADIENT))
    }

    // An older build knows only the accent: a color picked there replaces the
    // start, and the gradient it knows nothing of must not come back with it.
    @Test
    fun `a color an older build picked over the gradient leaves a single color`() {
        assertNull(AccentPolicy.gradientEnd(pink, storedStart = blue, storedEnd = purple))
    }

    @Test
    fun `a stored end that is no color is no gradient`() {
        assertNull(AccentPolicy.gradientEnd(blue, storedStart = blue, storedEnd = 0x1000000))
    }

    @Test
    fun `every ready-made gradient runs between two different presets`() {
        assertTrue(AccentPolicy.presetGradients.isNotEmpty())
        AccentPolicy.presetGradients.forEach { (start, end) ->
            assertTrue(AccentPolicy.isPreset(start))
            assertTrue(AccentPolicy.isPreset(end))
            assertNotEquals(start, end)
        }
    }

    @Test
    fun `a preset keeps its own dark pair`() {
        assertEquals(0xFF375F, AccentPolicy.darkHex(pink))
    }

    @Test
    fun `a dark color of the user's own is lightened in dark mode until it stands out on the cards`() {
        val dark = AccentPolicy.darkHex(navy)

        assertNotEquals(navy, dark)
        assertTrue(contrast(color(dark), darkCard) >= 3f)
        assertEquals(color(dark), AccentPolicy.fixedColor(navy, isDark = true))
    }

    @Test
    fun `a light color of the user's own keeps its tone in dark mode`() {
        assertEquals(mint, AccentPolicy.darkHex(mint))
    }

    @Test
    fun `the presets keep the theme's own text color`() {
        assertNull(AccentPolicy.onAccent(blue, isDark = false))
        assertNull(AccentPolicy.onAccent(0x5AC8FA, isDark = true))
    }

    @Test
    fun `text on a light color of the user's own is dark, and on a dark one white`() {
        assertTrue(AccentPolicy.onAccent(yellow, isDark = false)!!.luminance() < 0.1f)
        assertEquals(Color.White, AccentPolicy.onAccent(navy, isDark = false))
    }

    // Dark mode lightens navy, and white no longer reads best on what it draws.
    @Test
    fun `text on a color of the user's own reads on the tone each mode draws`() {
        val onDark = AccentPolicy.onAccent(navy, isDark = true)!!
        val white = contrast(Color.White, AccentPolicy.fixedColor(navy, isDark = true))
        assertNotEquals(Color.White, onDark)
        assertTrue(contrast(onDark, AccentPolicy.fixedColor(navy, isDark = true)) > white)
    }

    /** The light theme's cards, which an accent in light mode is drawn on. */
    private val lightCard = Color(0xFFF2F2F7)

    // White, picked as one's own, would vanish into the light cards, and so
    // would every link, switch and widget highlight drawn in it.
    @Test
    fun `a light color of the user's own is darkened in light mode until it stands out on the cards`() {
        val shown = AccentPolicy.fixedColor(0xFFFFFF, isDark = false)
        assertTrue(contrast(shown, lightCard) >= 3f)
        assertTrue(contrast(AccentPolicy.fixedColor(yellow, isDark = false), lightCard) >= 3f)
    }

    @Test
    fun `a dark color of the user's own keeps its tone in light mode`() {
        assertEquals(color(navy), AccentPolicy.fixedColor(navy, isDark = false))
    }

    // Orange, Green and Teal read faintly on the light cards as they are, so
    // light mode darkens them as it does a light color of the user's own.
    @Test
    fun `every preset stands out on the light cards in light mode and keeps its dark pair in dark mode`() {
        val pale = listOf(0xFF9500, 0x34C759, 0x5AC8FA)
        AppPreferences.themeColors.forEach { (name, hex) ->
            val light = AccentPolicy.fixedColor(hex, isDark = false)
            assertTrue("$name in light mode", contrast(light, lightCard) >= 3f)
            assertEquals("$name in light mode", hex !in pale, light == color(hex))
            assertEquals("$name in dark mode", color(AppPreferences.accentDarkVariant(hex)), AccentPolicy.fixedColor(hex, isDark = true))
        }
    }
}
