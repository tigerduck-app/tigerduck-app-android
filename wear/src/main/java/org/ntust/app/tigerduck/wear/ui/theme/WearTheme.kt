package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.Course

val LocalAccentColor = staticCompositionLocalOf { Color(0xFF007AFF) }

/**
 * The rest of the phone's accent gradient, or null while the phone picks a
 * single color: [LocalAccentColor] is its start. Draw the accent with
 * [accentBrush], which reads both.
 */
val LocalAccentGradient = staticCompositionLocalOf<WearAccentGradient?> { null }

/** The end color of the phone's accent gradient and the way it runs from [LocalAccentColor]. */
@Immutable
data class WearAccentGradient(val end: Color, val direction: AccentGradientDirection)

/**
 * The gradient a snapshot carries, or null for a single color or an end that
 * is no color. An end with no direction runs the way a gradient picked before
 * any direction was runs on the phone.
 */
fun wearAccentGradient(endHex: String?, direction: AccentGradientDirection?): WearAccentGradient? {
    // parseColor throws an IllegalArgumentException for a name it does not
    // know, and an IndexOutOfBoundsException for "".
    val end = endHex?.let { hex -> runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }
        ?: return null
    return WearAccentGradient(end, direction ?: AccentGradientDirection.DEFAULT)
}

/**
 * What a mark of the accent within [bounds] paints with: [start] alone, or
 * the gradient from it laid across [bounds] in [gradient]'s direction, on
 * the screen's own sides, as the phone draws it.
 */
fun accentBrush(start: Color, gradient: WearAccentGradient?, bounds: Rect): Brush {
    gradient ?: return SolidColor(start)
    val direction = gradient.direction
    return Brush.linearGradient(
        colors = listOf(start, gradient.end),
        start = Offset(bounds.left + direction.startX * bounds.width, bounds.top + direction.startY * bounds.height),
        end = Offset(bounds.left + direction.endX * bounds.width, bounds.top + direction.endY * bounds.height),
    )
}

/**
 * [color] as text and marks on the watch's black take it: itself when it reads there
 * (4.5:1, WCAG's bar for text), otherwise lifted toward white until it does, its hue kept.
 * A dark accent, such as the iPhone's own Indigo preset, would otherwise all but vanish.
 */
internal fun readableOnBlack(color: Color): Color {
    if (contrast(color, Color.Black) >= 4.5f) return color
    var lift = 0.05f
    while (lift < 1f) {
        val lifted = lerp(color, Color.White, lift)
        if (contrast(lifted, Color.Black) >= 4.5f) return lifted
        lift += 0.05f
    }
    return Color.White
}

/** Black or white, whichever reads better on [background]. */
fun textOn(background: Color): Color =
    if (contrast(Color.Black, background) >= contrast(Color.White, background)) Color.Black else Color.White

/** WCAG's contrast ratio between [a] and [b], from 1 to 21. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * Tinted card surface per course — mirrors iOS WatchVisualStylePolicy's
 * TigerDuck preset (Color(hex: course.colorHex) → backed-off surface). Honors
 * a user-set [Course.customColorHex] first; otherwise picks deterministically
 * from a small palette by hashing [Course.courseNo]. The phone-side
 * collision-probing palette isn't sent over the wire, so this won't match the
 * phone's per-course color picks exactly — it matches iOS's behavior of
 * picking a color locally on the watch and stays stable across syncs.
 */
fun wearCourseColor(course: Course): Color {
    course.customColorHex?.let { hex ->
        runCatching { return Color(android.graphics.Color.parseColor(hex)) }
    }
    val hash = course.courseNo.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return wearCoursePalette[hash % wearCoursePalette.size]
}

private val wearCoursePalette: List<Color> = listOf(
    Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFFD97706), Color(0xFFCA8A04),
    Color(0xFF65A30D), Color(0xFF16A34A), Color(0xFF059669), Color(0xFF0D9488),
    Color(0xFF0891B2), Color(0xFF0284C7), Color(0xFF2563EB), Color(0xFF4F46E5),
    Color(0xFF7C3AED), Color(0xFF9333EA), Color(0xFFC026D3), Color(0xFFDB2777),
    Color(0xFFE11D48), Color(0xFF475569),
)

/** Horizontal screen padding, user-adjustable in Settings. Defaults to 12 dp;
 *  bumping it helps on round watches where corners eat content. */
val LocalScreenPadding = staticCompositionLocalOf<Dp> { 12.dp }

fun parseAccent(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    Color(0xFF007AFF)
}

@Composable
fun WearTheme(
    accent: Color,
    accentGradient: WearAccentGradient?,
    paddingDp: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalAccentGradient provides accentGradient,
        LocalScreenPadding provides paddingDp.dp,
    ) {
        MaterialTheme(content = content)
    }
}
