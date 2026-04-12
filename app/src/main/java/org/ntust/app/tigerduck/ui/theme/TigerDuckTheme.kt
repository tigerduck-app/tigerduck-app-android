package org.ntust.app.tigerduck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.data.model.Course
import kotlin.random.Random

/**
 * Canonical (light-mode) schedule palette. Persistence layer always stores the
 * light-mode hex; [courseColorPaletteDark] provides the paired dark variant at
 * the same index so switching themes keeps the "same" class color.
 */
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

val courseColorPaletteDark: List<Color> = listOf(
    Color(0xFFB91C1C), // Red-700
    Color(0xFFC2410C), // Orange-700
    Color(0xFFB45309), // Amber-700
    Color(0xFFA16207), // Ochre-700
    Color(0xFF4D7C0F), // Lime-700
    Color(0xFF15803D), // Green-700
    Color(0xFF047857), // Emerald-700
    Color(0xFF0F766E), // Teal-700
    Color(0xFF0E7490), // Cyan-700
    Color(0xFF0369A1), // Sky-700
    Color(0xFF1D4ED8), // Blue-700
    Color(0xFF4338CA), // Indigo-700
    Color(0xFF6D28D9), // Violet-700
    Color(0xFF7E22CE), // Purple-700
    Color(0xFFA21CAF), // Fuchsia-700
    Color(0xFFBE185D), // Pink-700
    Color(0xFFBE123C), // Rose-700
    Color(0xFF334155), // Slate-700
)

object TigerDuckTheme {
    @Volatile
    private var courseColorMap: Map<String, Color> = emptyMap()

    // Compose-observable so course tiles recompose when the app switches theme.
    private val _isDarkMode = mutableStateOf(false)
    val isDarkMode: Boolean get() = _isDarkMode.value

    fun setDarkMode(dark: Boolean) {
        if (_isDarkMode.value != dark) _isDarkMode.value = dark
    }

    /**
     * If the stored color matches an entry in the canonical (light) palette,
     * return the corresponding dark variant when dark mode is active.
     * Custom HSV picks don't land on a palette index so they're returned as-is.
     */
    private fun resolveForMode(stored: Color): Color {
        if (!_isDarkMode.value) return stored
        val argb = stored.toArgb()
        val idx = courseColorPalette.indexOfFirst { it.toArgb() == argb }
        return if (idx >= 0) courseColorPaletteDark[idx] else stored
    }

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
        val stored = courseColorMap[courseNo] ?: courseColorPalette[courseHashIndex(courseNo)]
        return resolveForMode(stored)
    }

    /**
     * Returns the vibrant (light-palette) color regardless of dark mode. Use
     * this for low-alpha tints and colored text — the darker variant at low
     * opacity disappears against dark surfaces.
     */
    fun courseColorVibrant(courseNo: String): Color {
        return courseColorMap[courseNo] ?: courseColorPalette[courseHashIndex(courseNo)]
    }

    /**
     * Multiplies a tint alpha that was tuned for light mode so it stays
     * visible in dark mode. Dark surfaces swallow low-alpha tints, so we
     * roughly 2.3× the opacity (capped at 1.0) when the app is in dark mode.
     */
    fun tintAlpha(lightModeAlpha: Float): Float {
        return if (_isDarkMode.value) (lightModeAlpha * 2.3f).coerceAtMost(1f)
        else lightModeAlpha
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

// iOS-inspired, Material 3 color scheme. Keeping the slots explicit (rather
// than letting M3 derive tonal variants from `primary`) prevents the pinkish
// tint M3 otherwise applies to surfaceVariant when primary is blue.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF001A41),

    secondary = Color(0xFF5B5F68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E2E8),
    onSecondaryContainer = Color(0xFF181A20),

    tertiary = Color(0xFF5AC8FA),
    onTertiary = Color.White,

    background = Color(0xFFF2F2F7),      // iOS systemGroupedBackground
    onBackground = Color(0xFF1C1C1E),

    surface = Color.White,               // cards
    onSurface = Color(0xFF1C1C1E),

    surfaceVariant = Color(0xFFE9E9EE),  // subtle fills (chips, disabled bg)
    onSurfaceVariant = Color(0xFF5B5F68),

    outline = Color(0xFFC6C6C8),         // iOS separator
    outlineVariant = Color(0xFFE0E0E5),

    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),

    surfaceTint = Color(0xFF007AFF),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003585),
    onPrimaryContainer = Color(0xFFD5E4FF),

    secondary = Color(0xFFAEAEB2),
    onSecondary = Color(0xFF2C2C2E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),

    tertiary = Color(0xFF64D2FF),
    onTertiary = Color.White,

    background = Color(0xFF000000),      // iOS systemBackground (dark)
    onBackground = Color.White,

    surface = Color(0xFF1C1C1E),         // cards
    onSurface = Color.White,

    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),

    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF2C2C2E),

    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF5F1B14),
    onErrorContainer = Color(0xFFFEE2E2),

    surfaceTint = Color(0xFF0A84FF),
)

@Composable
fun TigerDuckAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = Color(0xFF007AFF),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme.copy(primary = accentColor, surfaceTint = accentColor)
    } else {
        LightColorScheme.copy(primary = accentColor, surfaceTint = accentColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
