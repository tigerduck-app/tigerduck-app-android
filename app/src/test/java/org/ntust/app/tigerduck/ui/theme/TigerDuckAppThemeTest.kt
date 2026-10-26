package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

/**
 * The app's root theme is where every screen gets the interface style and
 * the accent from: a swatch's color, or from Android 12 the wallpaper's.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class TigerDuckAppThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val orange = Color(0xFFE67E22)

    @After
    fun resetTheme() {
        TigerDuckTheme.setDarkMode(false)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    private fun scheme(darkTheme: Boolean, useDynamicColor: Boolean): ColorScheme {
        var seen: ColorScheme? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = darkTheme, accentColor = orange, useDynamicColor = useDynamicColor) {
                seen = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        return seen!!
    }

    @Test
    fun `the root theme hands the picked style to the screens inside it`() {
        var seen: VisualStylePolicy? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, visualPreset = VisualPreset.Tat) {
                seen = LocalVisualStylePolicy.current
            }
        }
        composeRule.waitForIdle()
        assertEquals(VisualPreset.Tat, seen?.preset)
    }

    @Test
    fun `the root theme hands TigerDuck when no style is given`() {
        var seen: VisualStylePolicy? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = true) { seen = LocalVisualStylePolicy.current }
        }
        composeRule.waitForIdle()
        assertEquals(VisualPreset.TigerDuck, seen?.preset)
    }

    @Test
    fun `the wallpaper's colors leave the picked style in place`() {
        var seen: VisualStylePolicy? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = true, useDynamicColor = true, visualPreset = VisualPreset.Tat) {
                seen = LocalVisualStylePolicy.current
            }
        }
        composeRule.waitForIdle()
        assertEquals(VisualPreset.Tat, seen?.preset)
    }

    @Test
    fun `the root theme uses accentColor when useDynamicColor is false`() {
        assertEquals(orange, scheme(darkTheme = false, useDynamicColor = false).primary)
    }

    @Test
    fun `the wallpaper's light colors take the accent's roles and leave TigerDuck's surfaces`() {
        val seen = scheme(darkTheme = false, useDynamicColor = true)
        val wallpaper = dynamicLightColorScheme(composeRule.activity)
        assertNotEquals("the wallpaper's primary must differ from the swatch", orange, wallpaper.primary)

        assertEquals(wallpaper.primary, seen.primary)
        assertEquals(wallpaper.onPrimary, seen.onPrimary)
        assertEquals(wallpaper.primaryContainer, seen.primaryContainer)
        assertEquals(wallpaper.onPrimaryContainer, seen.onPrimaryContainer)
        assertEquals(wallpaper.surfaceTint, seen.surfaceTint)
        // Cards and the page keep TigerDuck's own palette.
        assertEquals(LightColorScheme.surface, seen.surface)
        assertEquals(LightColorScheme.background, seen.background)
    }

    @Test
    fun `in dark mode text on the wallpaper's primary takes its paired color, which reads`() {
        val seen = scheme(darkTheme = true, useDynamicColor = true)
        val wallpaper = dynamicDarkColorScheme(composeRule.activity)

        assertEquals(wallpaper.primary, seen.primary)
        assertEquals(wallpaper.onPrimary, seen.onPrimary)
        assertEquals(wallpaper.primaryContainer, seen.primaryContainer)
        assertEquals(wallpaper.onPrimaryContainer, seen.onPrimaryContainer)
        assertTrue(
            "onPrimary on primary reads at ${contrast(seen.onPrimary, seen.primary)}:1",
            contrast(seen.onPrimary, seen.primary) >= 4.5f,
        )
        assertEquals(DarkColorScheme.surface, seen.surface)
        assertEquals(DarkColorScheme.background, seen.background)
    }

    @Test
    fun `a dark theme inside a light one, as the import page's, draws the wallpaper's dark colors`() {
        var inner: ColorScheme? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, accentColor = orange, useDynamicColor = true) {
                // The call ClassTableImportPage makes for its camera page.
                TigerDuckAppTheme(darkTheme = true, accentColor = MaterialTheme.colorScheme.primary) {
                    inner = MaterialTheme.colorScheme
                }
            }
        }
        composeRule.waitForIdle()
        val wallpaper = dynamicDarkColorScheme(composeRule.activity)

        assertEquals(wallpaper.primary, inner?.primary)
        assertEquals(wallpaper.onPrimary, inner?.onPrimary)
        assertEquals(DarkColorScheme.surface, inner?.surface)
    }

    @Test
    fun `a dark theme inside a light one keeps the swatch it is given`() {
        var inner: ColorScheme? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, accentColor = orange) {
                TigerDuckAppTheme(darkTheme = true, accentColor = MaterialTheme.colorScheme.primary) {
                    inner = MaterialTheme.colorScheme
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(orange, inner?.primary)
    }

    @Test
    fun `a switch's thumb on the wallpaper's dark primary takes its paired color`() {
        TigerDuckTheme.setDarkMode(true)
        var colors: SwitchColors? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = true, useDynamicColor = true) { colors = tigerDuckSwitchColors() }
        }
        composeRule.waitForIdle()
        val wallpaper = dynamicDarkColorScheme(composeRule.activity)
        val switch = colors!!

        assertEquals(wallpaper.primary, switch.checkedTrackColor)
        assertEquals(wallpaper.onPrimary, switch.checkedThumbColor)
        assertTrue(
            "the thumb stands out from its track at ${contrast(switch.checkedThumbColor, switch.checkedTrackColor)}:1",
            contrast(switch.checkedThumbColor, switch.checkedTrackColor) >= 3f,
        )
    }

    @Test
    fun `a switch keeps its white thumb on a swatch's accent in either theme`() {
        var dark by mutableStateOf(false)
        var colors: SwitchColors? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = dark, accentColor = orange) { colors = tigerDuckSwitchColors() }
        }
        composeRule.waitForIdle()
        assertEquals(Color.White, colors?.checkedThumbColor)

        composeRule.runOnIdle { dark = true }
        composeRule.waitForIdle()
        assertEquals(Color.White, colors?.checkedThumbColor)
    }

    private val purple = Color(0xFFAF52DE)

    private fun brushAndScheme(
        accentGradient: AccentGradient? = null,
        onAccentColor: Color? = null,
        useDynamicColor: Boolean = false,
    ): Pair<Brush, ColorScheme> {
        var brush: Brush? = null
        var seen: ColorScheme? = null
        composeRule.setContent {
            TigerDuckAppTheme(
                darkTheme = false,
                accentColor = orange,
                onAccentColor = onAccentColor,
                accentGradient = accentGradient,
                useDynamicColor = useDynamicColor,
            ) {
                brush = TigerDuckTheme.accentBrush
                seen = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        return brush!! to seen!!
    }

    @Test
    fun `a single color fills with the accent color itself`() {
        val (brush, _) = brushAndScheme()
        assertEquals(SolidColor(orange), brush)
    }

    @Test
    fun `a gradient fills from the accent to its end, and the accent slots keep its start`() {
        val (brush, scheme) = brushAndScheme(
            accentGradient = AccentGradient(purple, AccentGradientDirection.BottomToTop),
        )
        assertEquals(AccentGradientBrush(orange, purple, AccentGradientDirection.BottomToTop), brush)
        assertEquals(orange, scheme.primary)
    }

    @Test
    fun `the wallpaper's colors fill with their primary, a gradient picked or not`() {
        val (brush, _) = brushAndScheme(
            accentGradient = AccentGradient(purple, AccentGradientDirection.BottomToTop),
            useDynamicColor = true,
        )
        assertEquals(SolidColor(dynamicLightColorScheme(composeRule.activity).primary), brush)
    }

    @Test
    fun `text on an accent of the user's own takes the color it is given`() {
        val (_, scheme) = brushAndScheme(onAccentColor = Color(0xFF1C1C1E))
        assertEquals(Color(0xFF1C1C1E), scheme.onPrimary)
    }

    @Test
    fun `text on a preset keeps the theme's white`() {
        val (_, scheme) = brushAndScheme()
        assertEquals(Color.White, scheme.onPrimary)
    }
}
