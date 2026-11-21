package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** Debug builds only, and in English, as iOS's: tools for trying the app out. Search leaves it out. */
@Composable
internal fun DeveloperSection(viewModel: SettingsViewModel, open: (SettingsLink) -> Unit) {
    Column {
        SectionHeader("Developer")
        ContentCard {
            Column {
                SettingsLinkRow("Time override") { open(SettingsLink.DEBUG_TIME) }
                HorizontalDivider()
                SettingsLinkRow("Notification") { open(SettingsLink.DEBUG_NOTIFICATION) }
                HorizontalDivider()
                SettingsLinkRow("Server failure simulation") { open(SettingsLink.DEBUG_SERVER_FAILURE) }
                HorizontalDivider()
                // Acts as a phone without the Moodle app, a browser, a mail app and so on.
                SettingsLinkRow("Missing apps") { open(SettingsLink.DEBUG_OUTSIDE_APPS) }
                HorizontalDivider()
                // Which sites the TigerDuck browser loads inside.
                SettingsLinkRow("Browser allowlist") { open(SettingsLink.DEBUG_BROWSER_ALLOWLIST) }
                HorizontalDivider()
                // Points School Mail at a non-school mail server, so the
                // feature can be exercised without a real school mailbox.
                SettingsLinkRow("Email") { open(SettingsLink.DEBUG_MAIL_SERVER) }
                HorizontalDivider()
                // Video, audio, images, documents and archives of every kind the
                // viewer takes, and a file picked on the phone, opened as Moodle's are.
                SettingsLinkRow("File viewer samples") { open(SettingsLink.DEBUG_VIEWER_SAMPLES) }
                HorizontalDivider()
                // One-shot UI surfaces (What's new, update prompt,
                // flip-to-library first trigger) live behind here so
                // they can be re-fired after a single dismissal.
                SettingsLinkRow("Triggers") { open(SettingsLink.DEBUG_TRIGGERS) }
                HorizontalDivider()
                DeveloperSwitches(viewModel)
                HorizontalDivider()
                FullResetRow(viewModel)
            }
        }
    }
}

@Composable
private fun DeveloperSwitches(viewModel: SettingsViewModel) {
    // Makes the next request to a school host meet the TLS pin-mismatch
    // prompt, as a certificate the pins don't know would.
    var simulatingPinMismatch by remember {
        mutableStateOf(viewModel.pinManager.state.policy.simulatedMismatch != null)
    }
    SettingsToggleRow(
        label = "Simulate a TLS pin mismatch",
        checked = simulatingPinMismatch,
        subtitle = "Replaces ntust.edu.tw's pins with one no chain has, until the app restarts. The prompt needs a document offering the chain's root: adb shell am broadcast -a org.ntust.app.tigerduck.debug.INJECT_PIN_DOCUMENT",
        onCheckedChange = {
            simulatingPinMismatch = it
            viewModel.pinManager.simulateMismatch(if (it) "ntust" else null)
        },
    )
    HorizontalDivider()
    SettingsToggleRow(
        label = "Disable screen-capture protection",
        checked = viewModel.appState.disableScreenCaptureProtection,
        subtitle = "Allows screenshots/recordings of normally-protected surfaces (login sheet, library, onboarding password).",
        onCheckedChange = {
            viewModel.appState.disableScreenCaptureProtection = it
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullResetRow(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { },
                // The app vibrates for this long press itself, through the Vibration setting;
                // Compose's own buzz would land on top of it and ignore that setting.
                hapticFeedbackEnabled = false,
                onLongClick = {
                    Haptics.perform(
                        context,
                        HapticScenario.ClassTableLongPress,
                    )
                    viewModel.appState.performFullReset()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Long press to erase everything and restart",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Wipes all data, accounts, and preferences",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
    }
}
