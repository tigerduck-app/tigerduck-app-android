package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.EndpointHealthCheck
import org.ntust.app.tigerduck.network.OverrideValidator
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets

/**
 * Screen for picking which backend the app talks to.
 *
 * The backend is open source and self-hostable, so this is a supported
 * user-facing setting rather than a developer hatch: any host is accepted,
 * subject to [OverrideValidator]'s transport rule (HTTPS unless the address
 * is private/loopback) and to [EndpointHealthCheck] finding a TigerDuck
 * backend actually answering.
 */
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
    val healthCheck = remember(entryPoint) { entryPoint.endpointHealthCheck() }
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(prefs.announcementApiBaseUrlOverride.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(resolveAnnouncementEndpoint(prefs)) }
    var stored by remember { mutableStateOf(prefs.announcementApiBaseUrlOverride) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    // Blocks both buttons while the probe is in flight — a second Save
    // landing mid-probe would race two writes to the same pref with no
    // ordering guarantee.
    var isChecking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_api_endpoint)) },
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
                stringResource(R.string.settings_api_endpoint_effective_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                resolved.url,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            if (stored != null && !resolved.overrideApplied) {
                Text(
                    stringResource(R.string.settings_api_endpoint_stale_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.settings_api_endpoint_stale_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                stringResource(R.string.settings_api_endpoint_change_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Only transient feedback goes under the field. Which endpoint
            // is in force is already answered by the section above, and the
            // way back to the default is the button below rather than a
            // sentence telling the user to blank the field — so with nothing
            // to say, the row gives the space back instead of reserving a
            // line for it.
            val feedback = error ?: savedNote
            val feedbackContent: (@Composable () -> Unit)? = if (feedback == null) {
                null
            } else {
                {
                    Text(
                        feedback,
                        color = if (error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (error != null) error = null
                },
                singleLine = true,
                enabled = !isChecking,
                placeholder = { Text(stringResource(R.string.settings_api_endpoint_placeholder)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                isError = error != null,
                supportingText = feedbackContent,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val trimmed = draft.trim()
                        error = null
                        savedNote = null
                        when (val result = OverrideValidator.validate(trimmed)) {
                            OverrideValidator.Result.Malformed ->
                                error = context.getString(R.string.settings_api_endpoint_error_malformed)

                            OverrideValidator.Result.Insecure ->
                                error = context.getString(R.string.settings_api_endpoint_error_insecure)

                            is OverrideValidator.Result.Ok -> {
                                isChecking = true
                                scope.launch {
                                    // Probe before writing: this endpoint is
                                    // where every subsequent request goes,
                                    // including the ones that fetch the
                                    // screens the user would need to come
                                    // back and fix a bad value.
                                    when (val health = healthCheck.probe(result.normalized)) {
                                        is EndpointHealthCheck.Result.Unreachable ->
                                            error = context.getString(
                                                R.string.settings_api_endpoint_error_unreachable,
                                                health.detail,
                                            )

                                        EndpointHealthCheck.Result.NotTigerDuck ->
                                            error = context.getString(
                                                R.string.settings_api_endpoint_error_not_backend
                                            )

                                        EndpointHealthCheck.Result.Ok -> {
                                            prefs.announcementApiBaseUrlOverride = result.normalized
                                            stored = prefs.announcementApiBaseUrlOverride
                                            resolved = resolveAnnouncementEndpoint(prefs)
                                            draft = stored.orEmpty()
                                            // Re-register the device against
                                            // the new endpoint so the change
                                            // takes effect immediately — the
                                            // API clients pick the URL up per
                                            // call, but without a fresh
                                            // upsert the new backend has no
                                            // row for this device yet.
                                            pushRegistration.syncNow()
                                            savedNote = context.getString(
                                                if (result.rewrittenToHttp) {
                                                    R.string.settings_api_endpoint_saved_rewritten
                                                } else {
                                                    R.string.settings_api_endpoint_saved
                                                }
                                            )
                                        }
                                    }
                                    isChecking = false
                                }
                            }
                        }
                    },
                    enabled = !isChecking && draft.trim().isNotEmpty(),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_api_endpoint_checking))
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }

                OutlinedButton(
                    onClick = {
                        prefs.announcementApiBaseUrlOverride = null
                        stored = null
                        resolved = resolveAnnouncementEndpoint(prefs)
                        draft = ""
                        error = null
                        scope.launch { pushRegistration.syncNow() }
                        savedNote = context.getString(R.string.settings_api_endpoint_reset_done)
                    },
                    enabled = !isChecking && stored != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.settings_api_endpoint_reset_action)) }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_api_endpoint_https_note),
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
    fun endpointHealthCheck(): EndpointHealthCheck
}
