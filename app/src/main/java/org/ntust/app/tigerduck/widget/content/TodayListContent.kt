package org.ntust.app.tigerduck.widget.content

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.parseHm
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.LocalWidgetScale
import org.ntust.app.tigerduck.widget.WidgetClassRow
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetDayRows
import org.ntust.app.tigerduck.widget.WidgetScale
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetChangeInk
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The Today widget's rows: one per block, so a course meeting in periods 3–4
 * and 7–8 has two, each with its own times and room, in the order they
 * are taught (iOS `WidgetTimelineDerivation.todayRows`). Under the school's
 * course changes on [WidgetState.today] a block is the classes it splits into,
 * by their changed starts, and a cancelled one stays, to be struck through.
 * None on a quiet day ([WidgetState.isQuietToday]): before 開學 the timetable
 * is cached weeks ahead and would list classes for a term that has not
 * started, and on a holiday the user has not opted back into no class meets,
 * as the Next Class widget shows. Filtered here rather than in
 * WidgetDataLoader because the Week grid shares `state.courses` and keeps
 * rendering the timetable.
 */
internal fun todayListBlocks(state: WidgetState): List<CourseBlock> {
    if (state.isQuietToday) return emptyList()
    val order = AppConstants.Periods.chronologicalOrder
    val today = state.today
    return state.courses
        .flatMap { course ->
            if (today == null) {
                CourseBlock.allOf(course, state.currentWeekday)
            } else {
                CourseBlock.on(course, today, state.courseChanges)
            }
        }
        // By start, as iOS sorts, then by period: the same order the
        // timetable's periods give where nothing moved.
        .sortedWith(
            compareBy(
                { parseHm(it.startTime) ?: Int.MAX_VALUE },
                { block -> order.indexOf(block.firstPeriodId).takeIf { it >= 0 } ?: Int.MAX_VALUE },
            ),
        )
}

/** How tall a Today widget is before it lists the whole day rather than the classes still to come. */
private val LARGE_HEIGHT = 200.dp

/** The most rows a Today widget lists: Glance draws only the first ten children of a column. */
private const val MAX_ROWS = 9

/**
 * The Today widget, as the iPhone's: a medium one lists the classes not over yet, each with its
 * start, name and room; a large one the whole day, each with its start and end, past classes
 * dimmed, from the first not over where they do not all fit. A class in session is outlined, with
 * a bar for how far through it is.
 */
@Composable
fun TodayListContent(state: WidgetState, colors: WidgetColors, style: VisualStylePolicy, tapAction: Action) {
    val context = LocalContext.current
    val size = LocalSize.current
    val box = textBoxOf(LocalWidgetScale.current, context.resources.configuration.fontScale)
    val large = size.height >= LARGE_HEIGHT * box
    val rows = WidgetDayRows.rows(todayListBlocks(state), state.currentMinuteOfDay)
    val padding = 10.dp.scaled
    Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(colors.background)).padding(padding).clickable(tapAction),
    ) {
        TodayHeader(state, colors, large)
        val message = when {
            !state.isLoggedIn -> R.string.widget_sign_in
            rows.isEmpty() -> if (state.currentWeekday >= 6) R.string.widget_no_classes_weekend else R.string.widget_no_classes_today
            !large && WidgetDayRows.upcoming(rows).isEmpty() -> R.string.widget_no_more_classes
            else -> null
        }
        if (message != null) {
            TodayMessage(context.getString(message), colors)
            return@Column
        }
        val rowHeight = todayRowHeight(large, LocalWidgetScale.current, context.resources.configuration.fontScale, state.courseNameScale)
        val gap = (if (large) 6.dp else 4.dp).scaled
        val headerHeight = (if (large) 26.dp else 22.dp) * box
        val available = size.height - padding * 2 - headerHeight
        val limit = (((available + gap) / (rowHeight + gap)).toInt()).coerceIn(1, MAX_ROWS)
        val shown = if (large) WidgetDayRows.window(rows, limit) else WidgetDayRows.upcoming(rows).take(limit)
        val tileWidth = size.width - padding * 2 - timeColumnWidth(large, box) - 8.dp.scaled
        // The gap is each row's own padding, not a spacer: Glance draws only a column's first ten children.
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            shown.forEachIndexed { index, row ->
                TodayRow(row, large, state, colors, style, rowHeight, tileWidth, gapAbove = if (index > 0) gap else 0.dp)
            }
        }
    }
}

/** The widget's own box for a line of text: its sizes, the phone's font size capped, whichever is larger. */
internal fun textBoxOf(scale: WidgetScale, fontScale: Float): Float =
    maxOf(scale.ui, scale.text * WidgetScale.cappedFontScale(fontScale))

/**
 * A Today row's height: 46dp large and 26dp medium at the widget's sizes, and taller by what its
 * course name's line, 15sp or 13sp, grows where [courseNameScale] draws it larger, which the row
 * would otherwise clip, as [upNextFits] counts it.
 */
internal fun todayRowHeight(large: Boolean, scale: WidgetScale, fontScale: Float, courseNameScale: Float): Dp {
    val growth = (if (large) 15f else 13f) * (courseNameScale - 1f).coerceAtLeast(0f)
    return (if (large) 46.dp else 26.dp) * textBoxOf(scale, fontScale) +
        (WidgetScale.LINE_HEIGHT * growth * scale.text * WidgetScale.cappedFontScale(fontScale)).dp
}

private fun timeColumnWidth(large: Boolean, box: Float): Dp = (if (large) 44.dp else 40.dp) * box

/** The day's title, "Today · Sat", and its date, as iOS `WidgetDayHeader`. */
@Composable
private fun TodayHeader(state: WidgetState, colors: WidgetColors, large: Boolean) {
    val context = LocalContext.current
    val weekday = state.currentWeekday
    val title = if (weekday in 1..7) {
        context.getString(R.string.widget_today_weekday_title, context.getString(WEEKDAY_SHORT[weekday - 1]))
    } else {
        context.getString(R.string.widget_today_schedule_title)
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp.scaled),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = (if (large) 16.sp else 14.sp).scaled, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        state.today?.let { day ->
            Text(
                text = dayLabel(day, context.resources.configuration.locales[0]),
                style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp.scaled),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 6.dp.scaled),
            )
        }
    }
}

/** [day] as a widget heads it, its month and day in [locale]'s own order: "Nov 21", "11月21日". */
internal fun dayLabel(day: LocalDate, locale: java.util.Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(day)

/** `weekday_*_short`, Monday first. */
internal val WEEKDAY_SHORT = listOf(
    R.string.weekday_mon_short, R.string.weekday_tue_short, R.string.weekday_wed_short, R.string.weekday_thu_short,
    R.string.weekday_fri_short, R.string.weekday_sat_short, R.string.weekday_sun_short,
)

@Composable
private fun TodayMessage(text: String, colors: WidgetColors) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 14.sp.scaled, textAlign = TextAlign.Center),
        )
    }
}

/**
 * One class: its time beside its tile. Large, its start over its end, read as one range, and a
 * class over dimmed; medium, its start alone. Under the name and room a large tile names a
 * cancellation or shows the class's progress; a medium one shows the progress as a bar along its
 * bottom.
 */
@Composable
private fun TodayRow(
    row: WidgetClassRow,
    large: Boolean,
    state: WidgetState,
    colors: WidgetColors,
    style: VisualStylePolicy,
    height: Dp,
    tileWidth: Dp,
    gapAbove: Dp,
) {
    val context = LocalContext.current
    val block = row.block
    val tile = widgetTile(block.course, state.courseColors, colors, style, stepsBack = (large && row.isPast) || block.isCancelled)
    val box = textBoxOf(LocalWidgetScale.current, context.resources.configuration.fontScale)
    val timeInk = if (block.timeChanged && !block.isCancelled) widgetChangeInk(colors.isDark) else colors.onSurface
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height + gapAbove).padding(top = gapAbove),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.width(timeColumnWidth(large, box)).semantics {
                contentDescription = if (large) context.getString(R.string.a11y_time_range, block.startTime, block.endTime) else block.startTime
            },
        ) {
            Text(
                text = block.startTime,
                style = TextStyle(
                    color = ColorProvider(if (large && row.isPast && !block.timeChanged) colors.onSurfaceVariant else timeInk),
                    fontSize = (if (large) 13.sp else 12.sp).scaled,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (large) {
                Text(
                    text = block.endTime,
                    style = TextStyle(color = ColorProvider(if (block.endChanged && !block.isCancelled) timeInk else colors.onSurfaceVariant), fontSize = 11.sp.scaled),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.width(8.dp.scaled))
        val progress = row.progress
        ClassTile(tile, outlined = progress != null, radius = (if (large) 10.dp else 6.dp).scaled, modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            TodayTileContent(row, large, tile, state.courseNameScale, tileWidth)
        }
    }
}

@Composable
private fun TodayTileContent(row: WidgetClassRow, large: Boolean, tile: WidgetTile, courseNameScale: Float, tileWidth: Dp) {
    val context = LocalContext.current
    val online = onlineRoomWord()
    val progress = row.progress
    val inner = tileWidth - (if (progress != null) TILE_OUTLINE.scaled * 2 else 0.dp)
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(horizontal = (if (large) 10.dp else 8.dp).scaled), contentAlignment = Alignment.CenterStart) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                NameRoomLine(
                    block = row.block, tile = tile, online = online,
                    nameSize = (if (large) 15.sp else 13.sp).scaled * courseNameScale,
                    roomSize = (if (large) 14.sp else 12.sp).scaled,
                    nameWeight = if (large) FontWeight.Bold else FontWeight.Medium,
                    // Medium shows a cancellation by its stroke alone; large names it below.
                    nameNote = if (!large && row.block.isCancelled) context.getString(R.string.course_card_cancelled) else null,
                )
                when {
                    !large -> Unit
                    row.block.isCancelled -> Text(
                        text = context.getString(R.string.course_card_cancelled),
                        style = TextStyle(color = ColorProvider(tile.secondaryInk), fontSize = 11.sp.scaled, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    progress != null -> Box(modifier = GlanceModifier.padding(top = 4.dp.scaled)) {
                        TileProgress(progress, tile, inner - 20.dp.scaled, 4.dp.scaled)
                    }
                }
            }
        }
        if (!large && progress != null) TileProgress(progress, tile, inner, 3.dp.scaled, track = false)
    }
}
