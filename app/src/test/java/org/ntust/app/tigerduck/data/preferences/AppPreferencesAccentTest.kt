package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.util.JvmContext

/** The accent picked in Settings, as its two preferences store it and read it back. */
class AppPreferencesAccentTest {

    private lateinit var context: Context
    private val raw: SharedPreferences
        get() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = JvmContext()
        raw.edit().clear().commit()
    }

    @Test
    fun `an install that never picked an accent reads Blue, without the wallpaper`() {
        val prefs = AppPreferences(context)
        assertEquals(0x007AFF, prefs.accentColorHex)
        assertFalse(prefs.useWallpaperColors)
    }

    @Test
    fun `a stored -1 reads as Blue, so no surface draws it as white`() {
        raw.edit().putInt("accentColorHex", -1).commit()
        assertEquals(0x007AFF, AppPreferences(context).accentColorHex)
    }

    @Test
    fun `the wallpaper is stored apart from the swatch, which an older build still reads`() {
        val prefs = AppPreferences(context)
        prefs.accentColorHex = 0xFF2D55
        prefs.useWallpaperColors = true

        val restarted = AppPreferences(context)
        assertTrue(restarted.useWallpaperColors)
        assertEquals(0xFF2D55, restarted.accentColorHex)
        // An older build reads only this key: it must still hold the swatch.
        assertEquals(0xFF2D55, raw.getInt("accentColorHex", 0))
    }

    @Test
    fun `a full reset turns the wallpaper off again`() {
        val prefs = AppPreferences(context)
        prefs.useWallpaperColors = true
        prefs.clearAllPrefs()
        assertFalse(prefs.useWallpaperColors)
        assertEquals(0x007AFF, prefs.accentColorHex)
    }

    @Test
    fun `an install that never picked a gradient has none, and runs one top left to bottom right`() {
        val prefs = AppPreferences(context)
        assertEquals(AccentPolicy.NO_GRADIENT, prefs.accentGradientEndHex)
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, prefs.accentGradientDirection)
    }

    @Test
    fun `a gradient is stored beside the accent, which holds its start for an older build`() {
        val prefs = AppPreferences(context)
        prefs.accentColorHex = 0x007AFF
        prefs.accentGradientStartHex = 0x007AFF
        prefs.accentGradientEndHex = 0xAF52DE
        prefs.accentGradientDirection = AccentGradientDirection.BottomToTop

        val restarted = AppPreferences(context)
        assertEquals(0x007AFF, restarted.accentGradientStartHex)
        assertEquals(0xAF52DE, restarted.accentGradientEndHex)
        assertEquals(AccentGradientDirection.BottomToTop, restarted.accentGradientDirection)
        assertEquals(0x007AFF, raw.getInt("accentColorHex", 0))
    }

    @Test
    fun `the directions from a bottom corner are stored as their own codes and read back`() {
        val prefs = AppPreferences(context)
        prefs.accentGradientDirection = AccentGradientDirection.BottomLeftToTopRight
        assertEquals(6, raw.getInt("accentGradientDirection", -1))
        assertEquals(AccentGradientDirection.BottomLeftToTopRight, AppPreferences(context).accentGradientDirection)

        prefs.accentGradientDirection = AccentGradientDirection.BottomRightToTopLeft
        assertEquals(7, raw.getInt("accentGradientDirection", -1))
        assertEquals(AccentGradientDirection.BottomRightToTopLeft, AppPreferences(context).accentGradientDirection)
    }

    // A build older than these two directions reads them this way too, through
    // the same fromCode.
    @Test
    fun `a stored direction this build does not know reads as top left to bottom right`() {
        raw.edit().putInt("accentGradientDirection", 99).commit()
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, AppPreferences(context).accentGradientDirection)
    }

    @Test
    fun `a full reset drops the gradient`() {
        val prefs = AppPreferences(context)
        prefs.accentGradientStartHex = 0x007AFF
        prefs.accentGradientEndHex = 0xAF52DE
        prefs.accentGradientDirection = AccentGradientDirection.RightToLeft
        prefs.clearAllPrefs()

        assertEquals(AccentPolicy.NO_GRADIENT, prefs.accentGradientEndHex)
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, prefs.accentGradientDirection)
    }
}
