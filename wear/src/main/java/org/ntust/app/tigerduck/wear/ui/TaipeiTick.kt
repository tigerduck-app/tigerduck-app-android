package org.ntust.app.tigerduck.wear.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.LocalAmbientModeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Returns (date, minuteOfDay) and recomposes at the start of each minute so any screen
 * driving its UI off the wall clock — class status, which class is next, etc. —
 * transitions on its own, right as a class starts or ends, without the user navigating
 * away. The date, not just the weekday, is what tells a quiet day apart.
 */
@Composable
internal fun currentTaipeiTick(): Pair<LocalDate, Int> {
    val millis = appClockTicks(TimeUnit.MINUTES.toMillis(1))
    return remember(millis) {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), TaipeiClock.zone)
        now.toLocalDate() to now.hour * 60 + now.minute
    }
}

/**
 * The app clock's time in milliseconds, read again at each whole [periodMs] of it, so a reader
 * changes right at a class's start or end, or at each second of a countdown.
 *
 * It ticks only while [lifecycle] is resumed, and the watch app doesn't take Always On: in
 * ambient mode the system pauses the activity and shows the watch face, so no timer runs then,
 * nor in the background. Should the app take Always On through [LocalAmbientModeManager], ambient
 * mode reads the clock at the system's ambient tick, about once a minute, in place of this timer.
 */
@Composable
internal fun appClockTicks(
    periodMs: Long,
    lifecycle: Lifecycle? = (LocalActivity.current as? LifecycleOwner)?.lifecycle,
): Long {
    var now by remember { mutableLongStateOf(AppClock.nowMillis()) }
    val ambient = LocalAmbientModeManager.current
    if (ambient != null && ambient.currentAmbientMode is AmbientMode.Ambient) {
        LaunchedEffect(ambient) {
            now = AppClock.nowMillis()
            while (true) ambient.withAmbientTick { now = AppClock.nowMillis() }
        }
    } else {
        LaunchedEffect(lifecycle, periodMs) {
            val resumed = lifecycle?.currentStateFlow?.map { it.isAtLeast(Lifecycle.State.RESUMED) } ?: flowOf(true)
            // A pause cancels the loop; a resume reads the clock again at once.
            resumed.distinctUntilChanged().collectLatest { inUse ->
                if (!inUse) return@collectLatest
                while (true) {
                    now = AppClock.nowMillis()
                    delay(untilNextTick(now, periodMs))
                }
            }
        }
    }
    return now
}

/** How long from [nowMs] until the clock reaches its next whole [periodMs]. */
internal fun untilNextTick(nowMs: Long, periodMs: Long): Long = periodMs - Math.floorMod(nowMs, periodMs)
