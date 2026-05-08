package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.developer_time_override_section),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.developer_use_fake_time), modifier = Modifier.weight(1f))
                Switch(
                    checked = state.overrideEnabled,
                    onCheckedChange = viewModel::setOverrideEnabled,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.developer_date_label), modifier = Modifier.weight(1f))
                TextButton(
                    enabled = state.overrideEnabled,
                    onClick = { showDatePicker = true },
                ) {
                    Text(
                        LocalDate.of(state.draftYear, state.draftMonth, state.draftDay)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.getDefault())),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.developer_time_label), modifier = Modifier.weight(1f))
                TextButton(
                    enabled = state.overrideEnabled,
                    onClick = { showTimePicker = true },
                ) {
                    Text("%02d:%02d".format(state.draftHour, state.draftMinute))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.developer_mode_label), modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = state.draftFrozen,
                        onClick = { viewModel.setFrozen(true) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = state.overrideEnabled,
                    ) {
                        Text(stringResource(R.string.developer_mode_frozen))
                    }
                    SegmentedButton(
                        selected = !state.draftFrozen,
                        onClick = { viewModel.setFrozen(false) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = state.overrideEnabled,
                    ) {
                        Text(stringResource(R.string.developer_mode_ticking))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "%s: %s".format(
                    stringResource(R.string.developer_effective_now),
                    state.effectiveNow.format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd E HH:mm:ss", Locale.getDefault()),
                    ),
                ),
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = viewModel::reset) {
                    Text(stringResource(R.string.action_reset))
                }
                Button(
                    onClick = viewModel::apply,
                    enabled = state.overrideEnabled,
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate
                .of(state.draftYear, state.draftMonth, state.draftDay)
                .atStartOfDay(ZoneId.of("Asia/Taipei"))
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dpState.selectedDateMillis
                    if (millis != null) {
                        val ld = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        viewModel.setDate(ld.year, ld.monthValue, ld.dayOfMonth)
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = dpState)
        }
    }

    if (showTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = state.draftHour,
            initialMinute = state.draftMinute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                TimePicker(state = tpState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showTimePicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = {
                        viewModel.setTime(tpState.hour, tpState.minute)
                        showTimePicker = false
                    }) { Text(stringResource(R.string.action_done)) }
                }
            }
        }
    }
}
