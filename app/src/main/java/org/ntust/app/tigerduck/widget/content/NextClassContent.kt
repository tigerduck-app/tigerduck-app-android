package org.ntust.app.tigerduck.widget.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
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
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableThisWeek
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.CourseColorStyle
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.LocalWidgetScale
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetChangeInk
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
                    if (second != null) {
                        Text(
                            text = second.displayName,
                            style = TextStyle(
                                color = ColorProvider(colors.onSurface),
                                fontSize = 14.sp.scaled * state.courseNameScale,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    } else {
                        // "until …  room" is metadata, so it stays at the fixed
                        // 12.sp regardless of the slider.
                        MetaLine(
                            parts = listOf(
                                MetaPart(
                                    endTime.takeIf { it.isNotEmpty() }
                                        ?.let { context.getString(R.string.widget_until_time, it) }.orEmpty(),
                                    changed = first.endChanged,
                                ),
                                roomPart(first),
                            ),
                            colors = colors,
                            fontSize = 12.sp.scaled,
                        )
                    }
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
                    MetaLine(
                        parts = listOf(MetaPart(startTime, changed = block.startChanged), roomPart(block)),
                        colors = colors,
                        fontSize = 12.sp.scaled,
                    )
                }
            }

            else -> {
                val tomorrowCourseNo = state.tomorrowFirstCourseNo
                val tomorrowCourse = tomorrowCourseNo?.let { no ->
                    state.courses.find { it.courseNo == no }
                }
                val tomorrowPeriodId = state.tomorrowFirstCoursePeriodId
                val tomorrowBlock = tomorrowFirstBlock(state)
                // The class's own start, which the school may have moved.
                val tomorrowStartTime = tomorrowBlock?.startTime
                    ?: tomorrowPeriodId?.let { AppConstants.PeriodTimes.mapping[it]?.first }
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
                                color = ColorProvider(
                                    if (tomorrowBlock?.startChanged == true) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant,
                                ),
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
            style = TextStyle(
                color = ColorProvider(if (block.timeChanged) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant),
                fontSize = 13.sp.scaled,
            ),
        )
        RoomLine(block, colors, 13.sp.scaled)
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
    MetaLine(
        parts = listOf(
            MetaPart(
                if (startTime.isNotEmpty() && endTime.isNotEmpty()) "$startTime–$endTime" else "",
                changed = block.timeChanged,
            ),
            roomPart(block),
        ),
        colors = colors,
        fontSize = 10.sp.scaled,
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
            style = TextStyle(
                color = ColorProvider(if (block.startChanged) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant),
                fontSize = 14.sp.scaled,
            ),
        )
    }
    RoomLine(block, colors, 13.sp.scaled)
}

/**
 * [block]'s room on a line of its own: after `⇄` in ink where the school moved
 * it, as iOS `NextClassView.room`; nothing for no room.
 */
@Composable
private fun RoomLine(block: CourseBlock, colors: WidgetColors, fontSize: TextUnit) {
    if (block.classroom.isEmpty()) return
    Text(
        text = block.roomLabel,
        style = TextStyle(
            color = ColorProvider(if (block.roomChanged) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant),
            fontSize = fontSize,
            fontWeight = if (block.roomChanged) FontWeight.Medium else FontWeight.Normal,
        ),
        maxLines = 1,
    )
}

/** A piece of a one-line detail, such as a time or a room; [changed] when the school changed it. */
private data class MetaPart(val text: String, val changed: Boolean)

/** [block]'s room as a [MetaPart]. */
private fun roomPart(block: CourseBlock): MetaPart = MetaPart(block.roomLabel, changed = block.roomChanged)

/**
 * One line of details. Where nothing changed, one text with its parts two
 * spaces apart, as it has always been drawn; else a row of them, each changed
 * part in the change ink, as Glance draws no spans of color.
 */
@Composable
private fun MetaLine(parts: List<MetaPart>, colors: WidgetColors, fontSize: TextUnit) {
    val shown = parts.filter { it.text.isNotEmpty() }
    if (shown.none { it.changed }) {
        Text(
            text = shown.joinToString("  ") { it.text },
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = fontSize),
            maxLines = 1,
        )
        return
    }
    val ink = widgetChangeInk(colors.isDark)
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        shown.forEachIndexed { index, part ->
            Text(
                text = part.text,
                style = TextStyle(
                    color = ColorProvider(if (part.changed) ink else colors.onSurfaceVariant),
                    fontSize = fontSize,
                    fontWeight = if (part.changed) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
                // The last part takes what is left of the line, and is cut there.
                modifier = if (index == shown.lastIndex) {
                    GlanceModifier.defaultWeight().padding(start = if (index > 0) 6.dp.scaled else 0.dp)
                } else {
                    GlanceModifier.padding(start = if (index > 0) 6.dp.scaled else 0.dp)
                },
            )
        }
    }
}

/**
 * The first block of the next day with a class ([WidgetState.tomorrowFirstCourseNo]
 * on [WidgetState.tomorrowFirstCourseWeekday]), or null when there is none. With
 * [WidgetState.today] known, the class as the school's changes left it on that
 * weekday's date in the next seven days, which starts at the given period.
 */
internal fun tomorrowFirstBlock(state: WidgetState): CourseBlock? {
    val course = state.courses.find { it.courseNo == state.tomorrowFirstCourseNo } ?: return null
    val weekday = state.tomorrowFirstCourseWeekday ?: return null
    val periodId = state.tomorrowFirstCoursePeriodId ?: return null
    val today = state.today ?: return CourseBlock.containing(course, weekday, periodId)
    val day = ClassTableThisWeek.columnDate(weekday, today.plusDays(1))
    return CourseBlock.on(course, day, state.courseChanges).firstOrNull { it.firstPeriodId == periodId }
        ?: CourseBlock.containing(course, weekday, periodId)
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
