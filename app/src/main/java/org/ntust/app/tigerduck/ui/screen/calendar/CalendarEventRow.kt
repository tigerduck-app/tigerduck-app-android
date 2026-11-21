package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.ui.component.STACKED_ROW_FONT_SCALE
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One row of the calendar's day list. With [onOpen], a deadline whose assignment's page would
 * find it ([openableAssignmentIds]), the row is a button with a chevron, as the iPhone's
 * (tigerduck-app#999); every other row opens nothing. From [STACKED_ROW_FONT_SCALE] on, its time
 * or holiday switch goes under the title, at its start, as Home's assignment row does: beside
 * them the title squeezed into a strip a few letters wide.
 */
@Composable
internal fun EventRow(
    event: CalendarEvent,
    notifyOnHoliday: Boolean? = null,
    onNotifyOnHolidayChange: ((Boolean) -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    val stacked = LocalDensity.current.fontScale >= STACKED_ROW_FONT_SCALE
    val open = if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(open)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            // As tall as its texts, so the bar can span them.
            .then(if (stacked) Modifier.height(IntrinsicSize.Min) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .then(if (stacked) Modifier.fillMaxHeight() else Modifier.height(36.dp))
                .clip(RoundedCornerShape(2.dp))
                .background(event.source.color)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(event.source.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = event.source.color
            )
            if (stacked) EventRowTrailing(event, notifyOnHoliday, onNotifyOnHolidayChange, Modifier.padding(top = 4.dp))
        }
        if (!stacked) EventRowTrailing(event, notifyOnHoliday, onNotifyOnHolidayChange)
        if (onOpen != null) {
            Spacer(Modifier.width(8.dp))
            // No description: the row is the button and reads as its text.
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                modifier = Modifier.testTag(EVENT_ROW_CHEVRON_TAG),
            )
        }
    }
}

/** A holiday's reminder switch, or else the row's time; a whole day has none. */
@Composable
private fun EventRowTrailing(
    event: CalendarEvent,
    notifyOnHoliday: Boolean?,
    onNotifyOnHolidayChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.TAIWAN).apply {
            timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
        }
    }
    if (notifyOnHoliday != null && onNotifyOnHolidayChange != null) {
        // Where a timed row has its time: a holiday is a whole day, so it
        // has none to show.
        //
        // The switch is labelled rather than bare: on its own it asked
        // the user to guess what it governed, and a screen reader had
        // only the accessibility string to go on.
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.calendar_holiday_notify_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = notifyOnHoliday,
                onCheckedChange = onNotifyOnHolidayChange,
            )
        }
    } else if (!event.isAllDay) {
        // A whole day has no time worth showing: "00:00" read as a
        // midnight deadline.
        Text(
            timeFmt.format(event.date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = modifier,
        )
    }
}

/** Tags the chevron of a row that opens, for the tests: TalkBack skips it. */
internal const val EVENT_ROW_CHEVRON_TAG = "calendar_event_row_chevron"
