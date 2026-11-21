package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding
import org.ntust.app.tigerduck.wear.ui.theme.WatchCardStyle
import org.ntust.app.tigerduck.wear.ui.theme.courseCardColors
import org.ntust.app.tigerduck.wear.ui.theme.wearCourseColor
import java.time.LocalDate
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
                    item {
                        ClassCard(
                            headline = stringResource(R.string.watch_now), course = result.course,
                            startMinute = result.startMinute, endMinute = result.endMinute,
                            classroom = result.classroom, change = result.change, style = cardStyle, countdownOn = today,
                        )
                    }
                    result.nextToday?.let { next -> item { NextCard(next, cardStyle) } }
                }

                is NextClassResult.NextToday -> item { NextCard(result, cardStyle) }

                is NextClassResult.NextFuture -> item {
                    ClassCard(
                        headline = stringResource(nextHeadlineRes(result.daysAhead, result.weekday)),
                        course = result.course, startMinute = result.startMinute, endMinute = result.endMinute,
                        classroom = result.classroom, change = result.change, style = cardStyle,
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

/** A class next today, under the one going on or alone, headed "Next". */
@Composable
private fun NextCard(next: NextClassResult.NextToday, style: WatchCardStyle) = ClassCard(
    headline = stringResource(nextHeadlineRes(daysAhead = 0, next.weekday)), course = next.course,
    startMinute = next.startMinute, endMinute = next.endMinute, classroom = next.classroom, change = next.change,
    style = style,
)

/**
 * A class on Now & Next, as the iPhone's `ClassCard`: its caption, the [headline] and its full
 * range, read as words by a screen reader; its name; its room alone ([roomLine]); and, for the
 * class going on [countdownOn] that day, its progress and the time left ([ClassCountdown]). What
 * the school changed of its times or room is marked in [ChangeInk].
 */
@Composable
internal fun ClassCard(
    headline: String,
    course: Course,
    startMinute: Int,
    endMinute: Int,
    /** The room of the block the card is about, not every room of the course that day. */
    classroom: String,
    change: WatchClassChange?,
    style: WatchCardStyle,
    countdownOn: LocalDate? = null,
) {
    val colors = courseCardColors(style, wearCourseColor(course), tintAlpha = 0.22f, muted = false)
    val spoken = "$headline, ${stringResource(R.string.a11y_time_range, clockText(startMinute), clockText(endMinute))}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = cardCaption(headline, startMinute, endMinute),
            modifier = Modifier.semantics { contentDescription = spoken },
            color = if (change?.start != null || change?.end != null) ChangeInk else colors.secondaryText,
        )
        Text(
            text = course.displayName,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        roomLine(classroom, moved = change?.room != null, courseInk = colors.emphasis)?.let {
            Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (countdownOn != null) {
            val dayStart = countdownOn.atStartOfDay(TaipeiClock.zone).toInstant().toEpochMilli()
            ClassCountdown(
                startMs = dayStart + TimeUnit.MINUTES.toMillis(startMinute.toLong()),
                endMs = dayStart + TimeUnit.MINUTES.toMillis(endMinute.toLong()),
                colors = ProgressIndicatorDefaults.colors(indicatorColor = colors.progress, trackColor = colors.progressTrack),
                textColor = colors.text,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
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
