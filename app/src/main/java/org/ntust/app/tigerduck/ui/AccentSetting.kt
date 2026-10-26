package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradientDirection

/**
 * AppState's copy of the accent picked in Settings, held as Compose state over
 * its preferences: the swatch last picked, the gradient it may start, and
 * whether the wallpaper's colors are picked in its place. The root theme
 * reads it, so a pick recolors every screen at once.
 *
 * A swatch's pick stores its color and turns the wallpaper off; the
 * wallpaper's pick leaves the swatch as it is, for the surfaces
 * [AccentPolicy] keeps on it. [onFixedChange] hears each pick that changes the
 * swatch: the widgets draw it, and redraw only when asked. Its own class
 * because [AppState] needs a full Hilt graph, and this needs only the store.
 */
internal class AccentSetting(
    private val prefs: AppPreferences,
    private val onFixedChange: () -> Unit = {},
) {
    private var lightHexState by mutableIntStateOf(prefs.accentColorHex)
    private var followsWallpaperState by mutableStateOf(prefs.useWallpaperColors)
    private var gradientStartState by mutableIntStateOf(prefs.accentGradientStartHex)
    private var gradientEndState by mutableIntStateOf(prefs.accentGradientEndHex)
    private var directionState by mutableStateOf(prefs.accentGradientDirection)

    /** The swatch last picked, as its light palette hex, kept while the wallpaper is picked. */
    val lightHex: Int get() = lightHexState

    /** Whether the wallpaper's colors are the pick, whether or not this device has them. */
    val followsWallpaper: Boolean get() = followsWallpaperState

    /**
     * The end color of the gradient picked, as its light palette hex, or null
     * for a single color. Its start is [lightHex]. Kept while the wallpaper is
     * picked, as the swatch is.
     */
    val gradientEnd: Int?
        get() = AccentPolicy.gradientEnd(lightHexState, gradientStartState, gradientEndState)

    /** The way the gradient runs, kept with a single color picked for the next gradient. */
    val gradientDirection: AccentGradientDirection get() = directionState

    /** Whether the app's screens draw the wallpaper's colors on this device. */
    val appFollowsWallpaper: Boolean
        get() = AccentPolicy.appFollowsWallpaper(followsWallpaperState)

    /**
     * Picks the swatch of light palette hex [lightHex], in place of the
     * wallpaper too. Stored even when the copy already holds it: a stored
     * value that is no color reads as Blue, and picking Blue then writes Blue
     * over it, which an older build reads as it is.
     */
    fun pick(lightHex: Int) {
        leaveWallpaper()
        gradientStartState = AccentPolicy.NO_GRADIENT
        gradientEndState = AccentPolicy.NO_GRADIENT
        prefs.storeAccentPick(lightHex, AccentPolicy.NO_GRADIENT, AccentPolicy.NO_GRADIENT)
        if (lightHexState == lightHex) return
        lightHexState = lightHex
        onFixedChange()
    }

    /**
     * Picks the gradient from [startHex] to [endHex], light palette hexes, in
     * place of the wallpaper too. Its start becomes the fixed accent, which
     * everything that draws no gradient draws alone. Stored whole, in one
     * write, so whatever hears the accent change reads the whole gradient.
     */
    fun pickGradient(startHex: Int, endHex: Int) {
        leaveWallpaper()
        gradientStartState = startHex
        gradientEndState = endHex
        prefs.storeAccentPick(startHex, startHex, endHex)
        if (lightHexState == startHex) return
        lightHexState = startHex
        onFixedChange()
    }

    /** Picks the way the gradient runs, which the app's screens and the watch draw. */
    fun pickDirection(direction: AccentGradientDirection) {
        if (directionState == direction) return
        directionState = direction
        prefs.accentGradientDirection = direction
    }

    private fun leaveWallpaper() {
        if (!followsWallpaperState) return
        followsWallpaperState = false
        prefs.useWallpaperColors = false
    }

    /** Picks the wallpaper's colors, keeping the swatch for what cannot follow them. */
    fun pickWallpaper() {
        if (followsWallpaperState) return
        followsWallpaperState = true
        prefs.useWallpaperColors = true
    }

    /** Reads the store again, after a full reset wiped it. */
    fun reload() {
        lightHexState = prefs.accentColorHex
        followsWallpaperState = prefs.useWallpaperColors
        gradientStartState = prefs.accentGradientStartHex
        gradientEndState = prefs.accentGradientEndHex
        directionState = prefs.accentGradientDirection
    }
}
