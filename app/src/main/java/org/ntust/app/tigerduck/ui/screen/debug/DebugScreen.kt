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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
                title = { Text("Developer") },
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
                "Time override",
                style = MaterialTheme.typography.titleMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use fake time", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.overrideEnabled,
                    onCheckedChange = viewModel::setOverrideEnabled,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Date", modifier = Modifier.weight(1f))
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
                Text("Time", modifier = Modifier.weight(1f))
                TextButton(
                    enabled = state.overrideEnabled,
                    onClick = { showTimePicker = true },
                ) {
                    Text("%02d:%02d".format(state.draftHour, state.draftMinute))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mode", modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = state.draftFrozen,
                        onClick = { viewModel.setFrozen(true) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = state.overrideEnabled,
                    ) {
                        Text("Frozen")
                    }
                    SegmentedButton(
                        selected = !state.draftFrozen,
                        onClick = { viewModel.setFrozen(false) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = state.overrideEnabled,
                    ) {
                        Text("Ticking")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "%s: %s".format(
                    "Effective now",
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
                    Text("Reset")
                }
                Button(
                    onClick = viewModel::apply,
                    enabled = state.overrideEnabled,
                ) {
                    Text("Apply")
                }
            }
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate
                .of(state.draftYear, state.draftMonth, state.draftDay)
                .atStartOfDay(ZoneId.of("UTC"))
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
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
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
            androidx.compose.material3.Surface(
                shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = androidx.compose.material3.AlertDialogDefaults.containerColor,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    TimePicker(state = tpState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text("Cancel")
                        }
                        TextButton(onClick = {
                            viewModel.setTime(tpState.hour, tpState.minute)
                            showTimePicker = false
                        }) { Text("Done") }
                    }
                }
            }
        }
    }
}
