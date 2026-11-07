package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DeviceAccents
import org.ntust.app.tigerduck.liveactivity.LiveUpdateSegments
import org.ntust.app.tigerduck.shared.AccentGradient

/**
 * AppState's copy of the accent picked in Settings, held as Compose state over its preferences:
 * the accent, one color or a gradient of several; whether the wallpaper's colors are picked in its
 * place; and the three switches that hand its gradient to the widgets, the watch and the Live
 * Update. The root theme reads it, so a pick recolors every screen at once.
 *
 * A pick stores the accent and turns the wallpaper off; the wallpaper's pick leaves the accent as
 * it is, for the surfaces [AccentPolicy] keeps on it. [onWidgetsChange] hears each change to what
 * the widgets draw, its first stop or the gradient while theirs is on, and [onLiveUpdateChange]
 * each change to the Live Update's gradient: both redraw only when asked. Its own class because
 * [AppState] needs a full Hilt graph, and this needs only the store.
 */
internal class AccentSetting(
    private val prefs: AppPreferences,
    private val onWidgetsChange: () -> Unit = {},
    private val onLiveUpdateChange: () -> Unit = LiveUpdateSegments::accentChanged,
) {
    private var accentState by mutableStateOf(prefs.accent)
    private var followsWallpaperState by mutableStateOf(prefs.useWallpaperColors)
    private var onWidgetsState by mutableStateOf(prefs.accentGradientOnWidgets)
    private var onWatchState by mutableStateOf(prefs.accentGradientOnWatch)
    private var onLiveActivitiesState by mutableStateOf(prefs.accentGradientOnLiveActivities)

    /** The accent picked, kept while the wallpaper is picked. */
    val accent: AccentGradient get() = accentState

    /** Its first stop, the color everything that draws one color draws, as its light palette hex. */
    val lightHex: Int get() = accentState.first

    /** Whether the wallpaper's colors are the pick, whether or not this device has them. */
    val followsWallpaper: Boolean get() = followsWallpaperState

    /** Whether the app's screens draw the wallpaper's colors on this device. */
    val appFollowsWallpaper: Boolean
        get() = AccentPolicy.appFollowsWallpaper(followsWallpaperState)

    var gradientOnWidgets: Boolean
        get() = onWidgetsState
        set(value) = changing {
            onWidgetsState = value
            prefs.accentGradientOnWidgets = value
        }

    var gradientOnWatch: Boolean
        get() = onWatchState
        set(value) {
            onWatchState = value
            prefs.accentGradientOnWatch = value
        }

    var gradientOnLiveActivities: Boolean
        get() = onLiveActivitiesState
        set(value) = changing {
            onLiveActivitiesState = value
            prefs.accentGradientOnLiveActivities = value
        }

    /** The three switches at once, as Settings shows them. */
    var switches: AccentGradientSwitchStates
        get() = AccentGradientSwitchStates(onWidgetsState, onWatchState, onLiveActivitiesState)
        set(value) {
            if (value.onWidgets != onWidgetsState) gradientOnWidgets = value.onWidgets
            if (value.onWatch != onWatchState) gradientOnWatch = value.onWatch
            if (value.onLiveActivities != onLiveActivitiesState) gradientOnLiveActivities = value.onLiveActivities
        }

    /** The gradient each surface away from the app's screens draws, by its switch. */
    val deviceAccents: DeviceAccents
        get() = AccentPolicy.deviceAccents(accentState, onWidgetsState, onWatchState, onLiveActivitiesState)

    /** Picks the single color of light palette hex [lightHex], in place of a gradient and the wallpaper. */
    fun pick(lightHex: Int) = pickAccent(AccentGradient.color(lightHex))

    /**
     * Picks [accent], light palette hexes, in place of the wallpaper too. Stored even when the copy
     * already holds it: a stored value that is no color reads as Blue, and picking Blue then writes
     * Blue over it, which an older build reads as it is.
     */
    fun pickAccent(accent: AccentGradient) = changing {
        leaveWallpaper()
        prefs.storeAccent(accent)
        accentState = prefs.accent
    }

    private fun leaveWallpaper() {
        if (!followsWallpaperState) return
        followsWallpaperState = false
        prefs.useWallpaperColors = false
    }

    /** Picks the wallpaper's colors, keeping the accent for what cannot follow them. */
    fun pickWallpaper() {
        if (followsWallpaperState) return
        followsWallpaperState = true
        prefs.useWallpaperColors = true
    }

    /** Reads the store again, after a full reset wiped it. */
    fun reload() {
        accentState = prefs.accent
        followsWallpaperState = prefs.useWallpaperColors
        onWidgetsState = prefs.accentGradientOnWidgets
        onWatchState = prefs.accentGradientOnWatch
        onLiveActivitiesState = prefs.accentGradientOnLiveActivities
    }

    /** What the widgets draw: the first stop, and the gradient while theirs is on. */
    private fun widgetAccent() = accentState.first to deviceAccents.widgets

    /** Runs [change], and tells the widgets and the Live Update if what each draws changed. */
    private fun changing(change: () -> Unit) {
        val widgets = widgetAccent()
        val liveUpdate = deviceAccents.liveActivities
        change()
        if (widgetAccent() != widgets) onWidgetsChange()
        if (deviceAccents.liveActivities != liveUpdate) onLiveUpdateChange()
    }
}

/** Whether the widgets, the watch and the Live Update each draw the accent's gradient. All off by default. */
data class AccentGradientSwitchStates(
    val onWidgets: Boolean = false,
    val onWatch: Boolean = false,
    val onLiveActivities: Boolean = false,
)
