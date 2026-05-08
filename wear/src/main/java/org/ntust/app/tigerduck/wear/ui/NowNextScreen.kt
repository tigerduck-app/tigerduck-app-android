package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding
import java.util.concurrent.TimeUnit

@Composable
fun NowNextScreen(snapshot: WatchSnapshot) {
    val pad = LocalScreenPadding.current
    ScreenScaffold {
        if (snapshot.syncedAtMs == null) {
            EmptyStateMessage(text = stringResource(R.string.watch_open_phone_to_sync), openPhoneOnTap = true)
            return@ScreenScaffold
        }
        if (snapshot.courses.isEmpty()) {
            EmptyStateMessage(
                text = if (snapshot.loggedIn) stringResource(R.string.watch_no_courses_synced)
                       else stringResource(R.string.watch_open_phone_to_sync)
            )
            return@ScreenScaffold
        }

        val (weekday, minuteOfDay) = currentTaipeiTick()
        val result = NextClassResolver.resolve(snapshot.courses, weekday, minuteOfDay)

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = pad),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text(stringResource(R.string.watch_now_next_title)) }
            when (result) {
                is NextClassResult.Ongoing -> OngoingCard(result, minuteOfDay)
                is NextClassResult.NextToday -> NextTodayCard(result, minuteOfDay)
                is NextClassResult.NextFuture -> NextFutureCard(result, weekday)
                NextClassResult.Empty -> Text(stringResource(R.string.watch_no_upcoming_classes))
            }
            Spacer(Modifier.height(8.dp))
            StaleBanner(snapshot.syncedAtMs)
        }
    }
}

@Composable
private fun OngoingCard(result: NextClassResult.Ongoing, minuteOfDay: Int) {
    val accent = LocalAccentColor.current
    val span = (result.endMinute - result.startMinute).coerceAtLeast(1)
    val progress = ((minuteOfDay - result.startMinute).toFloat() / span).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.watch_now_ends_at, formatHm(result.endMinute)),
            color = accent,
        )
        Text(
            text = result.course.courseName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${result.course.classroom} · ${result.course.instructor}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        // Progress through the current period: ticks once a minute via the
        // parent's currentTaipeiTick(), so the bar fills smoothly enough for
        // a watch UI without burning a 1-second timer.
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
    result.nextToday?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.watch_next_label, it.course.courseName, formatHm(it.startMinute)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NextTodayCard(result: NextClassResult.NextToday, minuteOfDay: Int) {
    val minsUntil = result.startMinute - minuteOfDay
    Text(text = result.course.courseName, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(text = "${result.course.classroom} · ${result.course.instructor}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        text = if (minsUntil in 1..59)
            stringResource(R.string.watch_starts_in_minutes, minsUntil)
        else
            stringResource(R.string.watch_starts_at, formatHm(result.startMinute)),
        color = LocalAccentColor.current,
    )
}

@Composable
private fun NextFutureCard(result: NextClassResult.NextFuture, todayWeekday: Int) {
    Text(text = result.course.courseName, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(text = "${result.course.classroom} · ${result.course.instructor}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    val label = if (result.daysAhead == 1) {
        stringResource(R.string.watch_tomorrow_at, formatHm(result.startMinute))
    } else {
        val targetWeekday = ((todayWeekday - 1 + result.daysAhead) % 7) + 1
        stringResource(
            R.string.watch_weekday_at,
            weekdayShortName(targetWeekday),
            formatHm(result.startMinute),
        )
    }
    Text(text = label, color = LocalAccentColor.current)
}

@Composable
private fun StaleBanner(syncedAtMs: Long) {
    val now = AppClock.nowMillis()
    val ageMs = now - syncedAtMs
    if (ageMs < TimeUnit.HOURS.toMillis(6)) return
    val pretty = if (ageMs < TimeUnit.DAYS.toMillis(1)) {
        stringResource(
            R.string.watch_relative_hours_ago_short,
            TimeUnit.MILLISECONDS.toHours(ageMs).toInt(),
        )
    } else {
        stringResource(
            R.string.watch_relative_days_ago_short,
            TimeUnit.MILLISECONDS.toDays(ageMs).toInt(),
        )
    }
    Text(
        text = stringResource(R.string.watch_last_synced_relative, pretty),
        color = Color.Gray,
    )
}

@Composable
private fun currentTaipeiTick(): Pair<Int, Int> {
    var tick by remember { mutableStateOf(taipeiNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = taipeiNow()
            delay(TimeUnit.SECONDS.toMillis(60))
        }
    }
    return tick
}

private fun taipeiNow(): Pair<Int, Int> {
    val now = AppClock.localDateTime()
    val weekday = now.dayOfWeek.value
    val minuteOfDay = now.hour * 60 + now.minute
    return weekday to minuteOfDay
}

private fun formatHm(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return "%02d:%02d".format(h, m)
}

@Composable
private fun weekdayShortName(weekday: Int): String = stringResource(
    when (weekday) {
        1 -> R.string.weekday_mon_short
        2 -> R.string.weekday_tue_short
        3 -> R.string.weekday_wed_short
        4 -> R.string.weekday_thu_short
        5 -> R.string.weekday_fri_short
        6 -> R.string.weekday_sat_short
        else -> R.string.weekday_sun_short
    }
)
