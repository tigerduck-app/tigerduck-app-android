package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorColors
import androidx.wear.compose.material3.Text
import java.util.concurrent.TimeUnit

/**
 * The class going on's progress bar and the time left until its end, both moving each second
 * while the watch is in use ([appClockTicks]), as the iPhone's `ClassCountdown`. [startMs] and
 * [endMs] are on the app clock. The time left keeps the width of the widest it reads over the
 * class ([widestCountdown]), in tabular digits, so the bar doesn't jump as it counts.
 */
@Composable
internal fun ClassCountdown(
    startMs: Long,
    endMs: Long,
    colors: ProgressIndicatorColors,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val now = appClockTicks(TimeUnit.SECONDS.toMillis(1))
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FollowingProgressIndicator(
            value = classProgress(now, startMs, endMs),
            modifier = Modifier.weight(1f).height(4.dp),
            colors = colors,
        )
        Box(Modifier.testTag(COUNTDOWN_TAG), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = widestCountdown(TimeUnit.MILLISECONDS.toSeconds(endMs - startMs)),
                modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                maxLines = 1,
                softWrap = false,
                style = style,
            )
            Text(text = countdownText(secondsLeft(now, endMs)), color = textColor, maxLines = 1, softWrap = false, style = style)
        }
    }
}

internal const val COUNTDOWN_TAG = "class_countdown"

/** How far [nowMs] is through a class from [startMs] to [endMs], from 0 to 1. */
internal fun classProgress(nowMs: Long, startMs: Long, endMs: Long): Float =
    if (endMs <= startMs) 1f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)

/** Whole seconds from [nowMs] until [endMs], a second begun counting whole; none once it has passed. */
internal fun secondsLeft(nowMs: Long, endMs: Long): Long = ((endMs - nowMs + 999) / 1000).coerceAtLeast(0)

/**
 * [seconds] as the countdown reads them, as iOS's timer text: minutes and seconds under an hour
 * ("59:59"), hours, minutes and seconds from one ("1:50:00").
 */
internal fun countdownText(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/**
 * The widest the countdown of a class [spanSeconds] long reads, each digit a 0, as iOS
 * `ClassCountdown.widest`: "0:00:00" for a class of an hour or more, "00:00" for one of ten
 * minutes up to an hour.
 */
internal fun widestCountdown(spanSeconds: Long): String {
    fun zeros(value: Long) = "0".repeat(value.toString().length)
    return if (spanSeconds >= 3600) zeros(spanSeconds / 3600) + ":00:00" else zeros(spanSeconds / 60) + ":00"
}
