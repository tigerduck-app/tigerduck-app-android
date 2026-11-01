package org.ntust.app.tigerduck.ui.screen.debug

import android.content.Context
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.browser.AllowlistEntry
import org.ntust.app.tigerduck.browser.BrowserAllowlist
import org.ntust.app.tigerduck.browser.BrowserAllowlistStore
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser

/**
 * Settings › Developer › Browser allowlist: which sites the TigerDuck browser
 * loads inside, for testing. Entries added here go on top of the defaults,
 * which stay; "Allow every site" lets any https page in. A release build
 * ignores all of it ([BrowserAllowlistStore]). "Open a page" opens a typed
 * address in a browser that lets every page in, past any allowlist
 * ([InAppBrowser.openAnyPage]).
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
    openPage: (Context, String) -> Boolean = { context, url -> InAppBrowser.openAnyPage(context, url) },
) {
    val context = LocalContext.current
    val allowlist by store.allowlist.collectAsState()
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("") }
    var pageNote by remember { mutableStateOf<String?>(null) }

    fun open() {
        val url = debugPageUrl(page)
        pageNote = when {
            url == null -> "Not a web address."
            openPage(context, url) -> null
            else -> "The browser can't open now: this phone's WebView can't clear its data, or a clearing is owed."
        }
    }

    fun add() {
        note = when (store.add(draft)) {
            BrowserAllowlistStore.AddResult.ADDED -> null.also { draft = "" }
            BrowserAllowlistStore.AddResult.INVALID -> "Not a domain. Write a host (example.com) or a wildcard (*.example.com), with no scheme, port or path."
            BrowserAllowlistStore.AddResult.ALREADY_LISTED -> "Already on the list."
        }
    }

    PageScaffold(
        title = "Browser allowlist",
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Open a page", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = page,
                    onValueChange = {
                        page = it
                        pageNote = null
                    },
                    modifier = Modifier.weight(1f).testTag(OPEN_PAGE_FIELD_TAG),
                    singleLine = true,
                    placeholder = { Text("e.g. example.com or http://10.0.2.2:8080/") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { open() }),
                )
                Button(onClick = ::open, enabled = page.isNotBlank()) { Text("Open") }
            }
            pageNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "In the TigerDuck browser, letting every http and https page in, whatever the list below says. " +
                    "An address with no scheme opens as https. Plain http still loads only where the network " +
                    "security config allows cleartext, which the school's hosts never do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                    modifier = Modifier.weight(1f).testTag(ADD_DOMAIN_FIELD_TAG),
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

internal const val OPEN_PAGE_FIELD_TAG = "browserAllowlist.openPage"
internal const val ADD_DOMAIN_FIELD_TAG = "browserAllowlist.addDomain"

/**
 * What "Open a page" opens for [text]: the address as typed, https when it names no scheme; null
 * for anything but an http or https address. A host and port (`localhost:8080`, with a path, a
 * query or a fragment after it) names no scheme.
 */
internal fun debugPageUrl(text: String): String? {
    val typed = text.trim()
    if (typed.isEmpty()) return null
    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(.*)$").find(typed)
    val namesScheme = scheme != null && !Regex("^\\d+([/?#].*)?$").matches(scheme.groupValues[1])
    return (if (namesScheme) typed else "https://$typed").toHttpUrlOrNull()?.toString()
}
