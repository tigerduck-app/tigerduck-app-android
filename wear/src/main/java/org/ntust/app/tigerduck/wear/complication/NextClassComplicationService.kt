package org.ntust.app.tigerduck.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import kotlinx.coroutines.flow.first
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.wear.MainActivity
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.SchedulePersistenceHolder
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.clockText
import org.ntust.app.tigerduck.wear.ui.weekdayCaptionRes
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

class NextClassComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline {
        val snapshot = SchedulePersistenceHolder.get(this).flow.first()
        return complicationTimeline(this, snapshot, request.complicationType, AppClock.localDateTime(), launchAppPendingIntent())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val description = PlainComplicationText.Builder(getString(R.string.watch_widget_next_class)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("T2-103").build(),
                contentDescription = description,
            ).setTitle(PlainComplicationText.Builder("14:30").build()).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("T2-103 · 14:30–15:20").build(),
                contentDescription = description,
            ).setTitle(PlainComplicationText.Builder("CN").build()).build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(0.4f, 0f, 1f, description)
                .setText(PlainComplicationText.Builder("T2-103").build()).build()

            else -> null
        }
    }

    private fun launchAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, NextClassComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}

/**
 * The complication from [now] on the app clock: what it shows now, then what it shows from each
 * moment that changes over today and tomorrow, as iOS's timeline: each start and end of a class,
 * and midnight, when a later day's class becomes tomorrow's or today's. The watch face moves to
 * each entry on its own, so neither the next class nor the one going on waits for an update; the
 * ranged value's progress moves between them ([ClassComplication]). The entries are dated on the
 * real clock the watch face runs on, as the debug clock can move the app's; while it holds the
 * app's clock still, so does the complication, with no entries.
 */
internal fun complicationTimeline(
    context: Context,
    snapshot: WatchSnapshot,
    type: ComplicationType,
    now: LocalDateTime,
    tap: PendingIntent?,
): ComplicationDataTimeline {
    val current = complicationAt(context, snapshot, type, now, tap)
    // An entry must be of the type the timeline is; with nothing to show now, the next update fills
    // it. A debug clock held still holds the app's "now" still, which the real clock wouldn't.
    if (current is NoDataComplicationData || AppClock.isFrozen()) return ComplicationDataTimeline(current, emptyList())
    val today = now.toLocalDate()
    val midnights = (1L..2L).map { today.plusDays(it).atStartOfDay(TaipeiClock.zone).toInstant() }
    val changes = (snapshot.boundaries(today, now.hour * 60 + now.minute, days = 2) + midnights).distinct().sorted()
    val entries = changes.mapIndexedNotNull { i, at ->
        val until = changes.getOrElse(i + 1) { at.plus(Duration.ofDays(1)) }
        val start = Instant.ofEpochMilli(AppClock.realTimeFor(at.toEpochMilli()))
        val end = Instant.ofEpochMilli(AppClock.realTimeFor(until.toEpochMilli()))
        if (start >= end) return@mapIndexedNotNull null
        val data = complicationAt(context, snapshot, type, LocalDateTime.ofInstant(at, TaipeiClock.zone), tap)
        TimelineEntry(TimeInterval(start, end), data)
    }
    return ComplicationDataTimeline(current, entries)
}

/** What the complication shows at [at] on the app clock: the class going on, else the next one. */
internal fun complicationAt(
    context: Context,
    snapshot: WatchSnapshot,
    type: ComplicationType,
    at: LocalDateTime,
    tap: PendingIntent?,
): ComplicationData {
    if (snapshot.syncedAtMs == null || snapshot.courses.isEmpty()) return NoDataComplicationData()
    val content = when (val r = snapshot.nextClass(at.toLocalDate(), at.hour * 60 + at.minute)) {
        is NextClassResult.Ongoing -> {
            val start = at.toLocalDate().atStartOfDay(TaipeiClock.zone).plusMinutes(r.startMinute.toLong())
            val seconds = (r.endMinute - r.startMinute) * 60
            val elapsed = Duration.between(start, at.atZone(TaipeiClock.zone)).seconds.toInt().coerceIn(0, seconds)
            val realStart = Instant.ofEpochMilli(AppClock.realTimeFor(start.toInstant().toEpochMilli()))
            val span = ClassSpan(realStart.takeUnless { AppClock.isFrozen() }, seconds, elapsed)
            classComplication(
                context, r.course, r.classroom, r.change?.room != null, r.startMinute, r.endMinute, daysAhead = 0,
                weekday = r.weekday, shortTitle = "NOW", longTitle = "NOW · ${truncate(r.course.displayName, 7)}", span = span,
            )
        }

        is NextClassResult.NextToday -> classComplication(
            context, r.course, r.classroom, r.change?.room != null, r.startMinute, r.endMinute, daysAhead = 0,
            weekday = r.weekday, shortTitle = clockText(r.startMinute), longTitle = r.course.displayName,
        )

        is NextClassResult.NextFuture -> classComplication(
            context, r.course, r.classroom, r.change?.room != null, r.startMinute, r.endMinute, r.daysAhead, r.weekday,
            shortTitle = laterDayTitle(context, r.daysAhead, r.weekday, clockText(r.startMinute)),
            longTitle = r.course.displayName,
        )

        NextClassResult.Empty -> return NoDataComplicationData()
    }
    return content.dataFor(type, tap)
}

/**
 * The day of a class after today before [time], its start or its range, as the phone's Next class
 * widget words it. "Tomorrow 08:10" for the next day, else the weekday's caption, "Tue 08:10".
 */
internal fun laterDayTitle(context: Context, daysAhead: Int, weekday: Int, time: String): String =
    if (daysAhead == 1) {
        context.getString(R.string.widget_tomorrow_time, time)
    } else {
        "${context.getString(weekdayCaptionRes(weekday))} $time"
    }
