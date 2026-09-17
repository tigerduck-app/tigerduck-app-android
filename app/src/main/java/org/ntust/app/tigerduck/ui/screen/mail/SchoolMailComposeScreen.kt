package org.ntust.app.tigerduck.ui.screen.mail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.ComposeError
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.util.replaceIosArg
import java.io.IOException

/** Spec §6.4 — SubscriptionRuleEditorScreen's full-screen editor with Send in the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolMailComposeScreen(onDone: () -> Unit, viewModel: SchoolMailComposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLeave by remember { mutableStateOf(false) }

    val quoteHeader = stringResource(R.string.school_mail_quote_header)
    val forwardedHeader = stringResource(R.string.school_mail_forwarded_header)
    val fromLabel = stringResource(R.string.school_mail_forward_from)
    val dateLabel = stringResource(R.string.school_mail_forward_date)
    val subjectLabel = stringResource(R.string.school_mail_forward_subject)
    val toLabel = stringResource(R.string.school_mail_details_to)
    LaunchedEffect(Unit) {
        viewModel.prefill(
            ComposePrefill.Labels(
                quoteHeader = { date, sender -> quoteHeader.replaceIosArg(1, date).replaceIosArg(2, sender) },
                forwardedHeader = forwardedHeader,
                from = { fromLabel.replaceIosArg(1, it) },
                date = { dateLabel.replaceIosArg(1, it) },
                subject = { subjectLabel.replaceIosArg(1, it) },
                to = { toLabel.replaceIosArg(1, it) },
            ),
        )
    }
    LaunchedEffect(state.done) {
        if (!state.done) return@LaunchedEffect
        if (state.savedDraft) Toast.makeText(context, R.string.school_mail_saved, Toast.LENGTH_SHORT).show()
        onDone()
    }

    val leave = { if (state.dirty && !state.sending) showLeave = true else onDone() }
    BackHandler(onBack = leave)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addAttachments(uris.mapNotNull { describe(context, it) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = {
                    Text(
                        stringResource(
                            when (viewModel.mode) {
                                ComposeMode.REPLY -> R.string.school_mail_reply
                                ComposeMode.REPLY_ALL -> R.string.school_mail_reply_all
                                ComposeMode.FORWARD -> R.string.school_mail_forward
                                ComposeMode.NEW, ComposeMode.DRAFT -> R.string.school_mail_compose
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.school_mail_add_attachment))
                    }
                    if (state.sending) {
                        Box(Modifier.padding(horizontal = 16.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        TextButton(onClick = viewModel::send, enabled = !state.loading) { Text(stringResource(R.string.school_mail_send)) }
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.error?.let { ErrorText(it) }
            RecipientField(stringResource(R.string.school_mail_to), state.to, viewModel::setTo, enabled = !state.sending)
            if (state.showCcBcc) {
                RecipientField(stringResource(R.string.school_mail_cc), state.cc, viewModel::setCc, enabled = !state.sending)
                RecipientField(stringResource(R.string.school_mail_bcc), state.bcc, viewModel::setBcc, enabled = !state.sending)
            } else {
                TextButton(onClick = viewModel::showCcBcc) { Text(stringResource(R.string.school_mail_show_cc_bcc)) }
            }
            OutlinedTextField(
                value = state.subject,
                onValueChange = viewModel::setSubject,
                label = { Text(stringResource(R.string.school_mail_subject)) },
                singleLine = true,
                enabled = !state.sending,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                label = { Text(stringResource(R.string.school_mail_body)) },
                enabled = !state.sending,
                minLines = 10,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            state.attachments.forEach { attachment ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            Formatter.formatShortFileSize(context, attachment.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = { viewModel.removeAttachment(attachment.id) }, enabled = !state.sending) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove))
                    }
                }
            }
        }
    }

    if (showLeave) {
        TigerDuckDialog(
            onDismissRequest = { showLeave = false },
            title = stringResource(R.string.school_mail_leave_title),
            confirmText = stringResource(R.string.school_mail_save_draft),
            onConfirm = { showLeave = false; viewModel.saveDraft() },
            dismissText = stringResource(R.string.school_mail_keep_editing),
            onDismiss = { showLeave = false },
        ) {
            TextButton(onClick = { showLeave = false; viewModel.discard() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.school_mail_discard), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecipientField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
    )
}

@Composable
private fun ErrorText(error: ComposeError) {
    val text = when (error) {
        is ComposeError.InvalidRecipients -> stringResource(R.string.school_mail_invalid_recipients).replaceIosArg(1, error.tokens.joinToString(", "))
        ComposeError.NoRecipient -> stringResource(R.string.school_mail_no_recipient)
        ComposeError.TooLarge -> stringResource(R.string.school_mail_too_large)
        is ComposeError.SendFailed -> stringResource(R.string.school_mail_send_failed) + "\n" + stringResource(error.error.messageRes())
        is ComposeError.DraftFailed -> stringResource(error.error.messageRes())
        is ComposeError.LoadFailed -> stringResource(error.error.messageRes())
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Name, size and type of a picked document; the stream opens only when sending. */
private fun describe(context: Context, uri: Uri): ComposeAttachment? {
    val resolver = context.contentResolver
    var name: String? = null
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0L
    return ComposeAttachment(
        id = uri.toString(),
        fileName = TextCleaning.clean(name).ifBlank { "attachment" },
        contentType = resolver.getType(uri) ?: "application/octet-stream",
        sizeBytes = size,
        source = ComposeAttachment.Source.Local { resolver.openInputStream(uri) ?: throw IOException("cannot open $uri") },
    )
}
