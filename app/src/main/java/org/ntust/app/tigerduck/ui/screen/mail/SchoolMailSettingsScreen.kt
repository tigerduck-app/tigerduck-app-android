package org.ntust.app.tigerduck.ui.screen.mail

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.screen.settings.SettingRowHeight
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLinkRow
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * School Mail settings, linked from Settings → Other settings like the library
 * page (spec §6.5).
 *
 * The new-mail toggle, its hint and the notification diagnostics section used
 * to sit above the "Name shown to recipients" field here. They moved to
 * [SchoolMailNotificationSettingsScreen], reached from Settings →
 * Notifications, so notification settings live with the app's other
 * notification settings and the toggle exists in exactly one place.
 *
 * "Name shown to recipients" names the mailbox that signs outgoing mail, so
 * signed out it is greyed out rather than hidden — same rule as the School
 * Mail notifications row in Settings. The guide link below it stays live
 * either way: it is about using the mailbox from another app, which is the one
 * thing a signed-out user here might still want.
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
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()

    // Re-measured whenever the screen is entered: mail read since the last visit has grown the
    // cache behind it.
    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }

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
            item {
                ContentCard(modifier = Modifier.padding(top = 12.dp)) {
                    CacheSizeRow(cacheBytes = cacheBytes, onClear = viewModel::clearCache)
                }
            }
        }
    }
}

/**
 * Cache size plus Clear cache. The figure covers everything under the mail cache root, so it is the
 * disk the mailbox actually occupies, not just the message bodies. It reads "…" until the first
 * measurement lands, and clearing is offered only when there is something to clear — no
 * confirmation, since all of it is re-downloadable.
 */
@Composable
private fun CacheSizeRow(cacheBytes: Long?, onClear: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.school_mail_cache_size),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            cacheBytes?.let { Formatter.formatFileSize(context, it) } ?: "\u2026",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        TextButton(onClick = onClear, enabled = (cacheBytes ?: 0L) > 0L) {
            Text(stringResource(R.string.school_mail_clear_cache))
        }
    }
}
