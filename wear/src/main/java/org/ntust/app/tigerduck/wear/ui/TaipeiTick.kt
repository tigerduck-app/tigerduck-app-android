package org.ntust.app.tigerduck.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Returns (date, minuteOfDay) and recomposes once a minute so any screen
 * driving its UI off the wall clock — class status, "starts in N min", etc. —
 * transitions on its own without the user navigating away. The date, not just
 * the weekday, is what tells a quiet day apart.
 */
@Composable
internal fun currentTaipeiTick(): Pair<LocalDate, Int> {
    var tick by remember { mutableStateOf(taipeiNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = taipeiNow()
            delay(TimeUnit.SECONDS.toMillis(60))
        }
    }
    return tick
}

private fun taipeiNow(): Pair<LocalDate, Int> {
    val now = AppClock.localDateTime()
    val minuteOfDay = now.hour * 60 + now.minute
    return now.toLocalDate() to minuteOfDay
}
