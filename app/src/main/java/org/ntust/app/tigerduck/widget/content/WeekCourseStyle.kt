package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableThisWeek
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.ClassTableGridStyle
import org.ntust.app.tigerduck.ui.theme.pastelOutline
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetScale
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.widgetChangeInk
import org.ntust.app.tigerduck.widget.widgetCourseColor

// How the Week widget colors and sizes a course's block, and which change it marks on it.

/**
 * A course's fill on the Week widget: the highlight while [highlighted], as a
 * 衝堂 cluster's class in session is, else the class table's pastel under the
 * pastel style, else the widget's own tint, a little stronger in light mode
 * than the class table's; fainter for a block the school [cancelled].
 */
internal fun weekCourseFill(
    course: Course,
    highlighted: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    cancelled: Boolean = false,
): Color {
    if (highlighted) return colors.highlight
    val themed = widgetCourseColor(course, courseColors, colors.isDark)
    val fill = when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> if (colors.isDark) themed else themed.copy(alpha = 0.55f)
        ClassTableGridStyle.PeriodTiles -> style.classTableCourseFill(
            courseColor = widgetCourseColor(course, courseColors, isDark = false),
            themeColor = themed,
            isDark = colors.isDark,
        )
    }
    // Two fifths of it, as the iPhone's 0.1 to 0.25 and the class table's cancelled block.
    return if (cancelled) fill.copy(alpha = fill.alpha * 0.4f) else fill
}

/** A course's name on [weekCourseFill]. */
internal fun weekCourseTextColor(
    highlighted: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
): Color = if (highlighted) colors.onHighlight else style.classTableCourseTextColor(colors.isDark)

/**
 * How many lines of a course name fit a period tile [height] high, from one up
 * to two: a second line that does not fit is cut through the middle, on every
 * tile of the run. The name is 10sp, scaled by [courseNameScale] and the
 * system [fontScale] and the widget text size, inside the tile's 1dp above
 * and below and 2dp padding at the widget display size;
 * a line of Chinese, which most course names are, takes about 1.45 times
 * its size.
 */
internal fun tileNameLines(height: Dp, courseNameScale: Float, fontScale: Float, scale: WidgetScale = WidgetScale()): Int {
    val textHeight = height.value - (2 * 1f + 2 * 2f) * scale.ui
    val lineHeight = 10f * courseNameScale * fontScale * scale.text * 1.45f
    return (textHeight / lineHeight).toInt().coerceIn(1, 2)
}

/**
 * The ink a Week block marks the school's change in: on the highlight, the
 * text on it, as the Today list's full-color row; on a pastel, which stays
 * light in either theme, the light ink; else the theme's.
 */
internal fun weekChangeInk(highlighted: Boolean, colors: WidgetColors, style: VisualStylePolicy): Color = when {
    highlighted -> colors.onHighlight
    style.classTableGridStyle == ClassTableGridStyle.PeriodTiles -> widgetChangeInk(isDark = false)
    else -> widgetChangeInk(colors.isDark)
}

/**
 * The block of [course] over [periods] in the [weekday] column as the school
 * changed it: the changed meeting that starts at the block's first period on
 * the column's date, the week running from today, whole and cancelled only
 * when every period is. A tile that spans a hidden period draws two meetings
 * as one, and merging them would lose the first one's changed end. Null where
 * nothing a block shows changed, notes alone included, and for a state with no
 * [WidgetState.today]. As iOS `WeekGridView`'s look-up of the snapshot's
 * `meetingChanges`.
 */
internal fun weekBlockChange(state: WidgetState, course: Course, weekday: Int, periods: List<String>): CourseBlock? {
    val today = state.today ?: return null
    val date = ClassTableThisWeek.columnDate(weekday, today)
    val meetings = CourseMeetings.changedMeetings(course, date, state.courseChanges).filter { m -> m.periods.any { it in periods } }
    val first = meetings.firstOrNull { it.periods.firstOrNull() == periods.firstOrNull() }?.let(CourseBlock::of)
    // An assessment of any meeting the tile draws marks it, unless the tile reads as cancelled.
    val assessed = meetings.any { it.assessment != null } && first?.isCancelled != true
    val block = first?.copy(isAssessment = assessed) ?: CourseBlock(course, weekday, periods, isAssessment = true).takeIf { assessed }
    return block?.takeIf { it.isCancelled || it.roomChanged || it.timeChanged || it.isAssessment }
}

/**
 * The outline and progress bar of a Week block in session, as iOS's tile accent: the course's own
 * color around its translucent block, or a darker mix of it around its pastel tile, as the class
 * table outlines a required course ([VisualStylePolicy.classTableCourseOutline]).
 */
internal fun weekCourseAccent(course: Course, courseColors: Map<String, Color>, style: VisualStylePolicy): Color {
    val vivid = widgetCourseColor(course, courseColors, isDark = false)
    return when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> vivid
        ClassTableGridStyle.PeriodTiles -> vivid.pastelOutline()
    }
}
