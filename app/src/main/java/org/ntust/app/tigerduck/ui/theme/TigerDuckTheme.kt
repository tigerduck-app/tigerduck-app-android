package org.ntust.app.tigerduck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.shared.Course
import kotlin.random.Random

/**
 * Canonical (light-mode) schedule palette. Persistence layer always stores the
 * light-mode hex; [courseColorPaletteDark] provides the paired dark variant at
 * the same index so switching themes keeps the "same" class color.
 */
// Shared with iOS (TigerDuckTheme.coursePaletteHexes). Both platforms
// store and sync colors as these hex values so cross-device sync works.
val courseColorPalette: List<Color> = listOf(
    Color(0xFFFF6B6B), // 珊瑚紅
    Color(0xFF4ECDC4), // 青綠
    Color(0xFF45B7D1), // 天藍
    Color(0xFFF39C12), // 橘橙
    Color(0xFFDDA0DD), // 梅紫
    Color(0xFF2ECC71), // 翡翠綠
    Color(0xFFE74C3C), // 磚紅
    Color(0xFF3498DB), // 寶藍
    Color(0xFFF7DC6F), // 金黃
    Color(0xFF9B59B6), // 紫羅蘭
    Color(0xFF1ABC9C), // 碧綠
    Color(0xFFE67E22), // 南瓜橘
    Color(0xFF85C1E9), // 淺藍
    Color(0xFFD35400), // 焦橙
    Color(0xFF27AE60), // 森林綠
    Color(0xFFC0392B), // 酒紅
    Color(0xFF8E44AD), // 深紫
    Color(0xFF16A085), // 松綠
    Color(0xFFF1C40F), // 向日葵黃
    Color(0xFF2980B9), // 鈷藍
)

// Dark-mode variants: HSV saturation scaled to ~0.55x, value to ~0.75x
// so tiles read as muted/dim. Same palette order as above.
val courseColorPaletteDark: List<Color> = listOf(
    Color(0xFF994747), // 珊瑚紅
    Color(0xFF3A7A73), // 青綠
    Color(0xFF366D7D), // 天藍
    Color(0xFF916111), // 橘橙
    Color(0xFF846284), // 梅紫
    Color(0xFF267A4C), // 翡翠綠
    Color(0xFF8A3329), // 磚紅
    Color(0xFF265F83), // 寶藍
    Color(0xFF948448), // 金黃
    Color(0xFF5E3E6E), // 紫羅蘭
    Color(0xFF13715E), // 碧綠
    Color(0xFF8A5118), // 南瓜橘
    Color(0xFF54748C), // 淺藍
    Color(0xFF7E3700), // 焦橙
    Color(0xFF1E693D), // 森林綠
    Color(0xFF73281D), // 酒紅
    Color(0xFF562D68), // 深紫
    Color(0xFF126150), // 松綠
    Color(0xFF91750F), // 向日葵黃
    Color(0xFF1E4F6F), // 鈷藍
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
     * What the accent's own fills paint with: the gradient picked on the
     * accent color page, or the accent color itself. Only fills the app draws
     * take it, never text, icons or Material's own components, which draw
     * the accent color, the gradient's start.
     */
    val accentBrush: Brush
        @Composable @ReadOnlyComposable get() = LocalAccentBrush.current

    /**
     * What text and marks on [accentBrush] draw in: onPrimary on a single
     * color, and on a gradient whatever reads on its middle, where they sit.
     */
    val onAccentBrush: Color
        @Composable @ReadOnlyComposable get() = LocalOnAccentBrush.current

    // User-tunable course-name scale (0.8…1.6, default 1.0). Mirrored here
    // from AppState so call sites in unrelated component files (CourseCard)
    // can read it without threading a parameter through every caller —
    // same shape as [isDarkMode]. Always normalized by the AppState setter
    // before reaching this state holder.
    private val _courseNameScale = mutableFloatStateOf(1.0f)
    val courseNameScale: Float get() = _courseNameScale.floatValue

    fun setCourseNameScale(scale: Float) {
        if (_courseNameScale.floatValue != scale) _courseNameScale.floatValue = scale
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
        val existing = courseColorMapRef.get().toMutableMap()

        val pinnedCourseNos = mutableSetOf<String>()
        courses.forEach { course ->
            val pinned = parseHexOrNull(course.customColorHex) ?: return@forEach
            pinnedCourseNos.add(course.courseNo)
            existing[course.courseNo] = pinned
        }

        // Evict auto-assigned entries that now conflict with a pinned color
        val pinnedColors = pinnedCourseNos.mapNotNullTo(mutableSetOf()) { existing[it] }
        val evict = existing.keys.filter { it !in pinnedCourseNos && existing[it] in pinnedColors }
        evict.forEach { existing.remove(it) }

        val taken = existing.values.toMutableSet()
        courses.filter { existing[it.courseNo] == null }
            .sortedBy { it.courseNo }
            .forEach { course ->
                val color = pickFreeCourseColor(course.courseNo, taken)
                existing[course.courseNo] = color
                taken.add(color)
            }

        courseColorMapRef.set(existing)
    }

    fun clearCourseColorMap() {
        courseColorMapRef.set(emptyMap())
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

/**
 * The colour [courseNo] is given when it has no pick of its own: its hash's
 * palette slot, probing on past every colour in [taken], then a colour derived
 * from the course number once the palette is used up.
 */
internal fun pickFreeCourseColor(courseNo: String, taken: Set<Color>): Color {
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
// #39393D made it disappear into the card. The checked thumb is onPrimary,
// white on every swatch, and the wallpaper's own pair for its colors, whose
// dark primary is too light for a white thumb to stand out on.
private val SwitchUncheckedTrackLight = Color(0xFFD1D1D6)
private val SwitchUncheckedTrackDark = Color(0xFF1C1C1E)

@Composable
fun tigerDuckSwitchColors(): SwitchColors {
    val isDark = TigerDuckTheme.isDarkMode
    return SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
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
internal val LightColorScheme = lightColorScheme(
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

internal val DarkColorScheme = darkColorScheme(
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
    // Between surface and outline: HorizontalDivider draws in this on cards,
    // which are surface, and when the two matched every divider vanished.
    // Lighter than surfaceVariant too, the fill of bulletin cards and mail
    // rows, so a divider or chip outline on one of those shows as well.
    outlineVariant = Color(0xFF4E4E50),

    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF5F1B14),
    onErrorContainer = Color(0xFFFEE2E2),

    surfaceTint = Color(0xFF0A84FF),
)

// Larger + bolder take on the Material 3 default type scale. Font sizes are
// bumped ~2sp across the board and every slot is at least SemiBold so text
// reads as emphatic throughout the app.
internal val TigerDuckTypography: Typography = Typography().let { default ->
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

/**
 * Whether the [TigerDuckAppTheme] around draws the wallpaper's colors. A theme
 * nested inside it reads this, so it draws them too, in its own mode.
 */
private val LocalUsesDynamicColor = staticCompositionLocalOf { false }

@Composable
fun TigerDuckAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = Color(0xFF007AFF),
    /** What text and icons on [accentColor] draw in, where the theme's own would not read; null for the theme's own. */
    onAccentColor: Color? = null,
    /** The gradient picked, from [accentColor] to its end; null for a single color. */
    accentGradient: AccentGradient? = null,
    /**
     * Whether the accent slots take the wallpaper's colors in place of
     * [accentColor], from Android 12. A theme nested in another follows the
     * outer one unless told otherwise, and takes the wallpaper's colors for its
     * own mode: the class table import page is dark inside a light app, and the
     * accent it is handed, the light theme's primary, is the wrong tone there.
     */
    useDynamicColor: Boolean = LocalUsesDynamicColor.current,
    /** The interface style, provided to the content as [LocalVisualStylePolicy]. */
    visualPreset: VisualPreset = VisualPreset.Default,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val followsWallpaper = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (followsWallpaper) {
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // TigerDuck keeps explicit surface / background styling (cards, outline, page background)
        // so that only the accent slots adapt to the dynamic wallpaper palette, consistent with the
        // fixed accent presets. Each slot comes with its pair: the dark primary is a light tone,
        // and TigerDuck's white onPrimary on it reads at under 2:1.
        baseScheme.copy(
            primary = dynamic.primary,
            onPrimary = dynamic.onPrimary,
            primaryContainer = dynamic.primaryContainer,
            onPrimaryContainer = dynamic.onPrimaryContainer,
            surfaceTint = dynamic.surfaceTint,
        )
    } else {
        baseScheme.copy(
            primary = accentColor,
            onPrimary = onAccentColor ?: baseScheme.onPrimary,
            surfaceTint = accentColor,
        )
    }
    // The wallpaper's colors draw no gradient: a gradient picked waits behind them.
    val accentBrush: Brush = if (!followsWallpaper && accentGradient != null) {
        AccentGradientBrush(accentColor, accentGradient.end, accentGradient.direction)
    } else {
        SolidColor(colorScheme.primary)
    }
    val onAccentBrush = if (accentBrush is AccentGradientBrush) {
        readableOnGradient(accentBrush.start, accentBrush.end, colorScheme.onPrimary)
    } else {
        colorScheme.onPrimary
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // Raised so Chinese sits in the middle of its line, as Latin does.
        typography = rememberCjkCenteredTypography(TigerDuckTypography),
    ) {
        CompositionLocalProvider(
            LocalVisualStylePolicy provides VisualStylePolicy(visualPreset),
            LocalUsesDynamicColor provides useDynamicColor,
            LocalAccentBrush provides accentBrush,
            LocalOnAccentBrush provides onAccentBrush,
            content = content,
        )
    }
}
