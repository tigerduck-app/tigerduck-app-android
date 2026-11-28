package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.screen.settings.PageGap
import org.ntust.app.tigerduck.ui.screen.settings.PageLinkRow
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLink
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLinkRow
import org.ntust.app.tigerduck.ui.screen.settings.SettingsRow
import org.ntust.app.tigerduck.ui.screen.settings.SettingsState
import org.ntust.app.tigerduck.ui.screen.settings.closedText
import org.ntust.app.tigerduck.ui.screen.settings.explainsClosed
import org.ntust.app.tigerduck.ui.screen.settings.label
import org.ntust.app.tigerduck.ui.screen.settings.title
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * School Mail's page of Settings (spec §6.5), as iOS's `MailSettingsView`: its notifications, on a
 * page of their own ([SchoolMailNotificationSettingsScreen]); the name shown to recipients; and the
 * mail's storage.
 *
 * The notifications and the name belong to the mailbox, so signed out they show dimmed, the name
 * kept, and a tap says to sign in.
 *
 * The mail's cache and the mail downloaded for search are measured and cleared on Settings'
 * Storage page, with everything else the app keeps; the row here leads there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchoolMailSettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    open: (SettingsLink) -> Unit,
    viewModel: SchoolMailSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    PageScaffold(
        title = stringResource(PageSettings.MAIL.title),
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item { ContentCard { PageLinkRow(SettingsRow.MAIL_NOTIFICATIONS, state) { open(SettingsLink.MAIL_NOTIFICATIONS) } } }
            item { PageGap() }
            item { SectionHeader(SettingsRow.MAIL_DISPLAY_NAME.label()) }
            item {
                ContentCard {
                    DisplayNameField(ui.displayName, SettingsRow.MAIL_DISPLAY_NAME.closedText(state), viewModel::setDisplayName)
                }
            }
            item {
                ContentCard(modifier = Modifier.padding(top = 12.dp)) {
                    SettingsLinkRow(SettingsRow.MAIL_STORAGE.label()) { open(SettingsLink.STORAGE) }
                }
            }
        }
    }
}

/**
 * The name shown to recipients, with its hint. While [closedReason] closes it the field shows the
 * name dimmed, and a tap on it, or TalkBack's double tap, says why.
 */
@Composable
private fun DisplayNameField(name: String, closedReason: String?, onChange: (String) -> Unit) {
    val label = SettingsRow.MAIL_DISPLAY_NAME.label()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedTextField(
                value = name,
                onValueChange = onChange,
                enabled = closedReason == null,
                singleLine = true,
                // Closed, the cover below speaks for it.
                modifier = Modifier.fillMaxWidth().then(if (closedReason != null) Modifier.clearAndSetSemantics { } else Modifier),
            )
            if (closedReason != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .semantics(mergeDescendants = true) {
                            contentDescription = label
                            if (name.isNotEmpty()) stateDescription = name
                        }
                        .explainsClosed(closedReason),
                )
            }
        }
        Text(
            stringResource(R.string.school_mail_settings_display_name_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (closedReason == null) ContentAlpha.SECONDARY else ContentAlpha.DISABLED,
            ),
        )
    }
}
