package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentShader
import org.ntust.app.tigerduck.shared.Course

val LocalAccentColor = staticCompositionLocalOf { Color(0xFF007AFF) }

/**
 * The phone's accent gradient, each stop as the watch draws it, or null while the phone picks a
 * single color or keeps its watch switch off: [LocalAccentColor] is then the accent, and also the
 * gradient's first stop. Draw the accent with [accentBrush] or [accentFill], which read both.
 */
val LocalAccentGradient = staticCompositionLocalOf<AccentGradient?> { null }

/**
 * What a mark of the accent within [bounds] paints with: [start] alone, or [gradient] laid across
 * [bounds] in its style, on the screen's own sides, as the phone draws it ([AccentShader]).
 */
fun accentBrush(start: Color, gradient: AccentGradient?, bounds: Rect): Brush =
    if (gradient == null || !gradient.isGradient) SolidColor(start) else AccentMarkBrush(gradient, bounds)

/** [gradient] over [bounds], whatever the size of what it fills, equal to another of the same. */
@Immutable
private class AccentMarkBrush(val gradient: AccentGradient, val bounds: Rect) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        AccentShader.create(gradient, bounds.left, bounds.top, bounds.width, bounds.height)

    override fun equals(other: Any?): Boolean =
        other is AccentMarkBrush && other.gradient == gradient && other.bounds == bounds

    override fun hashCode(): Int = gradient.hashCode() * 31 + bounds.hashCode()
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
    accentGradient: AccentGradient?,
    paddingDp: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        // No right-to-left locale ships, but a watch set to Arabic or Hebrew still has a
        // right-to-left configuration, which Compose would mirror every screen by.
        LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalAccentColor provides accent,
        LocalAccentGradient provides accentGradient,
        LocalScreenPadding provides paddingDp.dp,
    ) {
        MaterialTheme(content = content)
    }
}
