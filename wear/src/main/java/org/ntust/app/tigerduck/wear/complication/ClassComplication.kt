package org.ntust.app.tigerduck.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.wear.ui.rangeText
import org.ntust.app.tigerduck.wear.ui.roomText
import org.ntust.app.tigerduck.wear.ui.spokenRange
import java.time.Instant

/**
 * The class going on, for the ranged value's progress: its start on the real clock, which the
 * platform's time runs on, its length, and how much of it had gone at the moment the data is for.
 * [realStart] is null while the debug clock holds the app's still, when the ring stands too.
 */
internal class ClassSpan(val realStart: Instant?, val seconds: Int, val elapsedSeconds: Int)

/**
 * What the complication shows of one class at one moment, before it is built for a type, as the
 * iPhone's complication families show it. The short and ranged value text is the room, or the
 * course's name with no room ([shortText]). The long text is iOS's rectangular: the name on top,
 * then the room before the range, with the day for a class on a later day. A screen reader hears
 * the range in words, with that day.
 */
internal class ClassComplication(
    val shortText: String,
    val shortTitle: String,
    val longTitle: String,
    val longText: String,
    val description: String,
    val longDescription: String,
    /** The class going on; null for one shown next. */
    val span: ClassSpan?,
) {
    fun dataFor(type: ComplicationType, tap: PendingIntent?): ComplicationData = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(shortText), plain(description))
            .setTitle(plain(shortTitle)).setTapAction(tap).build()

        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(longText), plain(longDescription))
            .setTitle(plain(longTitle)).setTapAction(tap).build()

        ComplicationType.RANGED_VALUE -> rangedValue(tap)
        else -> NoDataComplicationData()
    }

    /**
     * The class going on's progress, from 0 to its length in seconds, as iOS's ring and arc. The
     * platform moves it on its own clock where it can (Wear OS 4 and later), so it doesn't wait
     * for the next update; elsewhere, and while the debug clock is held, it stands at
     * [ClassSpan.elapsedSeconds]. A class shown next
     * has an empty ring and its start as the title.
     */
    private fun rangedValue(tap: PendingIntent?): RangedValueComplicationData {
        val span = span ?: return RangedValueComplicationData.Builder(0f, 0f, 1f, plain(description))
            .setText(plain(shortText)).setTitle(plain(shortTitle)).setTapAction(tap).build()
        val max = span.seconds.toFloat()
        val standing = RangedValueComplicationData.Builder(span.elapsedSeconds.toFloat(), 0f, max, plain(description))
            .setText(plain(shortText)).setTapAction(tap)
        val realStart = span.realStart
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || realStart == null) return standing.build()
        val moving = elapsed(realStart, span.seconds)
        return RangedValueComplicationData.Builder(moving, span.elapsedSeconds.toFloat(), 0f, max, plain(description))
            .setText(plain(shortText)).setTapAction(tap)
            .setDynamicValueInvalidationFallback(standing.build())
            .build()
    }

    /** Seconds gone since [realStart] by the platform's clock, held at [seconds] once the class is over. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun elapsed(realStart: Instant, seconds: Int): DynamicFloat {
        val gone = DynamicInstant.withSecondsPrecision(realStart)
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
            .toIntSeconds()
        return DynamicInt32.onCondition(gone.gt(seconds)).use(seconds).elseUse(gone).asFloat()
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()
}

/**
 * [course]'s class from [startMinute] to [endMinute], [daysAhead] from today on [weekday], in
 * [classroom] ([moved] there by the school). [shortTitle] and [longTitle] are the caller's.
 */
internal fun classComplication(
    context: Context,
    course: Course,
    classroom: String,
    moved: Boolean,
    startMinute: Int,
    endMinute: Int,
    daysAhead: Int,
    weekday: Int,
    shortTitle: String,
    longTitle: String,
    span: ClassSpan? = null,
): ClassComplication {
    val name = course.displayName
    val room = classroom.takeIf { it.isNotBlank() }
    val spoken = dayCaption(context, daysAhead, weekday, spokenRange(context, startMinute, endMinute))
    return ClassComplication(
        shortText = room ?: truncate(name, 7),
        shortTitle = shortTitle,
        longTitle = longTitle,
        longText = listOfNotNull(
            room?.let { roomText(it, moved) },
            dayCaption(context, daysAhead, weekday, rangeText(startMinute, endMinute)),
        ).joinToString(" · "),
        description = "${room ?: name}, $spoken",
        longDescription = listOfNotNull(name, room, spoken).joinToString(", "),
        span = span,
    )
}

/** [text], a time or a range, after the day when the class is on a later one ([laterDayTitle]). */
internal fun dayCaption(context: Context, daysAhead: Int, weekday: Int, text: String): String =
    if (daysAhead == 0) text else laterDayTitle(context, daysAhead, weekday, text)

internal fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max)
