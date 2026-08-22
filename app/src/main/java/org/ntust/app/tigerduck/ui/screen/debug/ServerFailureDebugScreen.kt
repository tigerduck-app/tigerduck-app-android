package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.component.SimulatedFailure

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFailureDebugScreen(onBack: () -> Unit) {
    val failures by ServerFailureSimulator.failures.collectAsState()
    val statuses by ServerStatusTracker.statuses.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server failure simulation") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerKind.entries.forEach { server ->
                ServerFailureRow(
                    server = server,
                    currentFailure = failures[server] ?: SimulatedFailure.NONE,
                    currentStatus = statuses[server] ?: ServerStatus.UNKNOWN,
                    onFailureSelected = { ServerFailureSimulator.setFailure(it, server) },
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ServerFailureSimulator.resetAll() }) {
                    Text("Reset all")
                }
                Button(onClick = { ServerStatusTracker.reset() }) {
                    Text("Reset statuses")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFailureRow(
    server: ServerKind,
    currentFailure: SimulatedFailure,
    currentStatus: ServerStatus,
    onFailureSelected: (SimulatedFailure) -> Unit,
) {
    val label = when (server) {
        ServerKind.MOODLE -> "Moodle"
        ServerKind.COURSE_SELECTION -> "Course Selection"
        ServerKind.BACKEND -> "TigerDuck Backend"
    }
    val statusColor = when (currentStatus) {
        ServerStatus.UNKNOWN -> Color.Gray
        ServerStatus.OK -> Color(0xFF34C759)
        ServerStatus.FAILED -> Color.Red
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = statusColor)
            }
        }

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = currentFailure.label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SimulatedFailure.entries.forEach { failure ->
                    DropdownMenuItem(
                        text = { Text(failure.label) },
                        onClick = {
                            onFailureSelected(failure)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
