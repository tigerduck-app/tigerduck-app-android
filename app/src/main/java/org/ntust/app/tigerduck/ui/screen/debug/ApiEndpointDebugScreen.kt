package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.announcements.OverrideValidator
import org.ntust.app.tigerduck.announcements.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushRegistrationService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiEndpointDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ApiEndpointEntryPoint::class.java,
        )
    }
    val prefs = remember(entryPoint) { entryPoint.appPreferences() }
    val pushRegistration = remember(entryPoint) { entryPoint.pushRegistrationService() }
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(prefs.announcementApiBaseUrlOverride.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(resolveAnnouncementEndpoint(prefs)) }
    var stored by remember { mutableStateOf(prefs.announcementApiBaseUrlOverride) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedNote) {
        if (savedNote != null) {
            delay(2000)
            savedNote = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API endpoint") },
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
                "Effective endpoint",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                resolved.url,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "Override (Announcement server only)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (error != null) error = null
                },
                singleLine = true,
                placeholder = { Text("http://192.168.X.X:40000/v2") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                isError = error != null,
                supportingText = {
                    when {
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        savedNote != null -> Text(savedNote!!)
                        resolved.overrideApplied -> Text("Active override is in effect.")
                        stored != null -> Text(
                            "Stored override was rejected by the allowlist; the default " +
                                "endpoint is in use. Tap Clear to remove it.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text("Default endpoint used when blank.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val trimmed = draft.trim()
                        if (trimmed.isEmpty()) {
                            error = "Enter a URL or use Clear to remove the override."
                            return@Button
                        }
                        val result = OverrideValidator.validate(trimmed)
                        when (result) {
                            is OverrideValidator.Result.Invalid -> {
                                error = result.message
                                return@Button
                            }
                            is OverrideValidator.Result.Ok -> {
                                prefs.announcementApiBaseUrlOverride = result.normalized
                                stored = prefs.announcementApiBaseUrlOverride
                                resolved = resolveAnnouncementEndpoint(prefs)
                                draft = stored.orEmpty()
                                // Re-register the device against the new endpoint
                                // so the change takes effect immediately —
                                // PushApiClient picks the URL up per call, but
                                // without a fresh upsert the new backend has no
                                // row for this device yet.
                                scope.launch { pushRegistration.syncNow() }
                                savedNote = if (result.rewrittenToHttp) {
                                    "Saved as ${resolved.url} (https rewritten to http for LAN host). Re-registering…"
                                } else {
                                    "Saved. Re-registering with ${resolved.url}…"
                                }
                            }
                        }
                    },
                    enabled = draft.trim().isNotEmpty(),
                ) { Text("Save") }

                OutlinedButton(
                    onClick = {
                        prefs.announcementApiBaseUrlOverride = null
                        stored = null
                        resolved = resolveAnnouncementEndpoint(prefs)
                        draft = ""
                        error = null
                        scope.launch { pushRegistration.syncNow() }
                        savedNote = "Override cleared. Re-registering with ${resolved.url}…"
                    },
                    enabled = stored != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Clear override") }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Allowed: loopback, RFC1918 private IPv4 (10.x, 172.16–31.x, 192.168.x), " +
                    "or *.api.tigerduck.app (HTTPS only). LAN backends speak HTTP — " +
                    "https://192.168.X.X:… is auto-rewritten to http:// at save time. " +
                    "Save / Clear takes effect immediately and re-registers this " +
                    "device against the new endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface ApiEndpointEntryPoint {
    fun appPreferences(): AppPreferences
    fun pushRegistrationService(): PushRegistrationService
}
