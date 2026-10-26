package org.ntust.app.tigerduck.ui.screen.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.storage.AppStorage.Kind
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.util.replaceIosArg

/** What a clearing asks before it goes ahead, while its question is open. */
private enum class Asking { DOWNLOADED_FILES, DOWNLOADED_MAIL, WEBSITE_DATA }

/**
 * 儲存空間, under 其他設定: everything the app keeps on the phone that can be cleared, with the
 * total first. Downloaded files (Moodle's, mail attachments, class table images), each deleted at
 * once since it downloads again when next opened, and all of them together, which asks first.
 * School Mail's cache, cleared at once, and the mail downloaded for search, which takes long to
 * fetch again and asks first. The TigerDuck browser's website data, which asks first, since sites
 * may ask the student to sign in again. School Mail's settings link here, where its rows used to
 * be. The TigerDuck browser's downloads are in the phone's Downloads folder, the user's, and a
 * footnote says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    // Measured each time the page shows, as files and mail come and go behind it.
    LifecycleResumeEffect(viewModel) {
        viewModel.measure()
        onPauseOrDispose { }
    }
    var asking by rememberSaveable { mutableStateOf<Asking?>(null) }
    val total = sizes?.values?.sum()
    val downloadedTotal = sizes?.filterKeys { it.downloaded }?.values?.sum()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard { SizeRow(stringResource(R.string.settings_storage_total), total) }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item { SectionHeader(stringResource(R.string.settings_downloaded_files_title)) }
            item {
                ContentCard(modifier = Modifier.padding(top = 4.dp)) {
                    Column {
                        Kind.entries.filter { it.downloaded }.forEach { kind ->
                            val label = stringResource(kind.label)
                            SizeRow(
                                label,
                                sizes?.get(kind),
                                action = stringResource(R.string.action_delete),
                                actionDescription = deleteDescription(label),
                                onAction = { viewModel.clear(kind) },
                            )
                            HorizontalDivider()
                        }
                        DestructiveRow(
                            stringResource(R.string.settings_downloaded_files_delete_all),
                            enabled = (downloadedTotal ?: 0L) > 0L,
                            onClick = { asking = Asking.DOWNLOADED_FILES },
                        )
                    }
                }
            }
            item { Footnote(stringResource(R.string.settings_downloaded_files_footer)) }

            item { Spacer(Modifier.height(16.dp)) }

            item { SectionHeader(stringResource(R.string.feature_school_mail)) }
            item {
                ContentCard(modifier = Modifier.padding(top = 4.dp)) {
                    Column {
                        // All of it downloads again as it is read, so no question.
                        SizeRow(
                            stringResource(R.string.school_mail_cache_size),
                            sizes?.get(Kind.CACHED_MAIL),
                            action = stringResource(R.string.school_mail_clear_cache),
                            onAction = { viewModel.clear(Kind.CACHED_MAIL) },
                        )
                        HorizontalDivider()
                        val label = stringResource(R.string.school_mail_storage_title)
                        SizeRow(
                            label,
                            sizes?.get(Kind.DOWNLOADED_MAIL),
                            action = stringResource(R.string.school_mail_delete),
                            actionDescription = deleteDescription(label),
                            onAction = { asking = Asking.DOWNLOADED_MAIL },
                        )
                    }
                }
            }
            item { Footnote(stringResource(R.string.school_mail_storage_footer)) }

            item { Spacer(Modifier.height(16.dp)) }

            item { SectionHeader(stringResource(R.string.settings_browser_in_app)) }
            item {
                ContentCard(modifier = Modifier.padding(top = 4.dp)) {
                    val label = stringResource(R.string.settings_storage_browser_data)
                    SizeRow(
                        label,
                        sizes?.get(Kind.WEBSITE_DATA),
                        action = stringResource(R.string.action_delete),
                        actionDescription = deleteDescription(label),
                        onAction = { asking = Asking.WEBSITE_DATA },
                    )
                }
            }
        }
    }

    asking?.let { question ->
        val (title, message, confirm) = when (question) {
            Asking.DOWNLOADED_FILES -> Triple(
                R.string.settings_downloaded_files_delete_all_title,
                R.string.settings_downloaded_files_delete_all_message,
                R.string.action_delete,
            )
            Asking.DOWNLOADED_MAIL -> Triple(
                R.string.school_mail_storage_delete_title,
                R.string.school_mail_storage_delete_message,
                R.string.school_mail_delete,
            )
            Asking.WEBSITE_DATA -> Triple(
                R.string.settings_storage_browser_data_delete_title,
                R.string.settings_storage_browser_data_delete_message,
                R.string.action_delete,
            )
        }
        TigerDuckDialog(
            onDismissRequest = { asking = null },
            title = stringResource(title),
            message = stringResource(message),
            confirmText = stringResource(confirm),
            confirmColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            onConfirm = {
                asking = null
                when (question) {
                    Asking.DOWNLOADED_FILES -> viewModel.clearDownloaded()
                    Asking.DOWNLOADED_MAIL -> viewModel.clear(Kind.DOWNLOADED_MAIL)
                    Asking.WEBSITE_DATA -> viewModel.clear(Kind.WEBSITE_DATA)
                }
            },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
}

private val Kind.label: Int
    get() = when (this) {
        Kind.MOODLE_FILES -> R.string.settings_downloaded_files_moodle
        Kind.MAIL_ATTACHMENTS -> R.string.settings_downloaded_files_mail
        Kind.CLASS_TABLE_IMAGES -> R.string.settings_downloaded_files_images
        Kind.CACHED_MAIL -> R.string.school_mail_cache_size
        Kind.DOWNLOADED_MAIL -> R.string.school_mail_storage_title
        Kind.WEBSITE_DATA -> R.string.settings_storage_browser_data
    }

/** "Delete Moodle files": what TalkBack hears for a row's button, not one of several alike. */
@Composable
private fun deleteDescription(label: String): String =
    stringResource(R.string.settings_storage_delete_kind).replaceIosArg(label)

/**
 * A label, its size ("…" until measured), and [action] when there's one, offered only when the
 * size says there's something to clear; TalkBack hears it as [actionDescription] when given.
 */
@Composable
private fun SizeRow(
    label: String,
    bytes: Long?,
    action: String? = null,
    actionDescription: String? = null,
    onAction: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .padding(start = 16.dp, end = if (action != null) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            bytes?.let { Formatter.formatFileSize(context, it) } ?: "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        if (action != null) {
            TextButton(
                onClick = onAction,
                enabled = (bytes ?: 0L) > 0L,
                modifier = Modifier.semantics { actionDescription?.let { contentDescription = it } },
            ) {
                Text(action)
            }
        }
    }
}

/** A whole row that clears, in the error color, dimmed when there's nothing to clear. */
@Composable
private fun DestructiveRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else ContentAlpha.DISABLED),
        )
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}
