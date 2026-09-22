package org.ntust.app.tigerduck.liveactivity

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale

/**
 * The countdown for a chip that shows a fixed string instead of running a
 * clock — HyperOS's island, see
 * [org.ntust.app.tigerduck.notification.DeviceSkin.chipShowsStaticText].
 *
 * Whole minutes, rounded up, so the text never runs ahead of the clock: with
 * thirty seconds left it still reads one minute. Nothing redraws it between
 * posts, which is why there is no seconds field to go stale, and why
 * [LiveActivityManager] re-posts at [nextChangeAt].
 */
internal object StaticCountdown {
    private const val MINUTE_MS = 60_000L

    fun minutesLeft(targetMs: Long, nowMs: Long): Long =
        (targetMs - nowMs + MINUTE_MS - 1) / MINUTE_MS

    /** The moment [minutesLeft] next drops by one. */
    fun nextChangeAt(targetMs: Long, nowMs: Long): Long {
        val intoMinute = (targetMs - nowMs) % MINUTE_MS
        return nowMs + if (intoMinute == 0L) MINUTE_MS else intoMinute
    }

    /** "38m" / "38分鐘", "1h 50m" / "1小時50分鐘" — ICU carries every locale the app ships. */
    fun format(minutes: Long, locale: Locale): String {
        val formatter = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
        val hours = Measure(minutes / 60, MeasureUnit.HOUR)
        val rest = Measure(minutes % 60, MeasureUnit.MINUTE)
        return when {
            minutes < 60 -> formatter.format(rest)
            minutes % 60 == 0L -> formatter.format(hours)
            else -> formatter.formatMeasures(hours, rest)
        }
    }
}
