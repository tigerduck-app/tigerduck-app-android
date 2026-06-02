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
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding
import org.ntust.app.tigerduck.wear.ui.theme.wearCourseColor
import java.util.concurrent.TimeUnit

@Composable
fun NowNextScreen(snapshot: WatchSnapshot) {
    val pad = LocalScreenPadding.current
    ScreenScaffold {
        if (snapshot.syncedAtMs == null) {
            EmptyStateMessage(
                text = stringResource(R.string.watch_open_phone_to_sync),
                openPhoneOnTap = true
            )
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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = pad),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text(stringResource(R.string.watch_now_next_title)) }
            when (result) {
                is NextClassResult.Ongoing -> {
                    // iOS NowNextView stacks Now + Next as two separate cards
                    // when both exist, instead of folding the next-today
                    // preview into the ongoing card as a single line.
                    OngoingCard(result, minuteOfDay)
                    result.nextToday?.let { next ->
                        NextCard(
                            course = next.course,
                            weekday = next.weekday,
                            statusText = nextStatusText(next.startMinute, minuteOfDay),
                            titleResId = R.string.watch_next,
                        )
                    }
                }

                is NextClassResult.NextToday -> NextCard(
                    course = result.course,
                    weekday = result.weekday,
                    statusText = nextStatusText(result.startMinute, minuteOfDay),
                    titleResId = null,
                )

                is NextClassResult.NextFuture -> NextCard(
                    course = result.course,
                    weekday = result.weekday,
                    statusText = futureStatusText(result.daysAhead, result.startMinute, weekday),
                    titleResId = null,
                )

                NextClassResult.Empty -> Text(stringResource(R.string.watch_no_upcoming_classes))
            }
            StaleBanner(snapshot.syncedAtMs)
        }
    }
}

@Composable
private fun OngoingCard(result: NextClassResult.Ongoing, minuteOfDay: Int) {
    val courseColor = wearCourseColor(result.course)
    val span = (result.endMinute - result.startMinute).coerceAtLeast(1)
    val progress = ((minuteOfDay - result.startMinute).toFloat() / span).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(courseColor.copy(alpha = 0.22f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.watch_now_ends_at, formatHm(result.endMinute)),
            color = courseColor,
        )
        Text(
            text = result.course.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${result.course.classroom(result.weekday)} · ${result.course.instructor}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

/**
 * Unified next-class card used for both [NextClassResult.NextToday] and
 * [NextClassResult.NextFuture], as well as the "Next" follow-up under an
 * ongoing course. [titleResId] surfaces the kind label ("Next") only when
 * the card is paired with another above it; the standalone next-today /
 * next-future cards skip the label so the time line carries that role —
 * matches the iOS ClassCard layout.
 */
@Composable
private fun NextCard(
    course: Course,
    weekday: Int,
    statusText: String,
    titleResId: Int?,
) {
    val courseColor = wearCourseColor(course)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(courseColor.copy(alpha = 0.22f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (titleResId != null) {
            Text(text = stringResource(titleResId), color = courseColor)
        }
        Text(
            text = course.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${course.classroom(weekday)} · ${course.instructor}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = statusText,
            color = courseColor,
        )
    }
}

@Composable
private fun nextStatusText(startMinute: Int, minuteOfDay: Int): String {
    val minsUntil = startMinute - minuteOfDay
    return if (minsUntil in 1..59) {
        stringResource(R.string.watch_starts_in_minutes, minsUntil)
    } else {
        stringResource(R.string.watch_starts_at, formatHm(startMinute))
    }
}

@Composable
private fun futureStatusText(daysAhead: Int, startMinute: Int, todayWeekday: Int): String =
    if (daysAhead == 1) {
        stringResource(R.string.watch_tomorrow_at, formatHm(startMinute))
    } else {
        val targetWeekday = ((todayWeekday - 1 + daysAhead) % 7) + 1
        stringResource(
            R.string.watch_weekday_at,
            weekdayShortName(targetWeekday),
            formatHm(startMinute),
        )
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
