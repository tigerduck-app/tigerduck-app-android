package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.debug.OutsideApp
import org.ntust.app.tigerduck.debug.PretendMissingApps
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets

/** The switches that make the app act as on a phone without some of the apps it hands things to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutsideAppsDebugScreen(onBack: () -> Unit) {
    val missing by PretendMissingApps.missing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text("Missing apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Pretend these aren't installed. Starting one fails as it would on a phone without it, so " +
                    "the app's own fallback runs; a chooser opens with nothing to offer. Kept until turned off.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutsideApp.entries.forEach { app ->
                val checked = app in missing
                // The whole row is the switch, so TalkBack reads which app with its state.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            role = Role.Switch,
                            onValueChange = { PretendMissingApps.setMissing(app, it) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.missing, style = MaterialTheme.typography.titleSmall)
                        Text(app.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
}
