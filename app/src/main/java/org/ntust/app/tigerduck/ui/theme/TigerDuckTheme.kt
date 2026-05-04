package org.ntust.app.tigerduck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

// Hues inherited from the Tailwind -700 line but with HSV saturation scaled
// to ~0.55x and value scaled to ~0.75x so dark-mode tiles read as muted and
// dim rather than vivid.
val courseColorPaletteDark: List<Color> = listOf(
    Color(0xFF8B4A4A), // Red
    Color(0xFF925C46), // Orange
    Color(0xFF875F41), // Amber
    Color(0xFF795F39), // Ochre
    Color(0xFF4A5D30), // Lime
    Color(0xFF346045), // Green
    Color(0xFF2A5A4C), // Emerald
    Color(0xFF2E5855), // Teal
    Color(0xFF36616C), // Cyan
    Color(0xFF386179), // Sky
    Color(0xFF5569A2), // Blue
    Color(0xFF605B98), // Indigo
    Color(0xFF765AA3), // Violet
    Color(0xFF7A549B), // Purple
    Color(0xFF7E4783), // Fuchsia
    Color(0xFF8F4A67), // Pink
    Color(0xFF8F4859), // Rose
    Color(0xFF323840), // Slate
)

object TigerDuckTheme {
    private val courseColorMapRef =
        java.util.concurrent.atomic.AtomicReference<Map<String, Color>>(emptyMap())
    private val courseColorMap get() = courseColorMapRef.get()

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
        courseColorMapRef.set(buildCourseColorAssignments(courses))
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

}

/**
 * Pure, side-effect-free color assignment used by both the app and the
 * widget. Pinned (user-picked) hex colors claim their slots first, then
 * remaining courses are assigned probing through the palette so collisions
 * between different courses are avoided — the widget must call this with the
 * same inputs as the app to stay in sync.
 */
fun buildCourseColorAssignments(courses: List<Course>): Map<String, Color> {
    val map = mutableMapOf<String, Color>()
    val taken = mutableSetOf<Color>()

    courses.forEach { course ->
        val pinned = parseHexOrNull(course.customColorHex) ?: return@forEach
        map[course.courseNo] = pinned
        taken.add(pinned)
    }

    courses.filter { map[it.courseNo] == null }
        .sortedBy { it.courseNo }
        .forEach { course ->
            val color = pickFreeCourseColor(course.courseNo, taken)
            map[course.courseNo] = color
            taken.add(color)
        }

    return map
}

private fun pickFreeCourseColor(courseNo: String, taken: Set<Color>): Color {
    val base = courseHashIndex(courseNo)
    var idx = base
    var probes = 0
    while (probes < courseColorPalette.size && courseColorPalette[idx] in taken) {
        idx = (idx + 1) % courseColorPalette.size
        probes++
    }
    if (probes < courseColorPalette.size) return courseColorPalette[idx]

    val paletteArgbs = courseColorPalette.mapTo(mutableSetOf()) { it.toArgb() }
    val rng = Random(courseNo.hashCode())
    repeat(32) {
        val h = rng.nextFloat() * 360f
        val s = 0.55f + rng.nextFloat() * 0.4f
        val v = 0.55f + rng.nextFloat() * 0.3f
        val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        if (c.toArgb() !in paletteArgbs && c !in taken) return c
    }
    return Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(
                rng.nextFloat() * 360f,
                0.7f,
                0.7f
            )
        )
    )
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

// The default M3 unchecked thumb is `outline`, which collapses to near-invisible
// dark gray on the dark track in dark mode — so we force a white thumb and a
// neutral track. The dark unchecked track has to be noticeably darker than the
// card surface (#3A3A3C) so the off-state still reads as a control; using
// #39393D made it disappear into the card.
private val SwitchUncheckedTrackLight = Color(0xFFD1D1D6)
private val SwitchUncheckedTrackDark = Color(0xFF1C1C1E)

@Composable
fun tigerDuckSwitchColors(): SwitchColors {
    val isDark = TigerDuckTheme.isDarkMode
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = if (isDark) SwitchUncheckedTrackDark else SwitchUncheckedTrackLight,
        uncheckedBorderColor = Color.Transparent,
    )
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

    background = Color.White,            // page background
    onBackground = Color(0xFF1C1C1E),

    surface = Color(0xFFF2F2F7),         // cards (pale gray on white background)
    onSurface = Color(0xFF1C1C1E),

    surfaceVariant = Color(0xFFE5E5EA),  // subtle fills, slightly darker than surface
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
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondaryContainer = Color(0xFFE5E5EA),

    tertiary = Color(0xFF64D2FF),
    onTertiary = Color.White,

    background = Color(0xFF000000),      // iOS systemBackground (dark)
    onBackground = Color.White,

    surface = Color(0xFF3A3A3C),         // cards (brighter gray so they stand out on black)
    onSurface = Color.White,

    surfaceVariant = Color(0xFF48484A),
    onSurfaceVariant = Color(0xFFC7C7CC),

    outline = Color(0xFF5A5A5C),
    outlineVariant = Color(0xFF3A3A3C),

    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF5F1B14),
    onErrorContainer = Color(0xFFFEE2E2),

    surfaceTint = Color(0xFF0A84FF),
)

// Larger + bolder take on the Material 3 default type scale. Font sizes are
// bumped ~2sp across the board and every slot is at least SemiBold so text
// reads as emphatic throughout the app.
private val TigerDuckTypography: Typography = Typography().let { default ->
    Typography(
        displayLarge = default.displayLarge.copy(fontSize = 60.sp, fontWeight = FontWeight.Bold),
        displayMedium = default.displayMedium.copy(fontSize = 48.sp, fontWeight = FontWeight.Bold),
        displaySmall = default.displaySmall.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold),
        headlineLarge = default.headlineLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold),
        headlineMedium = default.headlineMedium.copy(
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        ),
        headlineSmall = default.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
        titleLarge = default.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = default.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = default.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = default.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        bodyMedium = default.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        bodySmall = default.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelLarge = default.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = default.labelMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = default.labelSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    )
}

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
        typography = TigerDuckTypography,
        content = content
    )
}
