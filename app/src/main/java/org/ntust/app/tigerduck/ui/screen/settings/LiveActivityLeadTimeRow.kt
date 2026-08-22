// The slider row that sets how far ahead of a class or a deadline the Live
// Activity appears, and the duration formatter it labels itself with. Split
// out of LiveActivitySettingsScreen so the screen reads as a list of
// sections rather than a list of sections plus their widgets.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
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
    onCustomClick: () -> Unit,
) {
    // Clamp the slider knob within its visible range even when the user has
    // set a value outside the common range via the 自訂 dialog.
    val displayValue = value.coerceIn(range.start, range.endInclusive)
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
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCustomClick,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    stringResource(R.string.color_picker_custom),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Slider(
            value = displayValue,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}
