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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DeviceAccents
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientStyle
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
    fun `only a pick that changes what the widgets draw is announced`() {
        var changes = 0
        val setting = AccentSetting(prefs, onWidgetsChange = { changes++ })

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

    private fun gradient(vararg stops: Int, style: AccentGradientStyle = AccentGradientStyle.Linear) =
        requireNotNull(AccentGradient.of(stops.toList(), style))

    @Test
    fun `picking a gradient stores its first stop as the accent and the gradient beside it`() {
        val setting = AccentSetting(prefs)
        setting.pickWallpaper()
        setting.pickAccent(gradient(blue, purple, pink, style = AccentGradientStyle.Mesh))

        assertFalse(setting.followsWallpaper)
        assertEquals(blue, setting.lightHex)
        assertEquals(gradient(blue, purple, pink, style = AccentGradientStyle.Mesh), setting.accent)
        assertEquals(blue, prefs.accentColorHex)
        assertEquals(setting.accent, AccentSetting(prefs).accent)
    }

    @Test
    fun `picking a single color after a gradient drops the gradient`() {
        val setting = AccentSetting(prefs)
        setting.pickAccent(gradient(blue, purple))
        setting.pick(blue)

        assertFalse(setting.accent.isGradient)
        assertFalse(AccentSetting(prefs).accent.isGradient)
        assertEquals(blue, prefs.accentColorHex)
    }

    @Test
    fun `picking the wallpaper keeps the gradient for the surfaces that cannot follow it`() {
        val setting = AccentSetting(prefs)
        setting.pickAccent(gradient(pink, orange))
        setting.pickWallpaper()

        assertTrue(setting.followsWallpaper)
        assertEquals(pink, setting.lightHex)
        assertEquals(gradient(pink, orange), setting.accent)
    }

    @Test
    fun `a gradient is announced to the widgets only while they draw it, or when its first stop moves`() {
        var changes = 0
        val setting = AccentSetting(prefs, onWidgetsChange = { changes++ })

        setting.pickAccent(gradient(blue, purple))
        assertEquals(0, changes)
        setting.pickAccent(gradient(blue, pink))
        assertEquals(0, changes)
        setting.pickAccent(gradient(pink, orange))
        assertEquals(1, changes)

        setting.gradientOnWidgets = true
        assertEquals(2, changes)
        setting.pickAccent(gradient(pink, orange).withStyle(AccentGradientStyle.Radial))
        assertEquals(3, changes)
        setting.gradientOnWatch = true
        setting.gradientOnLiveActivities = true
        assertEquals(3, changes)
    }

    @Test
    fun `the Live Update hears only a change to what it draws of the accent`() {
        var changes = 0
        val setting = AccentSetting(prefs, onLiveUpdateChange = { changes++ })

        // A single color: the Live Update draws no gradient, whatever its switch says.
        setting.gradientOnLiveActivities = true
        setting.gradientOnLiveActivities = false
        assertEquals(0, changes)

        // A gradient while its switch is off, and the other surfaces' switches: nothing it draws.
        setting.pickAccent(gradient(blue, purple))
        setting.gradientOnWidgets = true
        setting.gradientOnWatch = true
        assertEquals(0, changes)

        setting.gradientOnLiveActivities = true
        assertEquals(1, changes)
        setting.pickAccent(gradient(blue, purple))
        assertEquals("the same gradient again", 1, changes)
        setting.pickAccent(gradient(blue, purple, style = AccentGradientStyle.Radial))
        assertEquals(2, changes)
        setting.pick(green)
        assertEquals("a single color in its place", 3, changes)
        setting.gradientOnLiveActivities = false
        assertEquals("off with one color", 3, changes)
    }

    @Test
    fun `a color an older build picked over the gradient reads as that color alone`() {
        AccentSetting(prefs).pickAccent(gradient(blue, purple))
        // An older build writes only the accent.
        RuntimeEnvironment.getApplication().getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)
            .edit().putInt("accentColorHex", green).commit()

        val setting = AccentSetting(prefs)
        assertEquals(green, setting.lightHex)
        assertEquals(AccentGradient.color(green), setting.accent)
    }

    @Test
    fun `the three switches start off, are stored, and hand each surface the gradient`() {
        val setting = AccentSetting(prefs)
        setting.pickAccent(gradient(blue, purple))
        assertFalse(setting.gradientOnWidgets || setting.gradientOnWatch || setting.gradientOnLiveActivities)
        assertEquals(DeviceAccents(), setting.deviceAccents)

        setting.gradientOnWidgets = true
        setting.gradientOnLiveActivities = true
        assertEquals(DeviceAccents(widgets = gradient(blue, purple), liveActivities = gradient(blue, purple)), setting.deviceAccents)
        val restarted = AccentSetting(prefs)
        assertTrue(restarted.gradientOnWidgets)
        assertFalse(restarted.gradientOnWatch)
        assertTrue(restarted.gradientOnLiveActivities)
    }

    @Test
    fun `after a full reset the copy has no gradient and every switch is off`() {
        val setting = AccentSetting(prefs)
        setting.pickAccent(gradient(blue, purple))
        setting.gradientOnWatch = true
        prefs.clearAllPrefs()
        setting.reload()

        assertEquals(AccentGradient.color(blue), setting.accent)
        assertFalse(setting.gradientOnWatch)
    }
}
