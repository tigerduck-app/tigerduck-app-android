package org.ntust.app.tigerduck.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers [AccentSetting], AppState's copy of the accent picked in Settings:
 * a swatch, or the wallpaper's colors, which keep the swatch last picked for
 * every surface that cannot follow them. [AppState] itself needs a real Hilt
 * graph, so its copy lives in this class.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AccentSettingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val blue = 0x007AFF
    private val pink = 0xFF2D55
    private val green = 0x34C759
    private val purple = 0xAF52DE
    private val orange = 0xFF9500

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    @Test
    fun `picking the wallpaper keeps the swatch last picked`() {
        val setting = AccentSetting(prefs)
        setting.pick(pink)
        setting.pickWallpaper()

        assertTrue(setting.followsWallpaper)
        assertEquals(pink, setting.lightHex)
        assertTrue(prefs.useWallpaperColors)
        assertEquals(pink, prefs.accentColorHex)
    }

    @Test
    fun `picking a swatch turns the wallpaper off and stores the swatch`() {
        val setting = AccentSetting(prefs)
        setting.pickWallpaper()
        setting.pick(green)

        assertFalse(setting.followsWallpaper)
        assertEquals(green, setting.lightHex)
        assertFalse(prefs.useWallpaperColors)
        assertEquals(green, prefs.accentColorHex)
    }

    @Test
    fun `picking again the swatch the wallpaper kept turns the wallpaper off`() {
        val setting = AccentSetting(prefs)
        setting.pick(pink)
        setting.pickWallpaper()
        setting.pick(pink)

        assertFalse(setting.followsWallpaper)
        assertFalse(prefs.useWallpaperColors)
        assertEquals(pink, prefs.accentColorHex)
    }

    @Test
    fun `only a pick that changes the fixed accent is announced, as the widgets draw only that`() {
        var changes = 0
        val setting = AccentSetting(prefs, onFixedChange = { changes++ })

        setting.pick(blue)
        assertEquals(0, changes)
        setting.pick(pink)
        assertEquals(1, changes)
        setting.pickWallpaper()
        assertEquals(1, changes)
        setting.pick(pink)
        assertEquals(1, changes)
        setting.pick(green)
        assertEquals(2, changes)
    }

    @Test
    fun `picking Blue over a stored -1 stores Blue, which an older build reads as it is`() {
        val raw = RuntimeEnvironment.getApplication().getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)
        raw.edit().putInt("accentColorHex", -1).commit()

        AccentSetting(prefs).pick(blue)

        assertEquals(blue, raw.getInt("accentColorHex", 0))
    }

    @Test
    fun `the app's screens follow a wallpaper pick from Android 12`() {
        val setting = AccentSetting(prefs)
        setting.pickWallpaper()
        assertTrue(setting.appFollowsWallpaper)
        assertTrue(AccentSetting(prefs).appFollowsWallpaper)
    }

    @Config(sdk = [30])
    @Test
    fun `on Android 11 the app's screens keep the swatch, even with the wallpaper stored`() {
        prefs.accentColorHex = pink
        prefs.useWallpaperColors = true

        val setting = AccentSetting(prefs)
        assertFalse(setting.appFollowsWallpaper)
        assertEquals(pink, setting.lightHex)
    }

    @Test
    fun `a pick reaches the root theme at once`() {
        val setting = AccentSetting(prefs)
        setting.pick(pink)
        var primary: Color? = null
        composeRule.setContent {
            // As MainActivity does: the root theme takes AppState's copy.
            TigerDuckAppTheme(
                darkTheme = false,
                accentColor = AccentPolicy.fixedColor(setting.lightHex, isDark = false),
                useDynamicColor = setting.appFollowsWallpaper,
            ) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFFF2D55), primary)

        composeRule.runOnIdle { setting.pickWallpaper() }
        composeRule.waitForIdle()
        assertEquals(dynamicLightColorScheme(composeRule.activity).primary, primary)

        composeRule.runOnIdle { setting.pick(pink) }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFFF2D55), primary)
    }

    @Test
    fun `after a full reset the copy reads Blue without the wallpaper`() {
        val setting = AccentSetting(prefs)
        setting.pick(pink)
        setting.pickWallpaper()
        prefs.clearAllPrefs()
        setting.reload()

        assertFalse(setting.followsWallpaper)
        assertEquals(blue, setting.lightHex)
    }

    @Test
    fun `picking a gradient stores its start as the accent and its end beside it`() {
        val setting = AccentSetting(prefs)
        setting.pickWallpaper()
        setting.pickGradient(blue, purple)

        assertFalse(setting.followsWallpaper)
        assertEquals(blue, setting.lightHex)
        assertEquals(purple, setting.gradientEnd)
        assertEquals(blue, prefs.accentColorHex)
        assertEquals(purple, AccentSetting(prefs).gradientEnd)
    }

    @Test
    fun `picking a single color after a gradient drops the gradient`() {
        val setting = AccentSetting(prefs)
        setting.pickGradient(blue, purple)
        setting.pick(blue)

        assertNull(setting.gradientEnd)
        assertNull(AccentSetting(prefs).gradientEnd)
        assertEquals(blue, prefs.accentColorHex)
    }

    @Test
    fun `picking the wallpaper keeps the gradient's start for the surfaces that cannot follow it`() {
        val setting = AccentSetting(prefs)
        setting.pickGradient(pink, orange)
        setting.pickWallpaper()

        assertTrue(setting.followsWallpaper)
        assertEquals(pink, setting.lightHex)
        assertEquals(pink, prefs.accentColorHex)
    }

    @Test
    fun `a gradient is announced only when its start changes the fixed accent`() {
        var changes = 0
        val setting = AccentSetting(prefs, onFixedChange = { changes++ })

        setting.pickGradient(blue, purple)
        assertEquals(0, changes)
        setting.pickGradient(blue, pink)
        assertEquals(0, changes)
        setting.pickGradient(pink, orange)
        assertEquals(1, changes)
        setting.pickDirection(AccentGradientDirection.LeftToRight)
        assertEquals(1, changes)
    }

    @Test
    fun `a color an older build picked over the gradient reads as that color alone`() {
        AccentSetting(prefs).pickGradient(blue, purple)
        // An older build writes only the accent.
        RuntimeEnvironment.getApplication().getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)
            .edit().putInt("accentColorHex", green).commit()

        val setting = AccentSetting(prefs)
        assertEquals(green, setting.lightHex)
        assertNull(setting.gradientEnd)
    }

    @Test
    fun `the direction picked is stored`() {
        val setting = AccentSetting(prefs)
        setting.pickGradient(blue, purple)
        setting.pickDirection(AccentGradientDirection.BottomToTop)

        assertEquals(AccentGradientDirection.BottomToTop, setting.gradientDirection)
        assertEquals(AccentGradientDirection.BottomToTop, AccentSetting(prefs).gradientDirection)
    }

    @Test
    fun `after a full reset the copy has no gradient and the first direction`() {
        val setting = AccentSetting(prefs)
        setting.pickGradient(blue, purple)
        setting.pickDirection(AccentGradientDirection.RightToLeft)
        prefs.clearAllPrefs()
        setting.reload()

        assertNull(setting.gradientEnd)
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, setting.gradientDirection)
    }
}
