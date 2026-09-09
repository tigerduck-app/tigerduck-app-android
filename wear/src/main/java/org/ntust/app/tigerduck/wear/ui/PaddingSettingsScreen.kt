package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.SchedulePersistenceHolder
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

@Composable
fun PaddingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SchedulePersistenceHolder.get(context) }
    val pad = LocalScreenPadding.current
    val scope = rememberCoroutineScope()

    val current by repo.paddingDpFlow.collectAsState(initial = SchedulePersistence.DEFAULT_PADDING_DP)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun setValue(next: Int) {
        val clamped =
            next.coerceIn(SchedulePersistence.MIN_PADDING_DP, SchedulePersistence.MAX_PADDING_DP)
        if (clamped == current) return
        scope.launch { repo.writePaddingDp(clamped) }
    }

    val range = (SchedulePersistence.MAX_PADDING_DP - SchedulePersistence.MIN_PADDING_DP).toFloat()
    val progress = if (range == 0f) 0f else
        (current - SchedulePersistence.MIN_PADDING_DP).toFloat() / range

    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize()) {
            // Live preview of the padding: a ring drawn just inside the
            // physical screen edge, inset by the current padding value.
            // Anything inside the ring is the area you have for content;
            // the gap between the ring and the watch chassis is the padding.
            PaddingEdgeRing(currentDp = current, modifier = Modifier.fillMaxSize())

            // Back chevron at the top, centered below TimeText to stay clear
            // of the round-screen mask. Swipe-from-left still works too.
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 26.dp)
                    .size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.watch_action_back),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = pad, end = pad, top = 56.dp)
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
                Text(stringResource(R.string.watch_screen_padding))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.watch_padding_value_dp, current))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { setValue(current - 1) }) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.watch_padding_decrease_cd),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp),
                    )
                    IconButton(onClick = { setValue(current + 1) }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.watch_padding_increase_cd),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen ring drawn just inside the physical edge, inset by the
 * current padding amount. Visualises the content boundary on the round
 * chassis: pixels outside the ring would be clipped/masked away by the
 * padding setting.
 */
@Composable
private fun PaddingEdgeRing(currentDp: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccentColor.current
    val density = LocalDensity.current
    val paddingPx = with(density) { currentDp.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = minOf(cx, cy)
        val ringR = (outerR - paddingPx).coerceAtLeast(0f)
        if (ringR > 0f) {
            drawCircle(
                color = accent.copy(alpha = 0.6f),
                radius = ringR,
                center = Offset(cx, cy),
                style = Stroke(width = strokePx),
            )
        }
    }
}
