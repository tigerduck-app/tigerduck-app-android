// The rows the Storage page and each page's own storage are drawn with: what a kind takes and its
// button, and the question a clearing asks first. One set, so a kind reads, clears and asks the
// same on either.

package org.ntust.app.tigerduck.ui.screen.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.storage.AppStorage.Kind
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.util.replaceIosArg

/**
 * What a clearing asks before it goes ahead: every downloaded file at once, the mail downloaded
 * for search, which takes long to fetch again, and the sites' data, after which sites may ask the
 * student to sign in again. [kind] is what it clears, null for every downloaded file.
 */
internal enum class StorageQuestion(
    val kind: Kind?,
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val confirm: Int,
) {
    DOWNLOADED_FILES(
        null,
        R.string.settings_downloaded_files_delete_all_title,
        R.string.settings_downloaded_files_delete_all_message,
        R.string.action_delete,
    ),
    DOWNLOADED_MAIL(
        Kind.DOWNLOADED_MAIL,
        R.string.school_mail_storage_delete_title,
        R.string.school_mail_storage_delete_message,
        R.string.school_mail_delete,
    ),
    WEBSITE_DATA(
        Kind.WEBSITE_DATA,
        R.string.settings_storage_browser_data_delete_title,
        R.string.settings_storage_browser_data_delete_message,
        R.string.action_delete,
    ),
}

/** What clearing [this] asks first, or null where it goes at once since it downloads again as it is read. */
internal val Kind.question: StorageQuestion?
    get() = StorageQuestion.entries.firstOrNull { it.kind == this }

@get:StringRes
internal val Kind.label: Int
    get() = when (this) {
        Kind.MOODLE_FILES -> R.string.settings_downloaded_files_moodle
        Kind.MAIL_ATTACHMENTS -> R.string.settings_downloaded_files_mail
        Kind.CLASS_TABLE_IMAGES -> R.string.settings_downloaded_files_images
        Kind.CACHED_MAIL -> R.string.school_mail_cache_size
        Kind.DOWNLOADED_MAIL -> R.string.school_mail_storage_title
        Kind.WEBSITE_DATA -> R.string.settings_storage_browser_data
    }

/**
 * [kind]'s row: its name, its size, and its button, which [onClear] answers by clearing it or by
 * asking first ([question]). The mail's cache says "Clear cache" and needs no more for TalkBack;
 * every other button says what it deletes.
 */
@Composable
internal fun KindRow(kind: Kind, bytes: Long?, onClear: () -> Unit) {
    val label = stringResource(kind.label)
    when (kind) {
        Kind.CACHED_MAIL -> SizeRow(label, bytes, action = stringResource(R.string.school_mail_clear_cache), onAction = onClear)
        Kind.DOWNLOADED_MAIL ->
            SizeRow(label, bytes, stringResource(R.string.school_mail_delete), deleteDescription(label), onClear)
        else -> SizeRow(label, bytes, stringResource(R.string.action_delete), deleteDescription(label), onClear)
    }
}

/** [question], open: its confirm, in the error color, does [onConfirm]; cancel or a tap outside [onDismiss]. */
@Composable
internal fun StorageQuestionDialog(question: StorageQuestion, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(question.title),
        message = stringResource(question.message),
        confirmText = stringResource(question.confirm),
        confirmColors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.action_cancel),
    )
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
internal fun SizeRow(
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
internal fun DestructiveRow(label: String, enabled: Boolean, onClick: () -> Unit) {
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
            color = MaterialTheme.colorScheme.error.copy(
                alpha = if (enabled) 1f else ContentAlpha.DISABLED,
            ),
        )
    }
}
