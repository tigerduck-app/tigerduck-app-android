// The slider row that sets how far ahead of a class or a deadline the Live
// Activity appears, and the duration formatter it labels itself with. Split
// out of LiveActivitySettingsScreen so the screen reads as a list of
// sections rather than a list of sections plus their widgets.
//
// v2.1.0 dropped the 自訂 ("custom") entry dialogs that used to sit next to
// the slider (LiveActivityCustomTimeDialogs, now deleted) to match iOS,
// which only ever offered a slider. See LiveActivityPreferences for how a
// value from the wider pre-v2.1.0 range is kept displayable on the now
// narrower slider.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
internal fun formatLeadDuration(totalMinutes: Int): String {
    if (totalMinutes <= 0) return stringResource(R.string.live_activity_settings_minutes_label, 0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> stringResource(R.string.live_activity_settings_minutes_label, m)
        m == 0 -> stringResource(R.string.live_activity_settings_hours_label, h)
        else -> stringResource(R.string.live_activity_settings_hours_minutes_label, h, m)
    }
}

@Composable
internal fun LeadTimeRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    // Clamp the slider knob within its visible range. Not load-bearing for
    // correctness anymore — LiveActivityPreferences now clamps on read — but
    // cheap insurance against ever handing the Slider composable a value
    // outside its own valueRange.
    val displayValue = value.coerceIn(range.start, range.endInclusive)
    // Only a step actually changing gets a haptic pulse, not every pointer-move
    // callback within the same step — Slider's onValueChange fires continuously
    // while dragging, same as TimeSliderViewModel.onDragChanged has to guard
    // against for the home-screen time slider.
    var lastHapticValue by remember { mutableFloatStateOf(displayValue) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = displayValue,
            onValueChange = {
                if (it != lastHapticValue) {
                    lastHapticValue = it
                    // Fixed feel, not the user-tunable HapticScenario.TimeSliderTick
                    // preference used by the home-screen time slider — these two
                    // scenarios are unrelated and shouldn't be coupled through one
                    // shared tunable strength/duration.
                    Haptics.previewCustom(
                        context,
                        HapticScenario.TimeSliderTick.defaultStrengthPct,
                        HapticScenario.TimeSliderTick.defaultDurationMs,
                    )
                }
                onValueChange(it)
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}
