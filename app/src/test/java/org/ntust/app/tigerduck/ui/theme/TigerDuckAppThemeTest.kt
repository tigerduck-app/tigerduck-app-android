package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's root theme is where every screen gets the interface style from. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class TigerDuckAppThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
    fun `the root theme uses accentColor when useDynamicColor is false`() {
        var seenScheme: ColorScheme? = null
        val customAccent = Color(0xFFE67E22)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, accentColor = customAccent, useDynamicColor = false) {
                seenScheme = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        assertEquals(customAccent, seenScheme?.primary)
    }

    @Config(sdk = [31])
    @Test
    fun `the root theme uses dynamic color scheme on S+ when useDynamicColor is true while preserving surface`() {
        var seenScheme: ColorScheme? = null
        val customAccent = Color(0xFFE67E22)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, accentColor = customAccent, useDynamicColor = true) {
                seenScheme = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        // Primary is resolved dynamically rather than fixed customAccent
        assertNotEquals(customAccent, seenScheme?.primary)
        // Card surface and background remain TigerDuck's explicit palette
        assertEquals(LightColorScheme.surface, seenScheme?.surface)
        assertEquals(LightColorScheme.background, seenScheme?.background)
        // onPrimary remains TigerDuck's explicit white text on primary button
        assertEquals(Color.White, seenScheme?.onPrimary)
    }
}
