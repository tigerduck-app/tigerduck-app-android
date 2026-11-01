package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding
import org.ntust.app.tigerduck.wear.ui.theme.WatchCardStyle
import org.ntust.app.tigerduck.wear.ui.theme.courseCardColors
import org.ntust.app.tigerduck.wear.ui.theme.wearCourseColor
import java.util.concurrent.TimeUnit

@Composable
fun NowNextScreen(snapshot: WatchSnapshot) {
    val pad = LocalScreenPadding.current
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
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

        val (today, minuteOfDay) = currentTaipeiTick()
        val result = snapshot.nextClass(today, minuteOfDay)
        val cardStyle = WatchCardStyle.fromVisualPreset(snapshot.visualPreset)

        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text(stringResource(R.string.watch_now_next_title)) } }
            when (result) {
                is NextClassResult.Ongoing -> {
                    item { OngoingCard(result, minuteOfDay, cardStyle) }
                    result.nextToday?.let { next ->
                        item {
                            NextCard(
                                course = next.course,
                                classroom = next.classroom,
                                change = next.change,
                                statusText = nextStatusText(next.startMinute, minuteOfDay),
                                titleResId = R.string.watch_next,
                                style = cardStyle,
                            )
                        }
                    }
                }

                is NextClassResult.NextToday -> item {
                    NextCard(
                        course = result.course,
                        classroom = result.classroom,
                        change = result.change,
                        statusText = nextStatusText(result.startMinute, minuteOfDay),
                        titleResId = null,
                        style = cardStyle,
                    )
                }

                is NextClassResult.NextFuture -> item {
                    NextCard(
                        course = result.course,
                        classroom = result.classroom,
                        change = result.change,
                        statusText = futureStatusText(result.daysAhead, result.startMinute, result.weekday),
                        titleResId = null,
                        style = cardStyle,
                    )
                }

                NextClassResult.Empty -> item {
                    Text(stringResource(R.string.watch_no_upcoming_classes))
                }
            }
            item { StaleBanner(snapshot.syncedAtMs) }
        }
    }
}

@Composable
private fun OngoingCard(result: NextClassResult.Ongoing, minuteOfDay: Int, style: WatchCardStyle) {
    val colors = courseCardColors(style, wearCourseColor(result.course), tintAlpha = 0.22f, muted = false)
    val span = (result.endMinute - result.startMinute).coerceAtLeast(1)
    val progress = ((minuteOfDay - result.startMinute).toFloat() / span).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.watch_now_ends_at, formatHm(result.endMinute)),
            color = if (result.change?.end != null) ChangeInk else colors.emphasis,
        )
        Text(
            text = result.course.displayName,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = classroomLine(result.classroom, result.course.instructor, moved = result.change?.room != null),
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        FollowingProgressIndicator(
            value = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            colors = ProgressIndicatorDefaults.colors(indicatorColor = colors.progress, trackColor = colors.progressTrack),
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
    /** The room of the block the card is about, not every room of the course that day. */
    classroom: String,
    /** What the school changed of the class, its time and room marked in [ChangeInk]. */
    change: WatchClassChange?,
    statusText: String,
    titleResId: Int?,
    style: WatchCardStyle,
) {
    val colors = courseCardColors(style, wearCourseColor(course), tintAlpha = 0.22f, muted = false)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (titleResId != null) {
            Text(text = stringResource(titleResId), color = colors.emphasis)
        }
        Text(
            text = course.displayName,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = classroomLine(classroom, course.instructor, moved = change?.room != null),
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = statusText,
            color = if (change?.start != null) ChangeInk else colors.emphasis,
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
private fun futureStatusText(daysAhead: Int, startMinute: Int, weekday: Int): String =
    if (daysAhead == 1) {
        stringResource(R.string.watch_tomorrow_at, formatHm(startMinute))
    } else {
        stringResource(
            R.string.watch_weekday_at,
            stringResource(weekdayCaptionRes(weekday)),
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
