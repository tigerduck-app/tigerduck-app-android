package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.data.MoodleColorBook
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ColorPickerSheet
import org.ntust.app.tigerduck.ui.component.colorPickerOffersDarkPalette
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

/**
 * The color sheet of the class table's [course], with the colors the table's other [courses]
 * already take marked. [onApply] gets the color as the course keeps it, `#RRGGBB`.
 */
@Composable
internal fun CourseRecolorSheet(course: Course, courses: List<Course>, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val darkPalette = LocalVisualStylePolicy.current.colorPickerOffersDarkPalette(TigerDuckTheme.isDarkMode)
    val displayPalette = if (darkPalette) courseColorPaletteDark else courseColorPalette
    // Each course's color as the palette above has it.
    val shown = if (darkPalette) TigerDuckTheme::courseColor else TigerDuckTheme::courseColorVibrant
    val currentColor = shown(course.courseNo)
    val usedByOthers = remember(courses, course.courseNo, darkPalette) {
        courses
            .asSequence()
            .filter { it.courseNo != course.courseNo }
            .map { shown(it.courseNo).toArgb() or 0xFF000000.toInt() }
            .toSet()
    }
    ColorPickerSheet(
        courseName = course.displayName,
        currentColor = currentColor,
        presetPalette = displayPalette,
        usedByOthers = usedByOthers,
        onApply = { picked -> onApply(storedCourseColorHex(picked, darkPalette)) },
        onDismiss = onDismiss,
    )
}

/**
 * The `#RRGGBB` a course keeps for [picked], applied on the sheet's dark palette when [darkPalette]
 * is set. Preset picks are stored as the canonical (light) hex so switching theme later swaps
 * automatically, and so are Moodle colors, which the dark sheet opens on in their dark variant.
 * Custom HSV picks keep the exact color in both modes.
 */
internal fun storedCourseColorHex(picked: Color, darkPalette: Boolean): String {
    val displayPalette = if (darkPalette) courseColorPaletteDark else courseColorPalette
    val pickedArgb = picked.toArgb() or 0xFF000000.toInt()
    val displayIdx = displayPalette.indexOfFirst {
        (it.toArgb() or 0xFF000000.toInt()) == pickedArgb
    }
    val storedArgb = when {
        displayIdx >= 0 -> courseColorPalette[displayIdx].toArgb()
        darkPalette -> MoodleColorBook.lightFor(pickedArgb and 0xFFFFFF) ?: picked.toArgb()
        else -> picked.toArgb()
    }
    return "#" + String.format("%06X", storedArgb and 0xFFFFFF)
}
