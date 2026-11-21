package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.AppUrls
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * The About section: the version, the update check, the services' status, What's new, the
 * website and the rest of About. [onWhatsNew] is there only while there is something to show.
 */
@Composable
internal fun AboutSection(
    state: SettingsState,
    appVersion: String,
    isCheckingForUpdate: Boolean,
    onCheckForUpdates: () -> Unit,
    onWhatsNew: () -> Unit,
    browserPreference: String,
    open: (SettingsLink) -> Unit,
) {
    val context = LocalContext.current
    SettingsSectionCard(SettingsSection.ABOUT) {
        SettingsValueRow(SettingsRow.VERSION.label(), appVersion)
        // Play only. fdroid leaves update notifications to the F-Droid client app (the
        // UpdateChecker stub is a no-op there), so a row that could only ever report "up to
        // date" would just confuse users. Mirrors iOS hiding this row on Mac.
        if (SettingsRow.CHECK_FOR_UPDATES.isShown(state)) {
            HorizontalDivider()
            CheckForUpdatesRow(isChecking = isCheckingForUpdate, onClick = onCheckForUpdates)
        }
        // Sits under Check for updates because the two answer the same question from opposite
        // ends: that row asks whether *this app* is current, this one whether the services
        // behind it are up. Shown on every flavor — an F-Droid build talks to the same backend.
        //
        // The URL currently 302s to another origin. Custom Tabs follows that in place, so the
        // in-app choice stays in-app; handing the redirect to an ACTION_VIEW intent instead
        // would eject the user into their browser mid-hop.
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.SERVER_STATUS.label()) {
            openLink(context, AppUrls.SERVER_STATUS, browserPreference)
        }
        // Only when pages or a summary are registered for the resolved locale. During early
        // bring-up of a release there may be neither; then the row is hidden rather than
        // routing the user to an empty sheet.
        if (SettingsRow.WHATS_NEW.isShown(state)) {
            HorizontalDivider()
            SettingsLinkRow(SettingsRow.WHATS_NEW.label(), onClick = onWhatsNew)
        }
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.WEBSITE.label()) { openLink(context, AppUrls.WEBSITE, browserPreference) }
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.ABOUT_OTHERS.label()) { open(SettingsLink.ABOUT_OTHERS) }
    }
}

/**
 * About section row that swaps a chevron for a spinner while a manual
 * "Check for updates" call is in flight. Disabled while checking so a
 * stuck-finger user can't fire a second concurrent query.
 */
@Composable
internal fun CheckForUpdatesRow(isChecking: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(enabled = !isChecking) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            SettingsRow.CHECK_FOR_UPDATES.label(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
