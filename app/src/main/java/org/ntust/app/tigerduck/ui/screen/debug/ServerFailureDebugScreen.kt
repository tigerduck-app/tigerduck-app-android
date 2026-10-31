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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
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
                windowInsets = NoTopBarInsets,
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

            Spacer(Modifier.height(8.dp))
            RefusedPasswordRow()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RefusedPasswordDebugEntryPoint {
    fun storedPasswordLatch(): StoredPasswordLatch
    fun credentialManager(): CredentialManager
}

/**
 * Latches the stored-password refusal the school would, which no demo session can get from it,
 * so the "Automatic sign-in failed" banner can be looked at. Signing in again, or out, lifts it.
 */
@Composable
private fun RefusedPasswordRow() {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, RefusedPasswordDebugEntryPoint::class.java)
    }
    val latch = remember(entryPoint) { entryPoint.storedPasswordLatch() }
    val credentials = remember(entryPoint) { entryPoint.credentialManager() }
    val rejection by latch.rejection.collectAsState()
    val signedIn = credentials.ntustStudentId != null && credentials.ntustPassword != null

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("School refused the stored password", style = MaterialTheme.typography.titleSmall)
            Text(
                if (signedIn) "Stops automatic sign-ins and shows the banner" else "Needs a signed-in account",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = rejection != null,
            enabled = signedIn || rejection != null,
            onCheckedChange = { on ->
                val studentId = credentials.ntustStudentId
                val password = credentials.ntustPassword
                if (!on) {
                    latch.clear()
                } else if (studentId != null && password != null) {
                    latch.markRejected(latch.generation, studentId, password, reason = null)
                }
            },
        )
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
        ServerKind.SCORE_SYSTEM -> "Score System"
        ServerKind.BACKEND -> "TigerDuck Backend"
        ServerKind.LIBRARY -> "Library"
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
