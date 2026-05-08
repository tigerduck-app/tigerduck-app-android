package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.TodayClassEntry
import org.ntust.app.tigerduck.shared.TodayClassStatus
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

@Composable
fun TodayScreen(
    snapshot: WatchSnapshot,
    onRowClick: (String) -> Unit,
) {
    val pad = LocalScreenPadding.current
    // Tick once a minute so a class transitioning to Ended (and losing its
    // Ongoing highlight) refreshes without the user navigating away.
    val (weekday, minuteOfDay) = currentTaipeiTick()
    val entries = NextClassResolver.todaysClasses(snapshot.courses, weekday, minuteOfDay)
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ListHeader { Text(stringResource(R.string.calendar_today)) }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.widget_no_classes_today),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(entries, key = { it.course.courseNo + it.firstPeriodId }) { entry ->
                    TodayRow(entry = entry, onClick = { onRowClick(entry.course.courseNo) })
                }
            }
        }
    }
}

@Composable
private fun TodayRow(entry: TodayClassEntry, onClick: () -> Unit) {
    val accent = LocalAccentColor.current
    val baseAlpha = if (entry.status == TodayClassStatus.Ended) 0.4f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (entry.status == TodayClassStatus.Ongoing) accent.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(baseAlpha),
    ) {
        if (entry.status == TodayClassStatus.Ongoing) {
            Spacer(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(accent)
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = entry.course.courseName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${entry.course.classroom} · ${formatHm(entry.startMinute)}–${formatHm(entry.endMinute)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatHm(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
