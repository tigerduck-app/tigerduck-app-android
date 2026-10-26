package org.ntust.app.tigerduck.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ProgressIndicatorColors
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentGradient
import org.ntust.app.tigerduck.wear.ui.theme.accentFill

/**
 * Wear's linear progress indicator at [value], following it as it changes.
 *
 * Wear's indicator starts from the first value and then follows only the state
 * it sees read inside its progress lambda. A plain number handed to it as
 * `{ value }` kept it on the first one: the screen padding bar sat near half
 * whatever the padding became, and Now & Next's bar stood still through a class.
 */
@Composable
internal fun FollowingProgressIndicator(
    value: Float,
    modifier: Modifier = Modifier,
    colors: ProgressIndicatorColors = ProgressIndicatorDefaults.colors(),
) {
    val latest by rememberUpdatedState(value)
    LinearProgressIndicator(progress = { latest }, modifier = modifier, colors = colors)
}

/**
 * A padding setting's bar, in the phone's accent, across its gradient when one
 * is picked ([accentFill]): [valueDp] of [minDp]..[maxDp].
 */
@Composable
internal fun PaddingBar(valueDp: Int, minDp: Int, maxDp: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccentColor.current
    val gradient = LocalAccentGradient.current
    // Wear's indicator flips its canvas in a right-to-left language; the gradient keeps to the screen's sides.
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fill = remember(accent, gradient, mirrored) { accentFill(accent, gradient, mirrored) }
    val brush = fill.brush
    FollowingProgressIndicator(
        value = paddingFraction(valueDp, minDp, maxDp),
        modifier = modifier,
        colors = if (brush is SolidColor) {
            ProgressIndicatorDefaults.colors(indicatorColor = brush.value)
        } else {
            ProgressIndicatorDefaults.colors(indicatorBrush = brush)
        },
    )
}

/** How far [valueDp] is through [minDp]..[maxDp], from 0 to 1. */
internal fun paddingFraction(valueDp: Int, minDp: Int, maxDp: Int): Float =
    if (maxDp <= minDp) 0f else ((valueDp - minDp).toFloat() / (maxDp - minDp)).coerceIn(0f, 1f)
