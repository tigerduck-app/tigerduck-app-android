package org.ntust.app.tigerduck.wear.ui

import android.content.Context
import androidx.annotation.StringRes
import org.ntust.app.tigerduck.wear.R

/** [minuteOfDay] as a clock reads it, "08:10". */
internal fun clockText(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** A class's times as a range, "10:20–12:10", as iOS `TimeRange.text`. */
internal fun rangeText(startMinute: Int, endMinute: Int): String = "${clockText(startMinute)}–${clockText(endMinute)}"

/**
 * The range in words, "10:20 to 12:10", as iOS `TimeRange.spoken`: a screen reader does not
 * reliably read the dash between two times as "to".
 */
internal fun spokenRange(context: Context, startMinute: Int, endMinute: Int): String =
    context.getString(R.string.a11y_time_range, clockText(startMinute), clockText(endMinute))

/**
 * What a class shown next is headed with on Now & Next: "Next" for one today, "Tomorrow", else
 * the [weekday] it meets, as iOS `ClassDay.headline`. A class today is never headed "Today".
 */
@StringRes
internal fun nextHeadlineRes(daysAhead: Int, weekday: Int): Int = when (daysAhead) {
    0 -> R.string.watch_next
    1 -> R.string.widget_tomorrow
    else -> weekdayCaptionRes(weekday)
}

/** A Now & Next card's caption: its [headline], then the class's range, "NOW · 10:20–12:10". */
internal fun cardCaption(headline: String, startMinute: Int, endMinute: Int): String =
    "$headline · ${rangeText(startMinute, endMinute)}"
