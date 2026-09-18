package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLinkRow
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * 校園信箱 settings, linked from Settings → 其他設定 like the library page
 * (spec §6.5).
 *
 * The new-mail toggle, its hint and the 通知診斷 section used to sit above
 * 寄件者顯示名稱 here. They moved to [SchoolMailNotificationSettingsScreen],
 * reached from Settings → 通知, so notification settings live with the app's
 * other notification settings and the toggle exists in exactly one place.
 *
 * 寄件者顯示名稱 names the mailbox that signs outgoing mail, so signed out it
 * is greyed out rather than hidden — same rule as the 校園信箱通知 row in
 * Settings. The guide link below it stays live either way: it is about using
 * the mailbox from another app, which is the one thing a signed-out user here
 * might still want.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolMailSettingsScreen(
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    viewModel: SchoolMailSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.school_mail_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item { SectionHeader(stringResource(R.string.school_mail_settings_display_name)) }
            item {
                ContentCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ui.displayName,
                            onValueChange = viewModel::setDisplayName,
                            enabled = signedIn,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.school_mail_settings_display_name_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (signedIn) ContentAlpha.SECONDARY else ContentAlpha.DISABLED,
                            ),
                        )
                    }
                }
            }
            item {
                ContentCard(modifier = Modifier.padding(top = 12.dp)) {
                    SettingsLinkRow(stringResource(R.string.school_mail_use_other_app)) { onOpenGuide() }
                }
            }
        }
    }
}
