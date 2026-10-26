package org.ntust.app.tigerduck.widget.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.CourseColorStyle
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.LocalWidgetScale
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetCourseColor

/**
 * The colors the Next Class widget draws [shown], the course it is about, in. Under TAT the widget
 * is that course's card, as Home's time machine card is: the class table's pastel, light in either
 * theme, with dark text, and the badge and the progress in that text's color. The course's own
 * darker line would leave the badge's white text under 4.5:1 on a bright picked color. Under
 * TigerDuck, or with no course to show, the theme's.
 */
internal fun nextClassColors(
    shown: Course?,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
): WidgetColors {
    if (shown == null || style.courseColorStyle != CourseColorStyle.Pastel) return colors
    val courseColor = widgetCourseColor(shown, courseColors, isDark = false)
    val fill = style.courseCardFill(courseColor, tintAlpha = 1f, muted = false, surface = colors.surface, isDark = false)
    return WidgetColors(
        isDark = false,
        background = fill,
        surface = fill,
        onSurface = style.courseCardTextColor(colors.onSurface),
        onSurfaceVariant = style.courseCardSecondaryTextColor(colors.onSurface),
        highlight = VisualStylePolicy.TextOnPastel,
        emptyCell = VisualStylePolicy.TextOnPastel.copy(alpha = 0.12f),
    )
}

/** The course the widget is about: the first class in session, else the next one. */
internal fun nextClassShownCourse(state: WidgetState): Course? = when {
    !state.isLoggedIn -> null
    state.ongoingBlocks.isNotEmpty() -> state.ongoingBlocks.first().course
    state.nextTodayBlock != null -> state.nextTodayBlock.course
    else -> state.tomorrowFirstCourseNo?.let { no -> state.courses.find { it.courseNo == no } }
}

@Composable
fun NextClassContent(state: WidgetState, colors: WidgetColors, style: VisualStylePolicy, tapAction: Action) {
    val height = LocalSize.current.height
    // Its content grows with the widget's sizes, and runs out of room sooner.
    val compact = height < 90.dp * LocalWidgetScale.current.textBox
    val drawn = nextClassColors(nextClassShownCourse(state), colors, style, state.courseColors)

    if (compact) CompactLayout(state, drawn, tapAction)
    else FullLayout(state, drawn, tapAction)
}

@Composable
private fun CompactLayout(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(horizontal = 10.dp.scaled, vertical = 6.dp.scaled)
            .clickable(tapAction),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        val ongoing = state.ongoingBlocks
        when {
            !state.isLoggedIn -> {
                Text(
                    text = context.getString(R.string.widget_sign_in),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 14.sp.scaled
                    ),
                )
            }

            ongoing.isNotEmpty() -> {
                val first = ongoing.first()
                val endTime = first.endTime
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(colors.highlight))
                        .cornerRadius(10.dp.scaled)
                        .padding(horizontal = 8.dp.scaled, vertical = 2.dp.scaled),
                ) {
                    Text(
                        text = if (ongoing.size > 1) {
                            context.getString(R.string.widget_ongoing_count, ongoing.size)
                        } else {
                            context.getString(R.string.widget_ongoing)
                        },
                        style = TextStyle(
                            color = ColorProvider(colors.onHighlight),
                            fontSize = 11.sp.scaled,
                            fontWeight = FontWeight.Bold
                        ),
                    )
                }
                Spacer(GlanceModifier.width(8.dp.scaled))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = first.course.displayName,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp.scaled * state.courseNameScale,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                    )
                    val second = ongoing.getOrNull(1)?.course
                    val firstRoom = first.classroom
                    Text(
                        text = if (second != null) second.displayName else buildString {
                            if (endTime.isNotEmpty()) {
                                append(context.getString(R.string.widget_until_time, endTime))
                            }
                            if (firstRoom.isNotEmpty()) {
                                if (isNotEmpty()) append("  ")
                                append(firstRoom)
                            }
                        },
                        style = TextStyle(
                            color = ColorProvider(if (second != null) colors.onSurface else colors.onSurfaceVariant),
                            // Only the second-course branch is a course name; the
                            // fallback string is "until …  room" metadata so it
                            // stays at the fixed 12.sp regardless of the slider.
                            fontSize = if (second != null) 14.sp.scaled * state.courseNameScale else 12.sp.scaled,
                            fontWeight = if (second != null) FontWeight.Bold else FontWeight.Normal,
                        ),
                        maxLines = 1,
                    )
                }
            }

            state.nextTodayBlock != null -> {
                val block = state.nextTodayBlock
                val course = block.course
                val startTime = block.startTime
                Text(
                    text = context.getString(R.string.widget_next_class_short),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 12.sp.scaled,
                        fontWeight = FontWeight.Bold
                    ),
                )
                Spacer(GlanceModifier.width(8.dp.scaled))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = course.displayName,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp.scaled * state.courseNameScale,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                    )
                    val room = block.classroom
                    Text(
                        text = buildString {
                            if (startTime.isNotEmpty()) append(startTime)
                            if (room.isNotEmpty()) {
                                if (isNotEmpty()) append("  ")
                                append(room)
                            }
                        },
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 12.sp.scaled
                        ),
                        maxLines = 1,
                    )
                }
            }

            else -> {
                val tomorrowCourseNo = state.tomorrowFirstCourseNo
                val tomorrowCourse = tomorrowCourseNo?.let { no ->
                    state.courses.find { it.courseNo == no }
                }
                val tomorrowPeriodId = state.tomorrowFirstCoursePeriodId
                val tomorrowStartTime = tomorrowPeriodId
                    ?.let { AppConstants.PeriodTimes.mapping[it]?.first }
                val tomorrowWeekday = state.tomorrowFirstCourseWeekday
                Spacer(GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    Text(
                        text = tomorrowCourse?.displayName
                            ?: context.getString(R.string.widget_no_more_classes),
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            // The fallback "no more classes" string is empty-state
                            // copy, not a course name — keep it at the baseline
                            // 15.sp so the slider doesn't blow it up.
                            fontSize = if (tomorrowCourse != null) {
                                15.sp.scaled * state.courseNameScale
                            } else {
                                15.sp.scaled
                            },
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                    )
                    if (tomorrowCourse != null && !tomorrowStartTime.isNullOrEmpty()) {
                        Text(
                            text = futureCourseTimeLabel(
                                context = context,
                                todayWeekday = state.currentWeekday,
                                futureWeekday = tomorrowWeekday,
                                startTime = tomorrowStartTime,
                            ),
                            style = TextStyle(
                                color = ColorProvider(colors.onSurfaceVariant),
                                fontSize = 12.sp.scaled
                            ),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun FullLayout(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val context = LocalContext.current
    val ongoing = state.ongoingBlocks
    val heightDp = LocalSize.current.height.value
    // Widget is resizable between 2-cell (~110dp) and 4-cell (~250dp) heights.
    // A midpoint threshold picks the right variant regardless of whether the
    // widget is at its narrow (2-cell wide) or wide (3-cell wide) size.
    val tall = heightDp >= 180f * LocalWidgetScale.current.textBox

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(12.dp.scaled)
            .clickable(tapAction),
    ) {
        when {
            !state.isLoggedIn -> {
                Text(
                    text = context.getString(R.string.widget_sign_in),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 15.sp.scaled
                    ),
                )
            }

            ongoing.size >= 2 && tall -> {
                // Two stacked full-size ongoing cards, each owning half the
                // widget height via defaultWeight so the progress bars
                // naturally fall to the bottom of their halves.
                Column(modifier = GlanceModifier.defaultWeight()) {
                    OngoingCard(ongoing[0], state, colors)
                }
                Spacer(GlanceModifier.height(12.dp.scaled))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    OngoingCard(ongoing[1], state, colors)
                }
            }

            ongoing.size >= 2 -> {
                // Default 2-cell height: compact mini cards, evenly
                // distributed so the gap above, between, and below matches.
                Spacer(GlanceModifier.defaultWeight())
                OngoingMiniCard(ongoing[0], state, colors)
                Spacer(GlanceModifier.defaultWeight())
                OngoingMiniCard(ongoing[1], state, colors)
                Spacer(GlanceModifier.defaultWeight())
            }

            ongoing.size == 1 -> OngoingCard(ongoing[0], state, colors)

            state.nextTodayBlock != null -> NextCard(
                block = state.nextTodayBlock,
                label = context.getString(R.string.widget_next_class),
                colors = colors,
                courseNameScale = state.courseNameScale,
            )

            state.tomorrowFirstCourseNo != null -> {
                // Mirrors iOS `WidgetTimelineDerivation` `.tomorrowFirst` —
                // same card layout as next-today. `computeTomorrowFirst` scans
                // up to 7 weekdays ahead, so the label switches to the
                // weekday caption (e.g., "Mon") when the picked day isn't
                // literally tomorrow (Fri → Mon after a weekend with no
                // classes). The room is that day's first block's.
                val block = tomorrowFirstBlock(state)
                if (block != null) {
                    NextCard(
                        block = block,
                        label = futureDayLabel(context, state.currentWeekday, block.weekday),
                        colors = colors,
                        courseNameScale = state.courseNameScale,
                    )
                }
            }

            else -> NoMoreClassesCard(colors)
        }
    }
}

@Composable
private fun OngoingCard(block: CourseBlock, state: WidgetState, colors: WidgetColors) {
    val context = LocalContext.current
    val progress = block.progress(state.currentMinuteOfDay)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Box(
            modifier = GlanceModifier
                .background(ColorProvider(colors.highlight))
                .cornerRadius(12.dp.scaled)
                .padding(horizontal = 10.dp.scaled, vertical = 3.dp.scaled),
        ) {
            Text(
                text = context.getString(R.string.widget_ongoing),
                style = TextStyle(
                    color = ColorProvider(colors.onHighlight),
                    fontSize = 12.sp.scaled,
                    fontWeight = FontWeight.Bold
                ),
            )
        }
        Spacer(GlanceModifier.height(6.dp.scaled))
        Text(
            text = block.course.displayName,
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 19.sp.scaled * state.courseNameScale,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
        )
        Spacer(GlanceModifier.height(3.dp.scaled))
        Text(
            text = "${block.startTime}–${block.endTime}  ${block.periodRange}",
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp.scaled),
        )
        val room = block.classroom
        if (room.isNotEmpty()) {
            Text(
                text = room,
                style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp.scaled),
            )
        }
        // Weighted spacer pushes the progress bar to the bottom of whatever
        // vertical space the caller allocates (full widget for the single
        // -ongoing case, half for the tall two-ongoing case).
        Spacer(GlanceModifier.defaultWeight())
        val widgetWidth = LocalSize.current.width
        val filledWidth = (widgetWidth.value * progress - 24f * LocalWidgetScale.current.ui).coerceAtLeast(0f).dp
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(5.dp.scaled)
                .background(ColorProvider(colors.emptyCell))
                .cornerRadius(2.5.dp.scaled),
        ) {
            Box(
                modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                    .background(ColorProvider(colors.highlight))
                    .cornerRadius(2.5.dp.scaled),
            ) {}
        }
    }
}

@Composable
private fun OngoingMiniCard(block: CourseBlock, state: WidgetState, colors: WidgetColors) {
    val context = LocalContext.current
    val startTime = block.startTime
    val endTime = block.endTime
    val progress = block.progress(state.currentMinuteOfDay)

    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .background(ColorProvider(colors.highlight))
                .cornerRadius(6.dp.scaled)
                .padding(horizontal = 5.dp.scaled, vertical = 1.dp.scaled),
        ) {
            Text(
                text = context.getString(R.string.widget_ongoing),
                style = TextStyle(
                    color = ColorProvider(colors.onHighlight),
                    fontSize = 9.sp.scaled,
                    fontWeight = FontWeight.Bold
                ),
            )
        }
        Spacer(GlanceModifier.width(5.dp.scaled))
        Text(
            text = block.course.displayName,
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 13.sp.scaled * state.courseNameScale,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
    val room = block.classroom
    Text(
        text = buildString {
            if (startTime.isNotEmpty() && endTime.isNotEmpty()) append("$startTime–$endTime")
            if (room.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append(room)
            }
        },
        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp.scaled),
        maxLines = 1,
    )
    Spacer(GlanceModifier.height(3.dp.scaled))
    val widgetWidth = LocalSize.current.width
    val filledWidth = (widgetWidth.value * progress - 24f * LocalWidgetScale.current.ui).coerceAtLeast(0f).dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(3.dp.scaled)
            .background(ColorProvider(colors.emptyCell))
            .cornerRadius(1.5.dp.scaled),
    ) {
        Box(
            modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                .background(ColorProvider(colors.highlight))
                .cornerRadius(1.5.dp.scaled),
        ) {}
    }
}

/**
 * Renders the next-class card for both "next today" and "tomorrow" cases —
 * iOS pulls these through the same `nextBody`, so the layout is identical and
 * only the label string differs. [block] is the block the card is about, so
 * the start time and room are that block's, not the day's first period's or
 * every room of the day.
 */
@Composable
private fun NextCard(
    block: CourseBlock,
    label: String,
    colors: WidgetColors,
    courseNameScale: Float,
) {
    val startTime = block.startTime

    Text(
        text = label,
        style = TextStyle(
            color = ColorProvider(colors.onSurfaceVariant),
            fontSize = 13.sp.scaled,
            fontWeight = FontWeight.Bold
        ),
    )
    Spacer(GlanceModifier.height(5.dp.scaled))
    Text(
        text = block.course.displayName,
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = 19.sp.scaled * courseNameScale,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 2,
    )
    if (startTime.isNotEmpty()) {
        Text(
            text = startTime,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 14.sp.scaled),
        )
    }
    val room = block.classroom
    if (room.isNotEmpty()) {
        Text(
            text = room,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp.scaled),
        )
    }
}

/**
 * The first block of the next day with a class ([WidgetState.tomorrowFirstCourseNo]
 * on [WidgetState.tomorrowFirstCourseWeekday]), or null when there is none.
 */
internal fun tomorrowFirstBlock(state: WidgetState): CourseBlock? {
    val course = state.courses.find { it.courseNo == state.tomorrowFirstCourseNo } ?: return null
    val weekday = state.tomorrowFirstCourseWeekday ?: return null
    val periodId = state.tomorrowFirstCoursePeriodId ?: return null
    return CourseBlock.containing(course, weekday, periodId)
}

/**
 * "Tomorrow" only when [futureWeekday] is literally the calendar day after
 * [todayWeekday]; otherwise the weekday's caption. `computeTomorrowFirst`
 * scans up to 7 weekdays ahead, so a Friday-afternoon viewer with no Sat/Sun
 * classes sees Monday's first class — labeling it "Tomorrow" would mislead.
 */
internal fun futureDayLabel(context: Context, todayWeekday: Int, futureWeekday: Int): String {
    val literalTomorrow = (todayWeekday % 7) + 1
    return if (futureWeekday == literalTomorrow) {
        context.getString(R.string.widget_tomorrow)
    } else {
        context.getString(weekdayCaptionRes(futureWeekday))
    }
}

internal fun futureCourseTimeLabel(
    context: Context,
    todayWeekday: Int,
    futureWeekday: Int?,
    startTime: String,
): String {
    val literalTomorrow = (todayWeekday % 7) + 1
    return if (futureWeekday == null || futureWeekday == literalTomorrow) {
        context.getString(R.string.widget_tomorrow_time, startTime)
    } else {
        "${context.getString(weekdayCaptionRes(futureWeekday))} $startTime"
    }
}

/**
 * Caption keys, not `weekday_*_short`: in zh-Hant a short name is the bare
 * numeral the Week grid heads its columns with, which alone does not read as
 * a day.
 */
private fun weekdayCaptionRes(weekday: Int): Int = when (weekday) {
    1 -> R.string.weekday_mon_caption
    2 -> R.string.weekday_tue_caption
    3 -> R.string.weekday_wed_caption
    4 -> R.string.weekday_thu_caption
    5 -> R.string.weekday_fri_caption
    6 -> R.string.weekday_sat_caption
    else -> R.string.weekday_sun_caption
}

@Composable
private fun NoMoreClassesCard(colors: WidgetColors) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = context.getString(R.string.widget_no_more_classes),
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 16.sp.scaled,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
