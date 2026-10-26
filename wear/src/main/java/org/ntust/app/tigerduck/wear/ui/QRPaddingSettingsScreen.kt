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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.SchedulePersistenceHolder
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

/**
 * Standalone padding for the fullscreen library QR (page 0). Mirrors the
 * Screen padding screen's UX (rotary + ± buttons), but previews the boundary
 * as four 90° corner brackets sitting on the actual QR's four corners — the
 * fullscreen QR is a square, not a round ring, so corner marks match what
 * the user will see.
 */
@Composable
fun QRPaddingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SchedulePersistenceHolder.get(context) }
    val pad = LocalScreenPadding.current
    val scope = rememberCoroutineScope()

    val current by repo.qrPaddingDpFlow.collectAsState(
        initial = SchedulePersistence.DEFAULT_QR_PADDING_DP
    )

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun setValue(next: Int) {
        val clamped = next.coerceIn(
            SchedulePersistence.MIN_QR_PADDING_DP,
            SchedulePersistence.MAX_QR_PADDING_DP,
        )
        if (clamped == current) return
        scope.launch { repo.writeQrPaddingDp(clamped) }
    }

    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize()) {
            // Live preview: four L-shaped corner marks at the corners of the
            // fullscreen QR for the *current* padding. Adjusting the padding
            // pulls the marks inward; this is the actual square the QR will
            // occupy when the user double-taps to enlarge.
            QrCornerBrackets(currentDp = current, modifier = Modifier.fillMaxSize())

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
                Text(stringResource(R.string.watch_qr_padding))
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
                    PaddingBar(
                        valueDp = current,
                        minDp = SchedulePersistence.MIN_QR_PADDING_DP,
                        maxDp = SchedulePersistence.MAX_QR_PADDING_DP,
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
 * Renders the four L-shaped corner marks at the corners of the actual
 * fullscreen QR square for the given QR padding. The square is sized by the
 * same formula [qrFullscreenSideDp] uses, so
 * the preview matches what the user will see when they double-tap the QR.
 */
@Composable
private fun QrCornerBrackets(currentDp: Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    val minSideDp = minOf(config.screenWidthDp, config.screenHeightDp)
    val sideDp = qrFullscreenSideDp(minSideDp, currentDp, config.isScreenRound)
    val sidePx = with(density) { sideDp.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    val legPx = with(density) { 14.dp.toPx() }

    Canvas(modifier = modifier) {
        drawQrCornerBrackets(sidePx, legPx, strokePx, color)
    }
}

/**
 * [QrCornerBrackets]' marks: at each corner of the centered square of side
 * [sidePx], two legs of [legPx] forming a 90° "L", in [color].
 */
internal fun DrawScope.drawQrCornerBrackets(
    sidePx: Float,
    legPx: Float,
    strokePx: Float,
    color: Color,
) {
    val half = sidePx / 2f
    val square = Rect(
        left = size.width / 2f - half,
        top = size.height / 2f - half,
        right = size.width / 2f + half,
        bottom = size.height / 2f + half,
    )
    fun leg(from: Offset, to: Offset) =
        drawLine(color, from, to, strokeWidth = strokePx, cap = StrokeCap.Round, alpha = 0.85f)
    with(square) {
        leg(topLeft, Offset(left + legPx, top))
        leg(topLeft, Offset(left, top + legPx))
        leg(topRight, Offset(right - legPx, top))
        leg(topRight, Offset(right, top + legPx))
        leg(bottomLeft, Offset(left + legPx, bottom))
        leg(bottomLeft, Offset(left, bottom - legPx))
        leg(bottomRight, Offset(right - legPx, bottom))
        leg(bottomRight, Offset(right, bottom - legPx))
    }
}
