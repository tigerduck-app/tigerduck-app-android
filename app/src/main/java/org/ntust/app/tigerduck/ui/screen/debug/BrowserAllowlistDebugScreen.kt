package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.browser.AllowlistEntry
import org.ntust.app.tigerduck.browser.BrowserAllowlist
import org.ntust.app.tigerduck.browser.BrowserAllowlistStore
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets

/**
 * Settings › Developer › Browser allowlist: which sites the TigerDuck browser
 * loads inside, for testing. Entries added here go on top of the defaults,
 * which stay; "Allow every site" lets any https page in. A release build
 * ignores all of it ([BrowserAllowlistStore]).
 *
 * Debug builds only: the route is registered inside `AppNavigation`'s
 * `BuildConfig.DEBUG` block. Debug UI in this app is written in English and
 * not localized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAllowlistDebugScreen(
    onBack: () -> Unit,
    store: BrowserAllowlistStore = BrowserAllowlistStore.of(LocalContext.current),
) {
    val allowlist by store.allowlist.collectAsState()
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun add() {
        note = when (store.add(draft)) {
            BrowserAllowlistStore.AddResult.ADDED -> null.also { draft = "" }
            BrowserAllowlistStore.AddResult.INVALID -> "Not a domain. Write a host (example.com) or a wildcard (*.example.com), with no scheme, port or path."
            BrowserAllowlistStore.AddResult.ALREADY_LISTED -> "Already on the list."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text("Browser allowlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { store.setAllowEverySite(!allowlist.allowEverySite) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Allow every site", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = allowlist.allowEverySite, onCheckedChange = null)
            }

            Text("Allowed domains", style = MaterialTheme.typography.titleSmall)
            Column {
                allowlist.entries.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider()
                    EntryRow(
                        entry = entry,
                        isDefault = entry in BrowserAllowlist.DEFAULT_ENTRIES,
                        isRedundant = allowlist.isRedundant(entry),
                        onRemove = { store.remove(entry) },
                    )
                }
            }

            Text("Add a domain", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        note = null
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("e.g. example.com or *.example.com") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                Button(onClick = ::add, enabled = draft.isNotBlank()) { Text("Add") }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // Takes the added domains off and turns Allow every site off.
            OutlinedButton(onClick = store::reset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to default", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: AllowlistEntry, isDefault: Boolean, isRedundant: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            val label = listOfNotNull(
                if (isDefault) "Default" else "Custom",
                "Redundant: a wildcard already covers it".takeIf { isRedundant },
            ).joinToString(" · ")
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!isDefault) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${entry.text}") }
        }
    }
}
