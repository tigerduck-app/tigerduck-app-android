package org.ntust.app.tigerduck.widget.content

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.parseHm
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableThisWeek
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetScale
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetChangeInk
import java.util.Locale
import kotlin.math.roundToInt

// The Next Class widget's lines and labels, which its compact and full layouts share.

/**
 * [block]'s room on a line of its own: after `⇄` in ink where the school moved
 * it, or held it online, as iOS `NextClassView.room`; nothing for no room.
 */
@Composable
internal fun RoomLine(block: CourseBlock, colors: WidgetColors, fontSize: TextUnit) {
    if (block.classroom(onlineRoomWord()).isEmpty()) return
    Text(
        text = block.roomLabel(onlineRoomWord()),
        style = TextStyle(
            color = ColorProvider(if (block.roomChanged) widgetChangeInk(colors.isDark) else colors.onSurfaceVariant),
            fontSize = fontSize,
            fontWeight = if (block.roomChanged) FontWeight.Medium else FontWeight.Normal,
        ),
        maxLines = 1,
    )
}

/** A piece of a one-line detail, such as a time or a room; [changed] when the school changed it. */
internal data class MetaPart(val text: String, val changed: Boolean)

/** [block]'s room as a [MetaPart]. */
@Composable
internal fun roomPart(block: CourseBlock): MetaPart = MetaPart(block.roomLabel(onlineRoomWord()), changed = block.roomChanged)

/**
 * One line of details. Where nothing changed, one text with its parts two
 * spaces apart, as it has always been drawn; else a row of them, each changed
 * part in the change ink, as Glance draws no spans of color.
 */
@Composable
internal fun MetaLine(parts: List<MetaPart>, colors: WidgetColors, fontSize: TextUnit) {
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

/** [time], a later day's class's range or start, after that day as [futureDayLabel] names it. */
internal fun futureCourseTimeLabel(
    context: Context,
    todayWeekday: Int,
    futureWeekday: Int?,
    time: String,
): String {
    val literalTomorrow = (todayWeekday % 7) + 1
    return if (futureWeekday == null || futureWeekday == literalTomorrow) {
        context.getString(R.string.widget_tomorrow_time, time)
    } else {
        "${context.getString(weekdayCaptionRes(futureWeekday))} $time"
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
internal fun NoMoreClassesCard(colors: WidgetColors) {
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

/** [block]'s start and end, "13:20–14:10"; its start alone where its end is unknown. */
internal fun timeRange(block: CourseBlock): String =
    if (block.endTime.isEmpty()) block.startTime else "${block.startTime}–${block.endTime}"

/** [timeRange] as TalkBack reads it, "13:20 to 14:10". */
internal fun spokenRange(context: Context, block: CourseBlock): String =
    if (block.endTime.isEmpty()) block.startTime else context.getString(R.string.a11y_time_range, block.startTime, block.endTime)

/** The minutes from [minute] of today to [block]'s start; null once it has started, or for no known start. */
internal fun minutesUntil(block: CourseBlock, minute: Int): Int? =
    parseHm(block.startTime)?.minus(minute)?.takeIf { it > 0 }

/**
 * [minutes] from now as a countdown in [locale], in ICU's own words, "in 25 min." or "25 分鐘後";
 * from an hour, to the half hour, "in 1.5 hr.". The widget redraws every few minutes through the
 * class hours, so it stays that close, where the iPhone's counts down on its own.
 */
internal fun countdownLabel(minutes: Int, locale: Locale): String {
    val formatter = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.SHORT, DisplayContext.CAPITALIZATION_FOR_STANDALONE,
    )
    return if (minutes < 60) {
        formatter.format(minutes.toDouble(), RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
    } else {
        formatter.format((minutes / 30.0).roundToInt() / 2.0, RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.HOURS)
    }
}

/**
 * Whether a full Next Class widget of [size] has room for [block], the class after its card, as
 * iOS's `ViewThatFits` keeps that row only where it fits. Type is drawn at [scale]'s text size and
 * the phone's [fontScale], capped, and course names grow by [courseNameScale] too.
 *
 * Tall enough for the widget's padding, the row and the gap over it, and the taller card, a class
 * in session's, its name held to one line; wide enough for the row's time range and its room as
 * written, [online] for one held online, which are never cut, and a few characters of its name,
 * which takes what they leave.
 */
internal fun upNextFits(
    block: CourseBlock,
    online: String,
    size: DpSize,
    scale: WidgetScale,
    fontScale: Float,
    courseNameScale: Float,
): Boolean {
    val text = scale.text * WidgetScale.cappedFontScale(fontScale)
    // Padding 12 + 12, the gap 6, the badge's padding 6, spacers 6 + 3, the progress 5, the row's padding 12;
    // the badge 12sp, the time and the room 13sp each, the card's name 19sp, and the row's name and room 13sp.
    val height = 62f * scale.ui +
        WidgetScale.LINE_HEIGHT * text * (12f + 13f + 13f + 19f * courseNameScale + 13f * maxOf(courseNameScale, 1f))
    // Padding 12 + 12, the row's 10 + 10, the range's 8 and the room's 6; the range, "13:20–14:10", about
    // 5.6em at 12sp, the room at 13sp, and three characters of the name at 13sp.
    val width = 58f * scale.ui + text * (5.6f * 12f + ems(shownRoom(block, online)) * 13f + 3f * 13f * courseNameScale)
    return size.height.value >= height && size.width.value >= width
}

/** About how many em [text] takes in bold: one for each Chinese character, and a little over half for the rest. */
private fun ems(text: String): Float = text.sumOf { if (it.code >= 0x2E80) 1.0 else 0.6 }.toFloat()

/**
 * The class after the one the widget shows, as the iPhone's medium widget lists it: a tile of its
 * course with its name, room and time range.
 */
@Composable
internal fun UpNextRow(block: CourseBlock, state: WidgetState, colors: WidgetColors, style: VisualStylePolicy) {
    val context = LocalContext.current
    val tile = widgetTile(block.course, state.courseColors, colors, style)
    ClassTile(tile, outlined = false, radius = 10.dp.scaled, modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 10.dp.scaled, vertical = 6.dp.scaled),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Box(modifier = GlanceModifier.defaultWeight()) {
                NameRoomLine(block, tile, onlineRoomWord(), nameSize = 13.sp.scaled * state.courseNameScale, roomSize = 13.sp.scaled)
            }
            Text(
                text = timeRange(block),
                style = TextStyle(
                    color = ColorProvider(if (block.timeChanged) tile.changeInk else tile.secondaryInk),
                    fontSize = 12.sp.scaled,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 8.dp.scaled).semantics { contentDescription = spokenRange(context, block) },
            )
        }
    }
}
