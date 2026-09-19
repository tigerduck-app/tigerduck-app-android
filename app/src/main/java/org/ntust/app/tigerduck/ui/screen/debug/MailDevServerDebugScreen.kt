package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.mail.MailDevServerController
import org.ntust.app.tigerduck.mail.MailDevServerSettings
import org.ntust.app.tigerduck.mail.MailEndpoint
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.MailSite
import org.ntust.app.tigerduck.mail.MailTransportSecurity
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets

/**
 * Points School Mail at a mail server that is not the school's, so the whole feature can
 * be exercised against a throwaway mailbox (spec §1.1 fixes IMAP 993 / SMTP 465 implicit
 * TLS for the real thing; this is a deliberate debug-only exception to that, not a repeal
 * of it — see [MailDevServerSettings.toConfig], which refuses to weaken a school host).
 *
 * Debug builds only: the route is registered inside `AppNavigation`'s `BuildConfig.DEBUG`
 * block and [MailSite] reads nothing here in a release build. Debug UI in this app is
 * written in English and not localized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDevServerDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, MailDevServerEntryPoint::class.java)
    }
    val controller = remember(entryPoint) { entryPoint.controller() }
    val site = remember(entryPoint) { entryPoint.site() }

    var draft by remember { mutableStateOf(controller.settings) }
    var effective by remember { mutableStateOf(site.config()) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val trimmed = draft.copy(
            domain = draft.domain.trim(),
            imap = draft.imap.copy(host = draft.imap.host.trim()),
            smtp = draft.smtp.copy(host = draft.smtp.host.trim()),
        )
        if (trimmed.enabled && !trimmed.isComplete) {
            error = "Fill in the domain and both hosts, with ports between 1 and 65535."
            return
        }
        error = null
        draft = trimmed
        val reset = controller.apply(trimmed)
        effective = site.config()
        note = if (reset) {
            "Saved. Signed out, and the mail cache, folders and notifications were cleared."
        } else {
            "Saved. The effective server did not change, so nothing was cleared."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text("Email") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Runs School Mail against another server so you can test without touching your " +
                    "school mailbox. Changing anything here signs the mail account out and clears " +
                    "its cache, folders and notifications — the cache has no account dimension, so " +
                    "the old account's mail must not be left sitting under the new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Override the school server", modifier = Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            }

            HorizontalDivider()

            OutlinedTextField(
                value = draft.domain,
                onValueChange = { draft = draft.copy(domain = it) },
                label = { Text("Address domain") },
                supportingText = { Text("Replaces ${MailServerConfig.DOMAIN} in your own address.") },
                singleLine = true,
                keyboardOptions = hostKeyboard,
                modifier = Modifier.fillMaxWidth(),
            )

            EndpointEditor(
                label = "IMAP",
                endpoint = draft.imap,
                onChange = { draft = draft.copy(imap = it) },
            )
            EndpointEditor(
                label = "SMTP",
                endpoint = draft.smtp,
                onChange = { draft = draft.copy(smtp = it) },
            )

            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { save() }) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        val reset = controller.reset()
                        draft = controller.settings
                        effective = site.config()
                        error = null
                        note = if (reset) "Back to the school server. Signed out and cleared." else "Already on the school server."
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset") }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()

            Text("In force now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                buildString {
                    appendLine("domain  @${effective.domain}")
                    appendLine("imap    ${effective.imap.host}:${effective.imap.port} ${effective.imap.security.label}")
                    append("smtp    ${effective.smtp.host}:${effective.smtp.port} ${effective.smtp.security.label}")
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                "A ${MailServerConfig.DOMAIN} host always keeps 993/465 implicit TLS and its " +
                    "certificate pins, whatever is typed above — the override may not downgrade " +
                    "the real school connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EndpointEditor(label: String, endpoint: MailEndpoint, onChange: (MailEndpoint) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = endpoint.host,
            onValueChange = { onChange(endpoint.copy(host = it)) },
            label = { Text("$label host") },
            singleLine = true,
            keyboardOptions = hostKeyboard,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            // A blank field reads back as port 0, which [MailEndpoint.isComplete] rejects, so
            // a half-typed port can never be saved and applied.
            value = if (endpoint.port == 0) "" else endpoint.port.toString(),
            onValueChange = { text ->
                onChange(endpoint.copy(port = text.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0))
            },
            label = { Text("$label port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MailTransportSecurity.entries.forEach { security ->
                FilterChip(
                    selected = endpoint.security == security,
                    onClick = { onChange(endpoint.copy(security = security)) },
                    label = { Text(security.label) },
                )
            }
        }
    }
}

private val hostKeyboard = KeyboardOptions(
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Next,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)

private val MailTransportSecurity.label: String
    get() = when (this) {
        MailTransportSecurity.IMPLICIT_TLS -> "TLS"
        MailTransportSecurity.STARTTLS -> "STARTTLS"
        MailTransportSecurity.NONE -> "None"
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface MailDevServerEntryPoint {
    fun controller(): MailDevServerController
    fun site(): MailSite
}
