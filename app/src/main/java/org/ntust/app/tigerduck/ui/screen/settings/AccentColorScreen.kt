package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.scrollbar
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/** The accent color page, opened from the accent row in Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentColorScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val appState = viewModel.appState
    PageScaffold(
        title = stringResource(R.string.settings_accent_color),
        onBack = onBack,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // Each pick stores the canonical (light) hexes, so the pair swaps
        // when the color theme toggles; isDark picks what is drawn.
        AccentColorPage(
            accent = appState.accent,
            followsWallpaper = appState.usesWallpaperColors,
            isDark = TigerDuckTheme.isDarkMode,
            onPick = appState::pickAccentColor,
            onPickAccent = appState::pickAccent,
            onPickWallpaper = appState::pickWallpaperColors,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The accent color page's content: from Android 12 the wallpaper's colors, then the preset colors
 * and the custom one, which opens the gradient editor ([AccentGradientEditor]) on the accent picked,
 * a color of the user's own or a gradient of up to five, as the iPhone's custom accent does.
 *
 * [accent]'s stops are light palette hexes, and stay as they are while [followsWallpaper], which
 * selects the wallpaper's colors in their place.
 */
@Composable
internal fun AccentColorPage(
    accent: AccentGradient,
    followsWallpaper: Boolean,
    isDark: Boolean,
    onPick: (Int) -> Unit,
    onPickAccent: (AccentGradient) -> Unit,
    onPickWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wallpaper = wallpaperColors(isDark)
    // Below Android 12 a stored wallpaper pick draws the accent it kept, so
    // the accent is what shows selected.
    val picksWallpaper = followsWallpaper && wallpaper != null
    var editing by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .scrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(top = 4.dp, bottom = 32.dp),
    ) {
        if (wallpaper != null) {
            SectionHeader(stringResource(R.string.color_name_wallpaper))
            ContentCard {
                WallpaperRow(
                    swatch = wallpaper.wallpaperSwatch(),
                    checkTint = wallpaper.onPrimary,
                    selected = picksWallpaper,
                    onClick = onPickWallpaper,
                )
            }
        }

        SectionHeader(stringResource(R.string.color_picker_presets_note))
        ContentCard {
            Box(Modifier.padding(16.dp)) {
                PresetSwatches(
                    accent = accent,
                    picksWallpaper = picksWallpaper,
                    isDark = isDark,
                    onPick = onPick,
                    onEdit = { editing = true },
                )
            }
        }
    }

    if (editing) {
        AccentGradientEditor(
            initial = accent,
            isDark = isDark,
            onApply = {
                editing = false
                onPickAccent(it)
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * The eight presets and the custom one, which is selected for a color of the user's own and for any
 * gradient, as the iPhone's custom accent button is, and shows it.
 */
@Composable
private fun PresetSwatches(
    accent: AccentGradient,
    picksWallpaper: Boolean,
    isDark: Boolean,
    onPick: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    val single = !picksWallpaper && !accent.isGradient
    EvenLines(count = AppPreferences.themeColors.size + 1) {
        AppPreferences.themeColors.forEach { (_, lightHex) ->
            AccentSwatch(
                fill = solidSwatch(lightHex, isDark),
                name = presetColorName(lightHex).orEmpty(),
                selected = single && accent.first == lightHex,
                checkTint = checkOn(lightHex, isDark),
                onClick = { onPick(lightHex) },
            )
        }
        val ownPicked = !picksWallpaper && (accent.isGradient || !AccentPolicy.isPreset(accent.first))
        AccentSwatch(
            fill = if (ownPicked) accentSwatch(accent, isDark) else HueWheel,
            name = stringResource(R.string.settings_accent_gradient_custom),
            selected = ownPicked,
            checkTint = checkOnAccent(accent, isDark),
            idleIcon = Icons.Filled.Add,
            // Opens on the accent drawn now, the user's own or a preset.
            onClick = onEdit,
        )
    }
}

/** The custom swatch, until a color or gradient of the user's own is picked: every hue. */
private val HueWheel: Brush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF3B30),
        Color(0xFFFF9500),
        Color(0xFFFFCC00),
        Color(0xFF34C759),
        Color(0xFF5AC8FA),
        Color(0xFF007AFF),
        Color(0xFFAF52DE),
        Color(0xFFFF3B30),
    ),
)

/**
 * The wallpaper's colors as one row: their swatch and what they do. The
 * row is the selectable item, which TalkBack reads by the swatch's name.
 */
@Composable
private fun WallpaperRow(swatch: Brush, checkTint: Color, selected: Boolean, onClick: () -> Unit) {
    val name = stringResource(R.string.color_name_wallpaper)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Selectable merges the swatch's name and the line into the row.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AccentSwatchSize)
                .clip(CircleShape)
                .background(swatch)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                // Not white, which vanishes on the light primary of the
                // wallpaper's dark colors.
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = checkTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.settings_accent_wallpaper_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
    }
}
