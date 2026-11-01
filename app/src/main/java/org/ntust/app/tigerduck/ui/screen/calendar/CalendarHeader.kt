package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.JumpToNowChip
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SyncStatusDot

@Composable
internal fun CalendarSyncDot(isLoading: Boolean, syncedAtMs: Long?) {
    SyncStatusDot(
        // The TigerDuck backend publishes the semester
        // boundaries and holidays this screen now shows,
        // so it belongs in the source list beside Moodle.
        servers = listOf(ServerKind.MOODLE, ServerKind.BACKEND),
        isLoading = isLoading,
        syncedAtMs = syncedAtMs,
    )
}

@Composable
internal fun CalendarTodayChip(onClick: () -> Unit) {
    Spacer(Modifier.width(8.dp))
    JumpToNowChip(label = stringResource(R.string.calendar_today), onClick = onClick)
}
