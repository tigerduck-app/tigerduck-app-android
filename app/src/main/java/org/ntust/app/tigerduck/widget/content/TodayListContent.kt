package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.CourseColorStyle
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.LocalWidgetScale
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.isOngoing
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetCourseColor

/**
 * The Today widget's rows: one per block, so a course meeting in periods 3–4
 * and 7–8 has two rows, each with its own times and room, in the order they
 * are taught (iOS `WidgetTimelineDerivation.todayRows`). None on a quiet day
 * ([WidgetState.isQuietToday]): before 開學 the timetable is cached weeks
 * ahead and would list classes for a term that has not started, and on a
 * holiday the user has not opted back into no class meets, as the Next Class
 * widget shows. Filtered here rather than in WidgetDataLoader because the
 * Week grid shares `state.courses` and keeps rendering the timetable.
 */
internal fun todayListBlocks(state: WidgetState): List<CourseBlock> {
    if (state.isQuietToday) return emptyList()
    val order = AppConstants.Periods.chronologicalOrder
    return state.courses
        .flatMap { CourseBlock.allOf(it, state.currentWeekday) }
        .sortedBy { block -> order.indexOf(block.firstPeriodId).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/**
 * A row's fill on the Today widget. Under TigerDuck, the widget's surface, or
 * for a class in session its course's own color, as on iOS, so back-to-back
 * 衝堂 rows in session stay apart. Under TAT every row takes its course's
 * pastel, as Home's cards do: the class in session the class table's, and
 * the rest the paler one of a card that steps back.
 */
internal fun todayRowFill(
    course: Course,
    ongoing: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
): Color = when {
    style.courseColorStyle == CourseColorStyle.Pastel -> style.courseCardFill(
        courseColor = widgetCourseColor(course, courseColors, isDark = false),
        tintAlpha = 1f,
        muted = !ongoing,
        surface = colors.surface,
        isDark = colors.isDark,
    )
    !ongoing -> colors.surface
    else -> style.courseAccentColor(
        courseColor = widgetCourseColor(course, courseColors, isDark = false),
        tint = widgetCourseColor(course, courseColors, colors.isDark),
    )
}

/** A row's periods and course name on [todayRowFill]. */
internal fun todayRowTextColor(ongoing: Boolean, colors: WidgetColors, style: VisualStylePolicy): Color = when {
    style.courseColorStyle == CourseColorStyle.Pastel -> style.courseCardTextColor(colors.onSurface)
    ongoing -> style.courseAccentTextColor(tintText = Color.White)
    else -> colors.onSurface
}

/** A row's times and room on [todayRowFill]. */
internal fun todayRowSecondaryTextColor(ongoing: Boolean, colors: WidgetColors, style: VisualStylePolicy): Color = when {
    style.courseColorStyle == CourseColorStyle.Pastel -> style.courseCardSecondaryTextColor(colors.onSurface)
    ongoing -> style.courseAccentSecondaryTextColor(tintText = Color.White.copy(alpha = 0.85f))
    else -> colors.onSurfaceVariant
}

@Composable
fun TodayListContent(state: WidgetState, colors: WidgetColors, style: VisualStylePolicy, tapAction: Action) {
    val context = LocalContext.current
    val today = state.currentWeekday
    val dayNames = listOf(
        context.getString(R.string.weekday_mon_short),
        context.getString(R.string.weekday_tue_short),
        context.getString(R.string.weekday_wed_short),
        context.getString(R.string.weekday_thu_short),
        context.getString(R.string.weekday_fri_short),
        context.getString(R.string.weekday_sat_short),
        context.getString(R.string.weekday_sun_short),
    )
    val isWeekend = today == 6 || today == 7
    val todayBlocks = todayListBlocks(state)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(8.dp.scaled)
            .clickable(tapAction),
    ) {
        Text(
            text = if (today in 1..7) {
                context.getString(R.string.widget_today_weekday_title, dayNames[today - 1])
            } else {
                context.getString(R.string.widget_today_schedule_title)
            },
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 15.sp.scaled,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.padding(bottom = 6.dp.scaled),
        )

        when {
            !state.isLoggedIn -> {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = context.getString(R.string.widget_sign_in),
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 14.sp.scaled,
                        ),
                    )
                }
            }

            todayBlocks.isEmpty() && isWeekend -> {
                Text(
                    text = context.getString(R.string.widget_no_classes_weekend),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 14.sp.scaled
                    ),
                )
            }

            todayBlocks.isEmpty() -> {
                Text(
                    text = context.getString(R.string.widget_no_classes_today),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 14.sp.scaled
                    ),
                )
            }

            else -> {
                todayBlocks.forEach { block ->
                    val course = block.course
                    val isOngoing = state.isOngoing(block)
                    val rowBg = todayRowFill(course, isOngoing, colors, style, state.courseColors)
                    val primaryText = todayRowTextColor(isOngoing, colors, style)
                    val secondaryText = todayRowSecondaryTextColor(isOngoing, colors, style)

                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp.scaled)
                            .background(ColorProvider(rowBg))
                            .cornerRadius(4.dp.scaled)
                            .padding(horizontal = 6.dp.scaled, vertical = 4.dp.scaled),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Column(modifier = GlanceModifier.width(70.dp * LocalWidgetScale.current.textBox)) {
                            Text(
                                text = block.periodRange,
                                style = TextStyle(
                                    color = ColorProvider(primaryText),
                                    fontSize = 13.sp.scaled,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                            Text(
                                text = "${block.startTime}–${block.endTime}",
                                style = TextStyle(
                                    color = ColorProvider(secondaryText),
                                    fontSize = 11.sp.scaled,
                                ),
                            )
                        }
                        Column(
                            modifier = GlanceModifier.defaultWeight().padding(start = 6.dp.scaled),
                        ) {
                            Text(
                                text = course.displayName,
                                style = TextStyle(
                                    color = ColorProvider(primaryText),
                                    fontSize = 14.sp.scaled * state.courseNameScale,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                            )
                            val room = block.classroom
                            if (room.isNotEmpty()) {
                                Text(
                                    text = room,
                                    style = TextStyle(
                                        color = ColorProvider(secondaryText),
                                        fontSize = 11.sp.scaled,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
