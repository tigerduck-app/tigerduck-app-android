package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.BuildConfig
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding
import java.util.concurrent.TimeUnit

@Composable
fun SettingsListScreen(
    snapshot: WatchSnapshot,
    onPaddingClick: () -> Unit,
    onQrPaddingClick: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val pad = LocalScreenPadding.current
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ListHeader { Text(stringResource(R.string.watch_settings)) }
            }
            item { SignedInRow(loggedIn = snapshot.loggedIn) }
            item { LastSyncRow(syncedAtMs = snapshot.syncedAtMs) }
            item {
                FilledTonalButton(
                    onClick = onPaddingClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.watch_screen_padding),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = onQrPaddingClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.watch_qr_padding),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            item { VersionRow() }
        }
    }
}

@Composable
private fun SignedInRow(loggedIn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (loggedIn) Icons.Filled.CheckCircle else Icons.Filled.Person,
            contentDescription = null,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (loggedIn) R.string.watch_settings_signed_in
                else R.string.watch_settings_signed_out
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LastSyncRow(syncedAtMs: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Filled.Schedule, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(
            text = lastSyncText(syncedAtMs),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun lastSyncText(syncedAtMs: Long?): String {
    if (syncedAtMs == null || syncedAtMs <= 0L) {
        return stringResource(R.string.watch_last_synced_never)
    }
    val ageMs = (currentMinuteTick() - syncedAtMs).coerceAtLeast(0L)
    val pretty = when {
        ageMs < TimeUnit.MINUTES.toMillis(1) -> stringResource(R.string.watch_just_now)
        ageMs < TimeUnit.HOURS.toMillis(1) -> stringResource(
            R.string.watch_relative_minutes_ago_short,
            TimeUnit.MILLISECONDS.toMinutes(ageMs).toInt(),
        )

        ageMs < TimeUnit.DAYS.toMillis(1) -> stringResource(
            R.string.watch_relative_hours_ago_short,
            TimeUnit.MILLISECONDS.toHours(ageMs).toInt(),
        )

        else -> stringResource(
            R.string.watch_relative_days_ago_short,
            TimeUnit.MILLISECONDS.toDays(ageMs).toInt(),
        )
    }
    return stringResource(R.string.watch_last_synced_relative, pretty)
}

@Composable
private fun currentMinuteTick(): Long {
    var nowMs by remember { mutableLongStateOf(AppClock.nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = AppClock.nowMillis()
            delay(TimeUnit.SECONDS.toMillis(60))
        }
    }
    return nowMs
}

@Composable
private fun VersionRow() {
    Text(
        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        color = Color.Gray,
        modifier = Modifier.fillMaxWidth(),
    )
}
