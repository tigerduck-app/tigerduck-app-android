package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.liveactivity.LiveUpdateSegments
import org.ntust.app.tigerduck.ui.AccentGradientSwitchStates
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.wear.WearCompanion

/**
 * The accent's rows in Settings: the accent row, which opens the accent color page, and under it
 * the switches that hand its gradient to the widgets, the watch and the Live Update.
 */
@Composable
internal fun AccentSettingsRows(appState: AppState, onOpenAccentColor: () -> Unit) {
    // The accent as the app draws it: the wallpaper's swatch while it follows them, else the
    // color or gradient picked.
    val followsWallpaper = appState.usesWallpaperColors
    val wallpaper = wallpaperColors(TigerDuckTheme.isDarkMode)
    AccentColorRow(
        preview = if (followsWallpaper && wallpaper != null) wallpaper.wallpaperSwatch() else TigerDuckTheme.accentBrush,
        pickName = accentPickName(appState.accent, followsWallpaper),
        onClick = onOpenAccentColor,
    )
    AccentGradientSwitches(
        isGradient = appState.accent.isGradient,
        states = appState.accentGradientSwitches,
        onChange = { appState.accentGradientSwitches = it },
    )
}

/**
 * The gradient on widgets, on the watch ([showsWatch]: only where the watch app ships) and in Live
 * Updates ([showsLiveActivities]: only where a Live Update can draw it), each a switch of its own,
 * all off by default, as on the iPhone. While the accent is one
 * color there is no gradient to hand any of them, and they are dimmed; a surface whose switch is
 * off draws the accent's first stop.
 */
@Composable
internal fun AccentGradientSwitches(
    isGradient: Boolean,
    states: AccentGradientSwitchStates,
    onChange: (AccentGradientSwitchStates) -> Unit,
    showsWatch: Boolean = WearCompanion.SHIPS,
    showsLiveActivities: Boolean = LiveUpdateSegments.canShowGradient(),
) {
    HorizontalDivider()
    SettingsToggleRow(
        label = stringResource(R.string.settings_accent_gradient_on_widgets),
        checked = states.onWidgets,
        enabled = isGradient,
        onCheckedChange = { onChange(states.copy(onWidgets = it)) },
    )
    if (showsWatch) {
        HorizontalDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_accent_gradient_on_watch),
            checked = states.onWatch,
            enabled = isGradient,
            onCheckedChange = { onChange(states.copy(onWatch = it)) },
        )
    }
    if (showsLiveActivities) {
        HorizontalDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_accent_gradient_on_live_activities),
            checked = states.onLiveActivities,
            enabled = isGradient,
            onCheckedChange = { onChange(states.copy(onLiveActivities = it)) },
        )
    }
}
