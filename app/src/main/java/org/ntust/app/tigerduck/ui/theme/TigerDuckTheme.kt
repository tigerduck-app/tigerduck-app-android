package org.ntust.app.tigerduck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.data.model.Course
import kotlin.random.Random

val courseColorPalette: List<Color> = listOf(
    Color(0xFFDC2626), // Red
    Color(0xFFEA580C), // Orange
    Color(0xFFD97706), // Amber
    Color(0xFFCA8A04), // Ochre
    Color(0xFF65A30D), // Lime
    Color(0xFF16A34A), // Green
    Color(0xFF059669), // Emerald
    Color(0xFF0D9488), // Teal
    Color(0xFF0891B2), // Cyan
    Color(0xFF0284C7), // Sky
    Color(0xFF2563EB), // Blue
    Color(0xFF4F46E5), // Indigo
    Color(0xFF7C3AED), // Violet
    Color(0xFF9333EA), // Purple
    Color(0xFFC026D3), // Fuchsia
    Color(0xFFDB2777), // Pink
    Color(0xFFE11D48), // Rose
    Color(0xFF475569), // Slate
)

object TigerDuckTheme {
    @Volatile
    private var courseColorMap: Map<String, Color> = emptyMap()

    fun buildCourseColorMap(courses: List<Course>) {
        val map = mutableMapOf<String, Color>()
        val taken = mutableSetOf<Color>()

        // Pinned (user-picked) colors claim their slots first.
        courses.forEach { course ->
            val pinned = parseHexOrNull(course.customColorHex) ?: return@forEach
            map[course.courseNo] = pinned
            taken.add(pinned)
        }

        // Remaining courses fall back to hash-based assignment, probing forward
        // through the palette to avoid colliding with pinned/already-assigned slots.
        // When the palette is exhausted (more than 18 distinct courses), we fall
        // back to a deterministic-random color outside the palette.
        courses.filter { map[it.courseNo] == null }
            .sortedBy { it.courseNo }
            .forEach { course ->
                val color = pickFreeColor(course.courseNo, taken)
                map[course.courseNo] = color
                taken.add(color)
            }

        courseColorMap = map
    }

    fun courseColor(courseNo: String): Color {
        courseColorMap[courseNo]?.let { return it }
        return courseColorPalette[courseHashIndex(courseNo)]
    }

    private fun pickFreeColor(courseNo: String, taken: Set<Color>): Color {
        val base = courseHashIndex(courseNo)
        var idx = base
        var probes = 0
        while (probes < courseColorPalette.size && courseColorPalette[idx] in taken) {
            idx = (idx + 1) % courseColorPalette.size
            probes++
        }
        if (probes < courseColorPalette.size) return courseColorPalette[idx]

        // Palette fully taken. Pick a deterministic-random color not in the
        // palette, seeded by courseNo so the same class keeps a stable color.
        val paletteArgbs = courseColorPalette.mapTo(mutableSetOf()) { it.toArgb() }
        val rng = Random(courseNo.hashCode())
        repeat(32) {
            val h = rng.nextFloat() * 360f
            val s = 0.55f + rng.nextFloat() * 0.4f
            val v = 0.55f + rng.nextFloat() * 0.3f
            val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
            if (c.toArgb() !in paletteArgbs && c !in taken) return c
        }
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(rng.nextFloat() * 360f, 0.7f, 0.7f)))
    }

    private fun courseHashIndex(courseNo: String): Int {
        val hash = courseNo.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
        return hash % courseColorPalette.size
    }

    private fun parseHexOrNull(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

object ContentAlpha {
    const val SECONDARY = 0.6f
    const val DISABLED = 0.38f
}

object Spacing {
    const val xs = 4
    const val sm = 8
    const val md = 12
    const val lg = 16
    const val xl = 24
    const val xxl = 32
}

object CornerRadius {
    const val sm = 8
    const val md = 12
    const val lg = 18
    const val xl = 24
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF5AC8FA),
    background = Color(0xFF1C1C1E),
    surface = Color(0xFF2C2C2E),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF5AC8FA),
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
)

@Composable
fun TigerDuckAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = Color(0xFF007AFF),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme.copy(primary = accentColor)
    } else {
        LightColorScheme.copy(primary = accentColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
