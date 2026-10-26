package org.ntust.app.tigerduck.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.theme.AccentGradientBrush
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.readableOnGradient
import org.ntust.app.tigerduck.util.replaceIosArg
import kotlin.math.ceil

/**
 * The name TalkBack reads for each accent swatch, in [AppPreferences.themeColors]
 * order. The same keys as iOS `AppState.themeColors`, whose seventh colour it
 * calls cyan.
 */
private val accentColorNames = listOf(
    R.string.color_name_blue,
    R.string.color_name_purple,
    R.string.color_name_pink,
    R.string.color_name_red,
    R.string.color_name_orange,
    R.string.color_name_green,
    R.string.color_name_cyan,
    R.string.color_name_indigo,
)

internal val AccentSwatchSize = 40.dp
internal val AccentSwatchGap = 12.dp

/** The name of the preset of light palette hex [lightHex], or null for a color of the user's own. */
@Composable
internal fun presetColorName(lightHex: Int): String? {
    val idx = AppPreferences.themeColors.indexOfFirst { it.second == lightHex }
    return accentColorNames.getOrNull(idx)?.let { stringResource(it) }
}

/**
 * What a gradient from [startHex] to [endHex] is called: a ready-made one by
 * its two colors, "Blue to Purple", and any other the custom gradient.
 */
@Composable
internal fun gradientName(startHex: Int, endHex: Int): String {
    val start = presetColorName(startHex)
    val end = presetColorName(endHex)
    return if (start != null && end != null && (startHex to endHex) in AccentPolicy.presetGradients) {
        stringResource(R.string.settings_accent_gradient_name).replaceIosArg(1, start).replaceIosArg(2, end)
    } else {
        stringResource(R.string.settings_accent_gradient_custom)
    }
}

/** What the accent picked is called, as the Settings row reads it out. */
@Composable
internal fun accentPickName(fixedHex: Int, gradientEnd: Int?, followsWallpaper: Boolean): String = when {
    followsWallpaper -> stringResource(R.string.color_name_wallpaper)
    gradientEnd != null -> gradientName(fixedHex, gradientEnd)
    else -> presetColorName(fixedHex) ?: stringResource(R.string.course_color_picker_custom_label)
}

/** The wallpaper's colors in [isDark]'s mode, as the root theme draws them, or null before Android 12. */
@Composable
internal fun wallpaperColors(isDark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

/** The wallpaper's swatch: its primary running into its tertiary, as Android's own pickers show it. */
internal fun ColorScheme.wallpaperSwatch(): Brush = Brush.linearGradient(listOf(primary, tertiary))

/** A single color's swatch, as [isDark]'s theme draws it. */
internal fun solidSwatch(lightHex: Int, isDark: Boolean): Brush =
    SolidColor(AccentPolicy.fixedColor(lightHex, isDark))

/** A gradient's swatch along [direction], each end as [isDark]'s theme draws it. */
internal fun gradientSwatch(startHex: Int, endHex: Int, direction: AccentGradientDirection, isDark: Boolean): Brush =
    AccentGradientBrush(AccentPolicy.fixedColor(startHex, isDark), AccentPolicy.fixedColor(endHex, isDark), direction)

/** The check on the accent [lightHex]: white on a preset, and on a color of the user's own whatever reads. */
internal fun checkOn(lightHex: Int, isDark: Boolean): Color = AccentPolicy.onAccent(lightHex, isDark) ?: Color.White

/**
 * The check or arrow on a gradient from [startHex] to [endHex]: what reads on
 * its middle, where it sits, which may not be what reads on its start.
 */
internal fun checkOnGradient(startHex: Int, endHex: Int, isDark: Boolean): Color = readableOnGradient(
    AccentPolicy.fixedColor(startHex, isDark),
    AccentPolicy.fixedColor(endHex, isDark),
    onStart = checkOn(startHex, isDark),
)

/**
 * One swatch on the accent color page: a selectable item named by its color,
 * so TalkBack says which color it is and which one is chosen, not just
 * "double tap to activate". The chosen one wears a check; the custom ones,
 * until chosen, a plus.
 */
@Composable
internal fun AccentSwatch(
    fill: Brush,
    name: String,
    selected: Boolean,
    checkTint: Color,
    onClick: () -> Unit,
    idleIcon: ImageVector? = null,
) {
    Box(
        modifier = Modifier
            .size(AccentSwatchSize)
            .clip(CircleShape)
            .background(fill)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val icon = if (selected) Icons.Filled.Check else idleIcon
        if (icon != null) {
            // No semantics of its own: the selected state says it.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) checkTint else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * [count] items of [itemSize] in lines as even as they can be. A line that
 * would overflow the card wraps, and the items share the lines: nine on a
 * card that fits eight go five and four, not eight and one on its own.
 */
@Composable
internal fun EvenLines(
    count: Int,
    itemSize: Dp = AccentSwatchSize,
    gap: Dp = AccentSwatchGap,
    content: @Composable FlowRowScope.() -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints {
        val perLine = if (constraints.hasBoundedWidth) {
            // In the pixels FlowRow lays them out in, which rounds each gap
            // up: in dp nine may fit a width that, at 2.75x, the ninth does not.
            val size = with(density) { itemSize.roundToPx() }
            val space = with(density) { ceil(gap.toPx()).toInt() }
            ((constraints.maxWidth + space) / (size + space)).coerceIn(1, count.coerceAtLeast(1))
        } else {
            count
        }
        val lines = (count + perLine - 1) / perLine
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = ((count + lines - 1) / lines.coerceAtLeast(1)).coerceAtLeast(1),
            content = content,
        )
    }
}

/**
 * The accent row in Settings: its name, a circle showing the accent picked,
 * and the way into the accent color page. TalkBack reads the row as one
 * button, "Accent color, Blue".
 */
@Composable
internal fun AccentColorRow(preview: Brush, pickName: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_accent_color),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(preview)
                // So a color close to the card's own still shows its edge.
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape)
                .semantics { contentDescription = pickName },
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp),
        )
    }
}
