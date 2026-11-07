package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The accent picked in Settings as its preferences store it and read it back: `accentColorHex`,
 * which keeps the first stop for every build and surface that draws one color, and the gradient
 * stored beside it, as the iPhone stores it (tigerduck-app#1004).
 */
class AppPreferencesAccentTest {

    private lateinit var context: Context
    private val raw: SharedPreferences
        get() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    private val gradient = requireNotNull(
        AccentGradient.of(listOf(0x4B48D1, 0x9442C7, 0xD61F45), AccentGradientStyle.Angular),
    )

    @Before
    fun setUp() {
        context = JvmContext()
        raw.edit().clear().commit()
    }

    @Test
    fun `an install that never picked an accent reads Blue, without the wallpaper or a gradient`() {
        val prefs = AppPreferences(context)
        assertEquals(0x007AFF, prefs.accentColorHex)
        assertEquals(AccentGradient.color(0x007AFF), prefs.accent)
        assertFalse(prefs.useWallpaperColors)
    }

    @Test
    fun `a stored -1 reads as Blue, so no surface draws it as white`() {
        raw.edit().putInt("accentColorHex", -1).commit()
        assertEquals(0x007AFF, AppPreferences(context).accentColorHex)
        assertEquals(AccentGradient.color(0x007AFF), AppPreferences(context).accent)
    }

    @Test
    fun `the wallpaper is stored apart from the swatch, which an older build still reads`() {
        val prefs = AppPreferences(context)
        prefs.accentColorHex = 0xFF2D55
        prefs.useWallpaperColors = true

        val restarted = AppPreferences(context)
        assertTrue(restarted.useWallpaperColors)
        assertEquals(0xFF2D55, restarted.accentColorHex)
        assertEquals(0xFF2D55, raw.getInt("accentColorHex", 0))
    }

    @Test
    fun `the custom color the previous release saved is a one-stop accent, unchanged`() {
        raw.edit().putInt("accentColorHex", 0x5856D6).commit()

        val prefs = AppPreferences(context)
        assertEquals(AccentGradient.color(0x5856D6), prefs.accent)
        assertEquals(0x5856D6, raw.getInt("accentColorHex", 0))
    }

    @Test
    fun `a saved gradient reads back, its first stop saved as the one color older builds read`() {
        AppPreferences(context).storeAccent(gradient)

        assertEquals(0x4B48D1, raw.getInt("accentColorHex", 0))
        assertEquals(gradient.encode(), raw.getString("accentGradient", null))
        assertEquals(gradient, AppPreferences(context).accent)
    }

    @Test
    fun `a color picked in a build without gradients replaces the gradient`() {
        AppPreferences(context).storeAccent(gradient)
        raw.edit().putInt("accentColorHex", 0x34C759).commit()

        assertEquals(AccentGradient.color(0x34C759), AppPreferences(context).accent)
    }

    @Test
    fun `a single color replaces the gradient, one that starts with that color too`() {
        val prefs = AppPreferences(context)
        prefs.storeAccent(requireNotNull(AccentGradient.of(listOf(0x007AFF, 0xAF52DE))))
        prefs.storeAccent(AccentGradient.color(0x007AFF))

        assertFalse(prefs.accent.isGradient)
        assertFalse(raw.contains("accentGradient"))
        assertFalse(AppPreferences(context).accent.isGradient)
    }

    @Test
    fun `a gradient stored by a newer build, or damaged, reads as its first stop alone`() {
        raw.edit()
            .putInt("accentColorHex", 0x4B48D1)
            .putString("accentGradient", """{"stops":[4933841,1],"style":"spiral","direction":"leftToRight"}""")
            .commit()

        assertEquals(AccentGradient.color(0x4B48D1), AppPreferences(context).accent)
    }

    // The release before this one (v2.4.0) stored a gradient as its start, end and direction.
    @Test
    fun `the previous release's gradient reads as two stops of a linear gradient in its direction`() {
        raw.edit()
            .putInt("accentColorHex", 0x007AFF)
            .putInt("accentGradientStartHex", 0x007AFF)
            .putInt("accentGradientEndHex", 0xAF52DE)
            .putInt("accentGradientDirection", AccentGradientDirection.BottomToTop.code)
            .commit()

        assertEquals(
            AccentGradient.of(listOf(0x007AFF, 0xAF52DE), AccentGradientStyle.Linear, AccentGradientDirection.BottomToTop),
            AppPreferences(context).accent,
        )
    }

    @Test
    fun `the previous release's gradient, picked over since by a single color, is none`() {
        raw.edit()
            .putInt("accentColorHex", 0x34C759)
            .putInt("accentGradientStartHex", 0x007AFF)
            .putInt("accentGradientEndHex", 0xAF52DE)
            .commit()

        assertEquals(AccentGradient.color(0x34C759), AppPreferences(context).accent)
    }

    @Test
    fun `the previous release's single color, with its -1 for no end, is that color alone`() {
        raw.edit()
            .putInt("accentColorHex", 0xFF9500)
            .putInt("accentGradientStartHex", -1)
            .putInt("accentGradientEndHex", -1)
            .putInt("accentGradientDirection", 3)
            .commit()

        assertEquals(AccentGradient.color(0xFF9500), AppPreferences(context).accent)
    }

    @Test
    fun `the next pick drops the previous release's keys, so its gradient never comes back`() {
        raw.edit()
            .putInt("accentColorHex", 0x007AFF)
            .putInt("accentGradientStartHex", 0x007AFF)
            .putInt("accentGradientEndHex", 0xAF52DE)
            .putInt("accentGradientDirection", 1)
            .commit()

        AppPreferences(context).storeAccent(AccentGradient.color(0x007AFF))

        assertFalse(raw.contains("accentGradientStartHex"))
        assertFalse(raw.contains("accentGradientEndHex"))
        assertFalse(raw.contains("accentGradientDirection"))
        assertEquals(AccentGradient.color(0x007AFF), AppPreferences(context).accent)
    }

    @Test
    fun `widgets, the watch and Live Updates each draw one color until their own switch is on`() {
        val prefs = AppPreferences(context)
        prefs.storeAccent(gradient)
        assertFalse(prefs.accentGradientOnWidgets)
        assertFalse(prefs.accentGradientOnWatch)
        assertFalse(prefs.accentGradientOnLiveActivities)
        assertEquals(DeviceAccents(), prefs.deviceAccents)

        prefs.accentGradientOnWidgets = true
        assertEquals(DeviceAccents(widgets = gradient), prefs.deviceAccents)
        prefs.accentGradientOnWatch = true
        assertEquals(DeviceAccents(widgets = gradient, watch = gradient), prefs.deviceAccents)
        prefs.accentGradientOnWidgets = false
        prefs.accentGradientOnLiveActivities = true
        val watchAndLive = DeviceAccents(watch = gradient, liveActivities = gradient)
        assertEquals(watchAndLive, prefs.deviceAccents)
        assertEquals(watchAndLive, AppPreferences(context).deviceAccents)

        // A single color: every switch stays as it is, and every surface draws that color.
        prefs.storeAccent(AccentGradient.color(0x5856D6))
        assertEquals(DeviceAccents(), prefs.deviceAccents)
        assertTrue(prefs.accentGradientOnWatch)
        assertEquals(0x5856D6, prefs.accentColorHex)
    }

    @Test
    fun `a full reset turns the wallpaper, the gradient and its switches off`() {
        val prefs = AppPreferences(context)
        prefs.useWallpaperColors = true
        prefs.storeAccent(gradient)
        prefs.accentGradientOnWidgets = true
        prefs.accentGradientOnWatch = true
        prefs.accentGradientOnLiveActivities = true
        prefs.clearAllPrefs()

        assertFalse(prefs.useWallpaperColors)
        assertEquals(AccentGradient.color(0x007AFF), prefs.accent)
        assertFalse(prefs.accentGradientOnWidgets || prefs.accentGradientOnWatch || prefs.accentGradientOnLiveActivities)
        assertNull(raw.getString("accentGradient", null))
    }
}
