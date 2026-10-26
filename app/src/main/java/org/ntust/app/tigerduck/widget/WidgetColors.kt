package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

data class WidgetColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val highlight: Color,
    val emptyCell: Color,
    /** Text and icons on [highlight]: white, or dark on a light accent of the user's own. */
    val onHighlight: Color = Color.White,
)

object WidgetTheme {
    val Light = WidgetColors(
        isDark = false,
        background = Color(0xFFF5F5F5),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1C1C1E),
        onSurfaceVariant = Color(0xFF6E6E73),
        highlight = Color(0xFF0066CC),
        emptyCell = Color(0xFFECECEC),
    )
    val Dark = WidgetColors(
        isDark = true,
        background = Color(0xFF1C1C1E),
        surface = Color(0xFF2C2C2E),
        onSurface = Color(0xFFF5F5F5),
        onSurfaceVariant = Color(0xFF8E8E93),
        highlight = Color(0xFF4DA3FF),
        emptyCell = Color(0xFF2C2C2E),
    )
}

/**
 * Resolves the display color for [course], preferring the pre-computed
 * assignment in [courseColors] so the widget matches the app's per-course
 * palette picks (which probe for free slots to avoid collisions). Falls back
 * to a hash-based palette pick if the map is missing the course.
 */
fun widgetCourseColor(
    course: Course,
    courseColors: Map<String, Color>,
    isDark: Boolean,
): Color {
    val resolved = courseColors[course.courseNo]
        ?: course.customColorHex?.let { hex ->
            try {
                Color(android.graphics.Color.parseColor(hex))
            } catch (_: Exception) {
                null
            }
        }
        ?: hashPaletteColor(course.courseNo)

    if (!isDark) return resolved
    val lightIdx = courseColorPalette.indexOfFirst { it == resolved }
    return if (lightIdx >= 0) courseColorPaletteDark[lightIdx] else resolved
}

private fun hashPaletteColor(courseNo: String): Color {
    val hash = courseNo.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return courseColorPalette[hash % courseColorPalette.size]
}

// Shared across every widget so the theme mode stays in lockstep with the
// app's regardless of which widget is rendering.
fun resolveWidgetIsDark(prefs: AppPreferences, context: Context): Boolean =
    when (prefs.themeMode) {
        "dark" -> true
        "light" -> false
        else -> {
            val nightMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            nightMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

/**
 * The accent every widget draws: the swatch last picked, in the theme's
 * variant, as the app draws it. Never the wallpaper's colors, which only the
 * app's own screens follow (see [AccentPolicy]).
 */
fun resolveWidgetAccentColor(prefs: AppPreferences, isDark: Boolean): Color =
    AccentPolicy.fixedColor(prefs.accentColorHex, isDark)

/** Text and icons on [resolveWidgetAccentColor]'s accent. */
fun resolveWidgetOnAccentColor(prefs: AppPreferences, isDark: Boolean): Color =
    AccentPolicy.onAccent(prefs.accentColorHex, isDark) ?: Color.White
