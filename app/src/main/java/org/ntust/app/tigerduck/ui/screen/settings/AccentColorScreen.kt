package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.West
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
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
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_accent_color)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
    ) { padding ->
        // Each pick stores the canonical (light) hexes, so the pair swaps
        // when the color theme toggles; isDark picks what is drawn.
        AccentColorPage(
            fixedHex = appState.accentColorHex,
            gradientEnd = appState.accentGradientEndHex,
            direction = appState.accentGradientDirection,
            followsWallpaper = appState.usesWallpaperColors,
            isDark = TigerDuckTheme.isDarkMode,
            onPick = appState::pickAccentColor,
            onPickGradient = appState::pickAccentGradient,
            onPickDirection = appState::pickAccentGradientDirection,
            onPickWallpaper = appState::pickWallpaperColors,
            modifier = Modifier.padding(padding),
        )
    }
}

/** What the color picker sheet is open on: a single color, or with [end] a gradient. */
private data class OpenPicker(val start: Int, val end: Int?)

/**
 * The accent color page's content: from Android 12 the wallpaper's colors,
 * then the preset colors with one of the user's own, then the gradients,
 * ready-made and the user's own, with the way the one picked runs.
 *
 * [fixedHex] is the swatch last picked and, with [gradientEnd], the
 * gradient's start; both are light palette hexes, and stay as they are while
 * [followsWallpaper], which selects the wallpaper's colors in their place.
 */
@Composable
internal fun AccentColorPage(
    fixedHex: Int,
    gradientEnd: Int?,
    direction: AccentGradientDirection,
    followsWallpaper: Boolean,
    isDark: Boolean,
    onPick: (Int) -> Unit,
    onPickGradient: (start: Int, end: Int) -> Unit,
    onPickDirection: (AccentGradientDirection) -> Unit,
    onPickWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wallpaper = wallpaperColors(isDark)
    // Below Android 12 a stored wallpaper pick draws the swatch it kept, so
    // the swatch is what shows selected.
    val picksWallpaper = followsWallpaper && wallpaper != null
    val singlePick = !picksWallpaper && gradientEnd == null
    val gradientPick = if (!picksWallpaper) gradientEnd else null
    var picker by remember { mutableStateOf<OpenPicker?>(null) }
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
                EvenLines(count = AppPreferences.themeColors.size + 1) {
                    AppPreferences.themeColors.forEach { (_, lightHex) ->
                        AccentSwatch(
                            fill = solidSwatch(lightHex, isDark),
                            name = presetColorName(lightHex).orEmpty(),
                            selected = singlePick && fixedHex == lightHex,
                            checkTint = checkOn(lightHex, isDark),
                            onClick = { onPick(lightHex) },
                        )
                    }
                    val ownPicked = singlePick && !AccentPolicy.isPreset(fixedHex)
                    AccentSwatch(
                        fill = if (ownPicked) solidSwatch(fixedHex, isDark) else HueWheel,
                        name = stringResource(R.string.course_color_picker_custom_label),
                        selected = ownPicked,
                        checkTint = checkOn(fixedHex, isDark),
                        idleIcon = Icons.Filled.Add,
                        // Opens on the color drawn now, the user's own or a preset.
                        onClick = { picker = OpenPicker(start = fixedHex, end = null) },
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_accent_gradients))
        ContentCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EvenLines(count = AccentPolicy.presetGradients.size + 1) {
                    AccentPolicy.presetGradients.forEach { (start, end) ->
                        AccentSwatch(
                            fill = gradientSwatch(start, end, direction, isDark),
                            name = gradientName(start, end),
                            selected = gradientPick == end && fixedHex == start,
                            checkTint = checkOnGradient(start, end, isDark),
                            onClick = { onPickGradient(start, end) },
                        )
                    }
                    val ownGradient = gradientPick?.takeIf { (fixedHex to it) !in AccentPolicy.presetGradients }
                    AccentSwatch(
                        fill = if (ownGradient != null) {
                            gradientSwatch(fixedHex, ownGradient, direction, isDark)
                        } else {
                            HueWheel
                        },
                        name = stringResource(R.string.settings_accent_gradient_custom),
                        selected = ownGradient != null,
                        checkTint = if (ownGradient != null) checkOnGradient(fixedHex, ownGradient, isDark) else Color.White,
                        idleIcon = Icons.Filled.Add,
                        onClick = {
                            // Opens on the gradient picked, or from the color
                            // picked to the end of its ready-made gradient.
                            val end = gradientPick
                                ?: AccentPolicy.presetGradients.firstOrNull { it.first == fixedHex }?.second
                                ?: AccentPolicy.presetGradients.first().second
                            picker = OpenPicker(start = fixedHex, end = end)
                        },
                    )
                }
                if (gradientPick != null) {
                    Text(
                        stringResource(R.string.settings_accent_gradient_direction),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DirectionChooser(
                        startHex = fixedHex,
                        endHex = gradientPick,
                        selected = direction,
                        isDark = isDark,
                        onPick = onPickDirection,
                    )
                }
            }
        }
    }

    picker?.let { open ->
        AccentPickerSheet(
            initialStart = open.start,
            initialEnd = open.end,
            direction = direction,
            isDark = isDark,
            onApply = { start, end ->
                picker = null
                if (end == null) onPick(start) else onPickGradient(start, end)
            },
            onDismiss = { picker = null },
        )
    }
}

/** The swatches that open the picker, until a color of the user's own is picked: every hue. */
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

private val AccentGradientDirection.label: Int
    get() = when (this) {
        AccentGradientDirection.TopToBottom -> R.string.settings_accent_gradient_direction_top_to_bottom
        AccentGradientDirection.BottomToTop -> R.string.settings_accent_gradient_direction_bottom_to_top
        AccentGradientDirection.LeftToRight -> R.string.settings_accent_gradient_direction_left_to_right
        AccentGradientDirection.RightToLeft -> R.string.settings_accent_gradient_direction_right_to_left
        AccentGradientDirection.TopRightToBottomLeft -> R.string.settings_accent_gradient_direction_top_right_to_bottom_left
        AccentGradientDirection.TopLeftToBottomRight -> R.string.settings_accent_gradient_direction_top_left_to_bottom_right
        AccentGradientDirection.BottomLeftToTopRight -> R.string.settings_accent_gradient_direction_bottom_left_to_top_right
        AccentGradientDirection.BottomRightToTopLeft -> R.string.settings_accent_gradient_direction_bottom_right_to_top_left
    }

// The screen's own sides, as the directions are: not mirrored for a
// right-to-left language.
private val AccentGradientDirection.arrow: ImageVector
    get() = when (this) {
        AccentGradientDirection.TopToBottom -> Icons.Filled.South
        AccentGradientDirection.BottomToTop -> Icons.Filled.North
        AccentGradientDirection.LeftToRight -> Icons.Filled.East
        AccentGradientDirection.RightToLeft -> Icons.Filled.West
        AccentGradientDirection.TopRightToBottomLeft -> Icons.Filled.SouthWest
        AccentGradientDirection.TopLeftToBottomRight -> Icons.Filled.SouthEast
        AccentGradientDirection.BottomLeftToTopRight -> Icons.Filled.NorthEast
        AccentGradientDirection.BottomRightToTopLeft -> Icons.Filled.NorthWest
    }

/**
 * The eight ways the gradient picked can run, in two rows of four in the
 * order of [AccentGradientDirection]: each direction above its reverse. Each
 * is a tile of that gradient running that way, with an arrow along it. The
 * one picked is ringed.
 */
@Composable
private fun DirectionChooser(
    startHex: Int,
    endHex: Int,
    selected: AccentGradientDirection,
    isDark: Boolean,
    onPick: (AccentGradientDirection) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val ring = MaterialTheme.colorScheme.onSurface
    val arrowTint = checkOnGradient(startHex, endHex, isDark)
    val density = LocalDensity.current
    BoxWithConstraints {
        // In pixels, as the row lays them out, so four always fit the card.
        val grid = with(density) {
            directionGrid(
                widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
                tilePx = DirectionTileSize.roundToPx(),
                minGapPx = DirectionMinGap.roundToPx(),
                maxGapPx = AccentSwatchGap.roundToPx(),
            )
        }
        val tile = with(density) { grid.tilePx.toDp() }
        val gap = with(density) { grid.gapPx.toDp() }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            AccentGradientDirection.entries.chunked(DirectionColumns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { option ->
                        val name = stringResource(option.label)
                        val isSelected = option == selected
                        Box(
                            modifier = Modifier
                                .size(tile)
                                .clip(shape)
                                .background(gradientSwatch(startHex, endHex, option, isDark))
                                .then(if (isSelected) Modifier.border(3.dp, ring, shape) else Modifier)
                                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(option) })
                                .semantics { contentDescription = name },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = option.arrow,
                                contentDescription = null,
                                tint = arrowTint,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DirectionColumns = 4

/** A tile a finger can tap. */
private val DirectionTileSize = 48.dp
private val DirectionMinGap = 4.dp

internal data class DirectionGrid(val tilePx: Int, val gapPx: Int)

/**
 * The size of the direction tiles and the gap between them, for four tiles to
 * a row in [widthPx]. The gap gives way first, from the swatches' own down to
 * [minGapPx]; only a width too narrow for four whole tiles even then shrinks
 * the tiles.
 */
internal fun directionGrid(widthPx: Int, tilePx: Int, minGapPx: Int, maxGapPx: Int): DirectionGrid {
    val gaps = DirectionColumns - 1
    val room = widthPx.toLong() - tilePx.toLong() * DirectionColumns
    if (room >= minGapPx.toLong() * gaps) {
        return DirectionGrid(tilePx, (room / gaps).coerceAtMost(maxGapPx.toLong()).toInt())
    }
    return DirectionGrid(((widthPx - minGapPx * gaps) / DirectionColumns).coerceAtLeast(1), minGapPx)
}
