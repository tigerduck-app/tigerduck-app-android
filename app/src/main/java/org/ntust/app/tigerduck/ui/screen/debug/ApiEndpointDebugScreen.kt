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
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiEndpointDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, AppPreferencesEntryPoint::class.java)
            .appPreferences()
    }

    var draft by remember { mutableStateOf(prefs.announcementApiBaseUrlOverride.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var effective by remember { mutableStateOf(resolveEffective(prefs)) }
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
                effective,
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
                placeholder = { Text("https://api.example.com/v2") },
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
                        stored != null -> Text("Active override is in effect.")
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
                        val validation = validateOverride(trimmed)
                        if (validation != null) {
                            error = validation
                            return@Button
                        }
                        prefs.announcementApiBaseUrlOverride = trimmed
                        stored = prefs.announcementApiBaseUrlOverride
                        effective = resolveEffective(prefs)
                        draft = stored.orEmpty()
                        savedNote = "Saved. Takes effect on the next Announcement request."
                    },
                    enabled = draft.trim().isNotEmpty(),
                ) { Text("Save") }

                OutlinedButton(
                    onClick = {
                        prefs.announcementApiBaseUrlOverride = null
                        stored = null
                        effective = resolveEffective(prefs)
                        draft = ""
                        error = null
                        savedNote = "Override cleared."
                    },
                    enabled = stored != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Clear override") }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Only the Announcement (bulletin) base URL is overridden. Push registration " +
                    "still uses the build's default endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun resolveEffective(prefs: AppPreferences): String {
    val override = prefs.announcementApiBaseUrlOverride?.trimEnd('/')
    return override ?: BuildConfig.PUSH_BASE_URL.trimEnd('/')
}

/**
 * Returns an error message if [raw] is not a usable override, or null if
 * it's accepted. Validation is intentionally minimal — the screen is
 * DEBUG-only and we want LAN dev backends to work without an allowlist.
 */
private fun validateOverride(raw: String): String? {
    val url = runCatching { URI(raw) }.getOrNull()
        ?: return "URL is malformed."
    val scheme = url.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return "Scheme must be http or https."
    }
    if (url.host.isNullOrBlank()) {
        return "URL is missing a host."
    }
    return null
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface AppPreferencesEntryPoint {
    fun appPreferences(): AppPreferences
}
