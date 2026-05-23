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
                        val result = OverrideValidator.validate(trimmed)
                        when (result) {
                            is OverrideValidator.Result.Invalid -> {
                                error = result.message
                                return@Button
                            }
                            is OverrideValidator.Result.Ok -> {
                                prefs.announcementApiBaseUrlOverride = result.normalized
                                stored = prefs.announcementApiBaseUrlOverride
                                effective = resolveEffective(prefs)
                                draft = stored.orEmpty()
                                savedNote = if (result.rewrittenToHttp) {
                                    "Saved as $effective (https rewritten to http for LAN host)."
                                } else {
                                    "Saved. Takes effect on the next Announcement request."
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
                "Allowed: loopback, RFC1918 private IPv4 (10.x, 172.16–31.x, 192.168.x), " +
                    "or *.api.tigerduck.app (HTTPS only). LAN backends speak HTTP — " +
                    "https://192.168.X.X:… is auto-rewritten to http:// at save time. " +
                    "Only the Announcement (bulletin) base URL is overridden; push " +
                    "registration still uses the build's default endpoint.",
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
 * Mirrors the iOS `PushServerConfig.isOverrideAllowed` / `normalize` pair so
 * the two platforms accept the same dev backends. Public hosts must be on
 * the `*.api.tigerduck.app` allowlist and speak HTTPS. Loopback / RFC1918
 * accept either scheme; `https://` to those hosts is rewritten to `http://`
 * so the most common LAN typo doesn't fail at the TLS handshake.
 */
internal object OverrideValidator {
    private val publicHostExactAllowlist = setOf("api.tigerduck.app")
    private val publicHostSuffixAllowlist = listOf(".api.tigerduck.app")

    sealed interface Result {
        data class Ok(val normalized: String, val rewrittenToHttp: Boolean) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(raw: String): Result {
        val parsed = runCatching { URI(raw) }.getOrNull()
            ?: return Result.Invalid("URL is malformed.")
        val scheme = parsed.scheme?.lowercase()
            ?: return Result.Invalid("URL is missing a scheme.")
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("Scheme must be http or https.")
        }
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) return Result.Invalid("URL is missing a host.")

        val isLocal = host == "localhost" || host == "127.0.0.1" || isPrivateIpv4(host)
        val isPublic = isAllowedPublicHost(host)

        if (!isLocal && !isPublic) {
            return Result.Invalid(
                "Rejected by allowlist. Only loopback, RFC1918 " +
                    "(10.x / 172.16–31.x / 192.168.x), or *.api.tigerduck.app are accepted.",
            )
        }
        if (isPublic && scheme != "https") {
            return Result.Invalid("Public hosts must use https.")
        }

        // Auto-downgrade https→http for LAN backends (LAN dev servers usually
        // don't terminate TLS; pasting https://192.168.X.X:… would otherwise
        // fail at handshake with WRONG_VERSION_NUMBER).
        return if (isLocal && scheme == "https") {
            val rewritten = URI(
                "http", parsed.userInfo, parsed.host, parsed.port,
                parsed.path, parsed.query, parsed.fragment,
            )
            Result.Ok(normalized = rewritten.toString(), rewrittenToHttp = true)
        } else {
            Result.Ok(normalized = parsed.toString(), rewrittenToHttp = false)
        }
    }

    private fun isAllowedPublicHost(host: String): Boolean {
        if (host in publicHostExactAllowlist) return true
        return publicHostSuffixAllowlist.any { suffix ->
            // host must be longer than the suffix so the apex isn't
            // double-counted via the suffix branch.
            host.length > suffix.length && host.endsWith(suffix)
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface AppPreferencesEntryPoint {
    fun appPreferences(): AppPreferences
}
