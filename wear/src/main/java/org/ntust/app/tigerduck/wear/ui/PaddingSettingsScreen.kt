package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.ScheduleRepository
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

@Composable
fun PaddingSettingsScreen() {
    val context = LocalContext.current
    val repo = remember(context) { ScheduleRepository.get(context) }
    val pad = LocalScreenPadding.current
    val scope = rememberCoroutineScope()

    val current by repo.paddingDpFlow.collectAsState(initial = SchedulePersistence.DEFAULT_PADDING_DP)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun setValue(next: Int) {
        val clamped = next.coerceIn(SchedulePersistence.MIN_PADDING_DP, SchedulePersistence.MAX_PADDING_DP)
        if (clamped == current) return
        scope.launch { repo.writePaddingDp(clamped) }
    }

    val range = (SchedulePersistence.MAX_PADDING_DP - SchedulePersistence.MIN_PADDING_DP).toFloat()
    val progress = if (range == 0f) 0f else
        (current - SchedulePersistence.MIN_PADDING_DP).toFloat() / range

    ScreenScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = pad)
                // The crown emits vertical scroll events; positive = clockwise.
                // Treat one notch (~120 px on most devices) as one dp step.
                .onRotaryScrollEvent { event ->
                    val steps = (event.verticalScrollPixels / 120f).toInt()
                    if (steps != 0) {
                        setValue(current + steps)
                        true
                    } else false
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ListHeader { Text(stringResource(R.string.padding_setting_title)) }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.padding_value_dp, current))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { setValue(current - 1) }) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = stringResource(R.string.padding_decrease_cd),
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(8.dp),
                )
                IconButton(onClick = { setValue(current + 1) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.padding_increase_cd),
                    )
                }
            }
        }
    }
}
