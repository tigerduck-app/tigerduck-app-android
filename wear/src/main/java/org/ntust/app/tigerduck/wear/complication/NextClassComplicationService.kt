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
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.MainActivity
import org.ntust.app.tigerduck.wear.data.ScheduleRepository
import org.ntust.app.tigerduck.wear.data.WatchSnapshot

class NextClassComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val data = runBlocking {
            val snapshot = ScheduleRepository.get(this@NextClassComplicationService).flow.first()
            renderComplication(snapshot, request.complicationType)
        }
        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("CN").build(),
            contentDescription = PlainComplicationText.Builder("Next class").build(),
        ).setTitle(PlainComplicationText.Builder("14:30").build()).build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder("T2-103").build(),
            contentDescription = PlainComplicationText.Builder("Next class").build(),
        ).setTitle(PlainComplicationText.Builder("14:30 · CN").build()).build()
        else -> null
    }

    private fun renderComplication(
        snapshot: WatchSnapshot,
        type: ComplicationType,
    ): ComplicationData {
        val now = AppClock.localDateTime()
        val weekday = now.dayOfWeek.value
        val minuteOfDay = now.hour * 60 + now.minute

        if (snapshot.syncedAtMs == null || snapshot.courses.isEmpty()) {
            return NoDataComplicationData()
        }

        val tap = launchAppPendingIntent()
        return when (val r = NextClassResolver.resolve(snapshot.courses, weekday, minuteOfDay)) {
            is NextClassResult.Ongoing -> when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(truncate(r.course.displayName, 7)).build(),
                    contentDescription = PlainComplicationText.Builder(r.course.displayName).build(),
                ).setTitle(PlainComplicationText.Builder("NOW").build()).setTapAction(tap).build()
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(r.course.classroom(r.weekday)).build(),
                    contentDescription = PlainComplicationText.Builder(r.course.displayName).build(),
                ).setTitle(PlainComplicationText.Builder("NOW · ${truncate(r.course.displayName, 7)}").build())
                  .setTapAction(tap).build()
                else -> NoDataComplicationData()
            }
            is NextClassResult.NextToday -> shortOrLong(
                type, r.course.displayName, r.course.classroom(r.weekday),
                titleShort = formatHm(r.startMinute),
                titleLong = "${formatHm(r.startMinute)} · ${truncate(r.course.displayName, 7)}",
                tap = tap,
            )
            is NextClassResult.NextFuture -> shortOrLong(
                type, r.course.displayName, r.course.classroom(r.weekday),
                titleShort = formatHm(r.startMinute),
                titleLong = "${formatHm(r.startMinute)} · +${r.daysAhead}d",
                tap = tap,
            )
            NextClassResult.Empty -> NoDataComplicationData()
        }
    }

    private fun shortOrLong(
        type: ComplicationType,
        courseName: String,
        classroom: String,
        titleShort: String,
        titleLong: String,
        tap: PendingIntent,
    ): ComplicationData = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(truncate(courseName, 7)).build(),
            contentDescription = PlainComplicationText.Builder(courseName).build(),
        ).setTitle(PlainComplicationText.Builder(titleShort).build()).setTapAction(tap).build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(classroom).build(),
            contentDescription = PlainComplicationText.Builder(courseName).build(),
        ).setTitle(PlainComplicationText.Builder(titleLong).build()).setTapAction(tap).build()
        else -> NoDataComplicationData()
    }

    private fun launchAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun formatHm(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max)

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, NextClassComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}
