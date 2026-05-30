package org.ntust.app.tigerduck.ui.screen.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.update.UpdateChecker

/**
 * Developer-only screen for re-firing one-shot UI surfaces that are otherwise
 * hard to retest after they've been dismissed once. Reached from
 * `Settings → Developer → Triggers`; the entry point and the NavHost route are
 * both `BuildConfig.DEBUG`-gated so release builds never see either.
 *
 * Each section drives the real production path with the smallest hook that
 * simulates a genuine fire — setting a persisted sentinel, flipping the
 * install-ready flag, or replaying the same request a real face-down gesture
 * surfaces — rather than shortcutting around the surface it's meant to test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersDebugScreen(
    appState: AppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TriggersEntryPoint::class.java,
        )
    }
    val prefs = remember(entryPoint) { entryPoint.appPreferences() }
    val updateChecker = remember(entryPoint) { entryPoint.updateChecker() }
    val arming = remember(entryPoint) { entryPoint.triggersDebugArming() }

    val isFlipArmed by arming.isFlipArmed.collectAsStateWithLifecycle()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Both must be on for the steady-state flip to even register, so the replay
    // is only meaningful when they are.
    val canTriggerFlip = appState.libraryFeatureEnabled && appState.flipToLibraryEnabled

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Triggers") },
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TriggerSection(
                header = "What's New",
                footer = "Sets the replay sentinel so the newest whatsnew.json " +
                    "entry shows on the next process start, regardless of this " +
                    "build's versionCode.",
            ) {
                Button(
                    onClick = {
                        prefs.lastSeenWhatsNewVersionCode = AppPreferences.WHATS_NEW_REPLAY
                        statusMessage = "Armed \"What's new\" — relaunch the app to see it."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Trigger What's New on next open") }
            }

            HorizontalDivider()

            TriggerSection(
                header = "Update prompt",
                footer = "Fires the in-app \"Update available\" dialog with a " +
                    "synthetic versionCode (Int.MAX_VALUE / display \"99.0.0\") " +
                    "so the three actions (Update now / Later / Skip this " +
                    "version) can be retested without a real Play update " +
                    "available. (No-op on fdroid.)",
            ) {
                Button(
                    onClick = {
                        updateChecker.armForDebug()
                        statusMessage = "Fired the update prompt."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Trigger Update prompt") }
            }

            HorizontalDivider()

            TriggerSection(
                header = "Flip to Library",
                footer = if (!canTriggerFlip) {
                    "Requires both Library and Flip-to-Library to be enabled. " +
                        "Turn them on in Settings first."
                } else {
                    "Resets the first-trigger seen flag, waits 3 seconds, then " +
                        "replays the same prompt a real face-down gesture surfaces."
                },
            ) {
                Button(
                    onClick = {
                        arming.armFlipPrompt(appState)
                        statusMessage = "Reset first-trigger flag. Prompt fires in 3 seconds."
                    },
                    enabled = canTriggerFlip && !isFlipArmed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("First library flip after 3 sec") }
                if (isFlipArmed) {
                    Text(
                        "Flip prompt scheduled. Stay here or navigate to any tab — " +
                            "it'll fire root-level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            statusMessage?.let { msg ->
                HorizontalDivider()
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TriggerSection(
    header: String,
    footer: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            header,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
        Text(
            footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface TriggersEntryPoint {
    fun appPreferences(): AppPreferences
    fun updateChecker(): UpdateChecker
    fun triggersDebugArming(): TriggersDebugArming
}
