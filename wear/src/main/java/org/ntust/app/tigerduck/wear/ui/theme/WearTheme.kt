package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import org.ntust.app.tigerduck.shared.Course

val LocalAccentColor = staticCompositionLocalOf { Color(0xFF007AFF) }

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
fun WearTheme(accent: Color, paddingDp: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalScreenPadding provides paddingDp.dp,
    ) {
        MaterialTheme(content = content)
    }
}
