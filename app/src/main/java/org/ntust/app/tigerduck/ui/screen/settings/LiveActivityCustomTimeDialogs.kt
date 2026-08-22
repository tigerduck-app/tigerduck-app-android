// The two "自訂" entry dialogs behind the lead-time sliders: minutes-only for
// class prep, hours-and-minutes for assignment deadlines. Both keep their
// text fields as raw strings and only surface a value once it parses inside
// the caller's range, so a half-typed number never reaches preferences.
//
// They use a bare Dialog rather than AlertDialog because the text fields need
// imePadding and a scroll container — an AlertDialog would put the confirm
// button under the keyboard on short screens.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@Composable
internal fun CustomMinutesDialog(
    title: String,
    description: String,
    initialMinutes: Int,
    minMinutes: Int,
    maxMinutes: Int,
    unitHint: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in minMinutes..maxMinutes }
    val valid = parsed != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { new ->
                            text = new.filter { it.isDigit() }.take(5)
                        },
                        label = { Text(unitHint) },
                        singleLine = true,
                        isError = text.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { parsed?.let(onConfirm) }
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.live_activity_settings_range_minutes,
                            minMinutes,
                            maxMinutes
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { parsed?.let(onConfirm) },
                        enabled = valid,
                    ) { Text(stringResource(R.string.action_confirm)) }
                }
            }
        }
    }
}

@Composable
internal fun CustomHoursMinutesDialog(
    title: String,
    description: String,
    initialMinutes: Int,
    minMinutes: Int,
    maxMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hoursText by remember { mutableStateOf((initialMinutes / 60).toString()) }
    var minutesText by remember { mutableStateOf((initialMinutes % 60).toString()) }

    val hours = hoursText.toIntOrNull() ?: -1
    val minutes = minutesText.toIntOrNull() ?: -1
    val total = if (hours >= 0 && minutes in 0..59) hours * 60 + minutes else -1
    val valid = total in minMinutes..maxMinutes

    val maxHours = maxMinutes / 60
    val rangeHint = when {
        minMinutes >= 60 -> stringResource(
            R.string.live_activity_settings_range_hours,
            minMinutes / 60,
            maxHours
        )

        else -> stringResource(
            R.string.live_activity_settings_range_min_to_hours,
            minMinutes,
            maxHours
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = hoursText,
                            onValueChange = { new ->
                                hoursText = new.filter { it.isDigit() }.take(4)
                            },
                            label = { Text(stringResource(R.string.live_activity_settings_hours_unit)) },
                            singleLine = true,
                            isError = !valid && hoursText.isNotEmpty(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { new ->
                                minutesText = new.filter { it.isDigit() }.take(2)
                            },
                            label = { Text(stringResource(R.string.live_activity_settings_minutes_unit)) },
                            singleLine = true,
                            isError = !valid && minutesText.isNotEmpty(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (valid) onConfirm(total) }
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        rangeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (valid) onConfirm(total) },
                        enabled = valid,
                    ) { Text(stringResource(R.string.action_confirm)) }
                }
            }
        }
    }
}
