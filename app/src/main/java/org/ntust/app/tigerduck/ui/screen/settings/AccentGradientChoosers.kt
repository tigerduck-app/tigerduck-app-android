package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.West
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle

private val AccentGradientStyle.label: Int
    get() = when (this) {
        AccentGradientStyle.Linear -> R.string.settings_accent_gradient_style_linear
        AccentGradientStyle.Radial -> R.string.settings_accent_gradient_style_radial
        AccentGradientStyle.Angular -> R.string.settings_accent_gradient_style_angular
        AccentGradientStyle.Mesh -> R.string.settings_accent_gradient_style_mesh
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

/** A tile of [accent] as [isDark]'s theme shows it, named for TalkBack, ringed while [selected]. */
@Composable
private fun SampleTile(
    accent: AccentGradient,
    isDark: Boolean,
    name: String,
    selected: Boolean,
    size: Dp,
    shape: Shape,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(accentSwatch(accent, isDark))
            .then(if (selected) Modifier.border(3.dp, ring, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The four styles, each a tile of [accent] in that style, as the iPhone's editor offers them. */
@Composable
internal fun StyleChooser(accent: AccentGradient, isDark: Boolean, onPick: (AccentGradientStyle) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Smaller only on a card too narrow for four whole ones.
        val tile = minOf(StyleTileSize, (maxWidth - StyleMinGap * 3) / 4)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AccentGradientStyle.entries.forEach { style ->
                SampleTile(
                    accent = accent.withStyle(style),
                    isDark = isDark,
                    name = stringResource(style.label),
                    selected = accent.style == style,
                    size = tile,
                    shape = shape,
                    onClick = { onPick(style) },
                )
            }
        }
    }
}

private val StyleTileSize = 64.dp
private val StyleMinGap = 8.dp

/**
 * The eight ways a linear [accent] can run, in two rows of four in the order of
 * [AccentGradientDirection]: each direction above its reverse. Each is a tile of the gradient
 * running that way, with an arrow along it. The one picked is ringed.
 */
@Composable
internal fun DirectionChooser(accent: AccentGradient, isDark: Boolean, onPick: (AccentGradientDirection) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val arrowTint = checkOnAccent(accent, isDark)
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
                        SampleTile(
                            accent = accent.withDirection(option),
                            isDark = isDark,
                            name = stringResource(option.label),
                            selected = option == accent.direction,
                            size = tile,
                            shape = shape,
                            onClick = { onPick(option) },
                        ) {
                            Icon(option.arrow, contentDescription = null, tint = arrowTint, modifier = Modifier.size(22.dp))
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
