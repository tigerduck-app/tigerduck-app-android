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
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import androidx.glance.layout.ColumnScope
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
    else FullLayout(state, drawn, style, tapAction)
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
                Box(
                    modifier = GlanceModifier
                        .accentBackground(colors, WidgetAccentMark.Badge)
                        .cornerRadius(10.dp.scaled)
                        .padding(horizontal = 8.dp.scaled, vertical = 2.dp.scaled),
                ) {
                    Text(
                        text = if (ongoing.size > 1) context.getString(R.string.widget_ongoing_count, ongoing.size)
                        else context.getString(R.string.widget_ongoing),
                        style = TextStyle(
                            color = ColorProvider(colors.onAccentFill),
                            fontSize = 11.sp.scaled,
                            fontWeight = FontWeight.Bold
                        ),
                    )
                }
                Spacer(GlanceModifier.width(8.dp.scaled))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    WidgetCourseName(
                        text = first.course.displayName, isAssessment = first.isAssessment, isDark = colors.isDark,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp.scaled * state.courseNameScale,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                    )
                    val second = ongoing.getOrNull(1)
                    if (second != null) {
                        WidgetCourseName(
                            text = second.course.displayName, isAssessment = second.isAssessment, isDark = colors.isDark,
                            style = TextStyle(
                                color = ColorProvider(colors.onSurface),
                                fontSize = 14.sp.scaled * state.courseNameScale,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    } else {
                        // The class's range and room, as the iPhone's small widget shows it, are
                        // metadata, so they stay at the fixed 12.sp regardless of the slider.
                        MetaLine(
                            parts = listOf(MetaPart(timeRange(first), changed = first.timeChanged), roomPart(first)),
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
                    WidgetCourseName(
                        text = course.displayName, isAssessment = block.isAssessment, isDark = colors.isDark,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp.scaled * state.courseNameScale,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                    )
                    MetaLine(
                        parts = listOf(MetaPart(timeRange(block), changed = block.timeChanged), roomPart(block)),
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
                // The class's own range, which the school may have moved; else its period's start.
                val tomorrowTime = tomorrowBlock?.let(::timeRange)
                    ?: tomorrowPeriodId?.let { AppConstants.PeriodTimes.mapping[it]?.first }
                val tomorrowWeekday = state.tomorrowFirstCourseWeekday
                Spacer(GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    WidgetCourseName(
                        text = tomorrowCourse?.displayName ?: context.getString(R.string.widget_no_more_classes),
                        isAssessment = tomorrowCourse != null && tomorrowBlock?.isAssessment == true, isDark = colors.isDark,
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
                    if (tomorrowCourse != null && !tomorrowTime.isNullOrEmpty()) {
                        Text(
                            text = futureCourseTimeLabel(
                                context = context,
                                todayWeekday = state.currentWeekday,
                                futureWeekday = tomorrowWeekday,
                                time = tomorrowTime,
                            ),
                            style = TextStyle(
                                color = ColorProvider(
                                    if (tomorrowBlock?.timeChanged == true) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant,
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

/**
 * The Next Class widget at its full height, as the iPhone's medium one: the class in session, or
 * the next with a countdown while it is later today, and below it, where the widget has room, the
 * class after it ([WidgetState.upNextBlock]).
 */
@Composable
private fun FullLayout(state: WidgetState, colors: WidgetColors, style: VisualStylePolicy, tapAction: Action) {
    val context = LocalContext.current
    val ongoing = state.ongoingBlocks
    val heightDp = LocalSize.current.height.value
    val box = LocalWidgetScale.current.textBox
    // Widget is resizable between 2-cell (~110dp) and 4-cell (~250dp) heights.
    // A midpoint threshold picks the right variant regardless of whether the
    // widget is at its narrow (2-cell wide) or wide (3-cell wide) size.
    val tall = heightDp >= 180f * box
    // The class after it where it fits under the card, as iOS's `ViewThatFits` gives it room.
    val upNext = state.upNextBlock?.takeIf { heightDp >= 150f * box }
    Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(colors.background)).padding(12.dp.scaled).clickable(tapAction),
    ) {
        when {
            !state.isLoggedIn -> Text(
                text = context.getString(R.string.widget_sign_in),
                style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 15.sp.scaled),
            )
            ongoing.size >= 2 -> TwoOngoing(ongoing, tall, state, colors)
            ongoing.size == 1 -> WithUpNext(upNext, state, colors, style) { OngoingCard(ongoing[0], state, colors) }
            state.nextTodayBlock != null -> WithUpNext(upNext, state, colors, style) {
                NextCard(
                    block = state.nextTodayBlock,
                    label = context.getString(R.string.widget_next_class),
                    colors = colors,
                    courseNameScale = state.courseNameScale,
                    countdown = minutesUntil(state.nextTodayBlock, state.currentMinuteOfDay)
                        ?.let { countdownLabel(it, context.resources.configuration.locales[0]) },
                )
            }
            // Mirrors iOS `WidgetTimelineDerivation` `.tomorrowFirst` — the next
            // card under the day's caption (e.g., "Mon") when the picked day isn't
            // literally tomorrow. The room is that day's first block's.
            state.tomorrowFirstCourseNo != null -> tomorrowFirstBlock(state)?.let { block ->
                NextCard(
                    block = block,
                    label = futureDayLabel(context, state.currentWeekday, block.weekday),
                    colors = colors,
                    courseNameScale = state.courseNameScale,
                )
            }
            else -> NoMoreClassesCard(colors)
        }
    }
}

/**
 * Two classes in session: on a tall widget a full card each, owning half its height so the
 * progress bars fall to the bottom of their halves; else a mini card each, evenly spaced.
 */
@Composable
private fun ColumnScope.TwoOngoing(ongoing: List<CourseBlock>, tall: Boolean, state: WidgetState, colors: WidgetColors) {
    if (tall) {
        Column(modifier = GlanceModifier.defaultWeight()) { OngoingCard(ongoing[0], state, colors) }
        Spacer(GlanceModifier.height(12.dp.scaled))
        Column(modifier = GlanceModifier.defaultWeight()) { OngoingCard(ongoing[1], state, colors) }
    } else {
        Spacer(GlanceModifier.defaultWeight())
        OngoingMiniCard(ongoing[0], state, colors)
        Spacer(GlanceModifier.defaultWeight())
        OngoingMiniCard(ongoing[1], state, colors)
        Spacer(GlanceModifier.defaultWeight())
    }
}

/** [card], and under it [upNext] where there is one. */
@Composable
private fun ColumnScope.WithUpNext(
    upNext: CourseBlock?,
    state: WidgetState,
    colors: WidgetColors,
    style: VisualStylePolicy,
    card: @Composable () -> Unit,
) {
    if (upNext == null) {
        card()
        return
    }
    Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) { card() }
    Spacer(GlanceModifier.height(6.dp.scaled))
    UpNextRow(upNext, state, colors, style)
}

@Composable
private fun OngoingCard(block: CourseBlock, state: WidgetState, colors: WidgetColors) {
    val context = LocalContext.current
    val progress = block.progress(state.currentMinuteOfDay)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Box(
            modifier = GlanceModifier
                .accentBackground(colors, WidgetAccentMark.Badge)
                .cornerRadius(12.dp.scaled)
                .padding(horizontal = 10.dp.scaled, vertical = 3.dp.scaled),
        ) {
            Text(
                text = context.getString(R.string.widget_ongoing),
                style = TextStyle(
                    color = ColorProvider(colors.onAccentFill),
                    fontSize = 12.sp.scaled,
                    fontWeight = FontWeight.Bold
                ),
            )
        }
        Spacer(GlanceModifier.height(6.dp.scaled))
        WidgetCourseName(
            text = block.course.displayName, isAssessment = block.isAssessment, isDark = colors.isDark,
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 19.sp.scaled * state.courseNameScale,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
        )
        Spacer(GlanceModifier.height(3.dp.scaled))
        Text(
            text = timeRange(block),
            style = TextStyle(
                color = ColorProvider(if (block.timeChanged) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant),
                fontSize = 13.sp.scaled,
            ),
            modifier = GlanceModifier.semantics { contentDescription = spokenRange(context, block) },
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
                    .accentBackground(colors, WidgetAccentMark.Bar)
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
                .accentBackground(colors, WidgetAccentMark.Badge)
                .cornerRadius(6.dp.scaled)
                .padding(horizontal = 5.dp.scaled, vertical = 1.dp.scaled),
        ) {
            Text(
                text = context.getString(R.string.widget_ongoing),
                style = TextStyle(
                    color = ColorProvider(colors.onAccentFill),
                    fontSize = 9.sp.scaled,
                    fontWeight = FontWeight.Bold
                ),
            )
        }
        Spacer(GlanceModifier.width(5.dp.scaled))
        WidgetCourseName(
            text = block.course.displayName, isAssessment = block.isAssessment, isDark = colors.isDark,
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
                .accentBackground(colors, WidgetAccentMark.Bar)
                .cornerRadius(1.5.dp.scaled),
        ) {}
    }
}

/**
 * Renders the next-class card for both "next today" and "tomorrow" cases —
 * iOS pulls these through the same `nextBody`, so the layout is identical and
 * only the label string differs. [block] is the block the card is about, so
 * its time range and room are that block's, not the day's. Today's carries a
 * [countdown] to its start beside the label, as the iPhone's medium widget.
 */
@Composable
private fun NextCard(
    block: CourseBlock,
    label: String,
    colors: WidgetColors,
    courseNameScale: Float,
    countdown: String? = null,
) {
    val context = LocalContext.current
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(
            text = label,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp.scaled, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (countdown != null) {
            Text(
                text = countdown,
                style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 13.sp.scaled, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
    Spacer(GlanceModifier.height(5.dp.scaled))
    WidgetCourseName(
        text = block.course.displayName, isAssessment = block.isAssessment, isDark = colors.isDark,
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = 19.sp.scaled * courseNameScale,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 2,
    )
    if (block.startTime.isNotEmpty()) {
        Text(
            text = timeRange(block),
            style = TextStyle(
                color = ColorProvider(if (block.timeChanged) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant),
                fontSize = 14.sp.scaled,
            ),
            modifier = GlanceModifier.semantics { contentDescription = spokenRange(context, block) },
        )
    }
    RoomLine(block, colors, 13.sp.scaled)
}
