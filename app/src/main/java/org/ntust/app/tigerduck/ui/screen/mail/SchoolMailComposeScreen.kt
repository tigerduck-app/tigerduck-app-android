package org.ntust.app.tigerduck.ui.screen.mail

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.navigation.AskBeforeLeaving
import org.ntust.app.tigerduck.ui.navigation.HeldMove
import org.ntust.app.tigerduck.ui.navigation.LeaveGuard
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.ComposeError
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.RecipientSlot
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.util.replaceIosArg

/**
 * How long a confirmed send is held for Undo: [SchoolMailComposeViewModel.SEND_HOLD_MS], or as
 * long as the accessibility settings give an action ("Time to take action"), so a screen reader
 * user has the time to reach Undo on the bar.
 */
internal fun sendHoldMillis(accessibility: AccessibilityManager?): Long =
    accessibility?.calculateRecommendedTimeoutMillis(
        SchoolMailComposeViewModel.SEND_HOLD_MS, containsIcons = false, containsText = true, containsControls = true,
    ) ?: SchoolMailComposeViewModel.SEND_HOLD_MS

/** Spec §6.4 — SubscriptionRuleEditorScreen's full-screen editor with Send in the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolMailComposeScreen(
    onDone: () -> Unit,
    /** What a bottom-bar tap or a link asks before it pops this page. */
    leaveGuard: LeaveGuard,
    viewModel: SchoolMailComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLeave by remember { mutableStateOf(false) }
    // A tab or a link waiting on the answer to Save draft, which moves the app on once the draft
    // is saved or discarded; none when back asked, which closes this page.
    val heldMove = remember { HeldMove() }
    var showSendConfirm by remember { mutableStateOf(false) }

    val quoteHeader = stringResource(R.string.school_mail_quote_header)
    val forwardedHeader = stringResource(R.string.school_mail_forwarded_header)
    val fromLabel = stringResource(R.string.school_mail_forward_from)
    val dateLabel = stringResource(R.string.school_mail_forward_date)
    val subjectLabel = stringResource(R.string.school_mail_forward_subject)
    val toLabel = stringResource(R.string.school_mail_details_to)
    val labels = ComposePrefill.Labels(
        quoteHeader = { date, sender -> quoteHeader.replaceIosArg(1, date).replaceIosArg(2, sender) },
        forwardedHeader = forwardedHeader,
        from = { fromLabel.replaceIosArg(1, it) },
        date = { dateLabel.replaceIosArg(1, it) },
        subject = { subjectLabel.replaceIosArg(1, it) },
        to = { toLabel.replaceIosArg(1, it) },
    )
    LaunchedEffect(Unit) { viewModel.prefill(labels) }
    LaunchedEffect(state.done) {
        if (!state.done) return@LaunchedEffect
        if (state.savedDraft) Toast.makeText(context, R.string.school_mail_saved, Toast.LENGTH_SHORT).show()
        // A missing sent copy is not told here: the list and the mail this goes back to show it
        // until it is dismissed (SentCopyNoticeBanner).
        heldMove.release(onDone)
    }
    // A save that failed leaves the user writing, and nothing they asked for before then may move
    // the app once they later send or discard.
    LaunchedEffect(state.error) { if (state.error != null) heldMove.drop() }

    // A confirmed send is held a few seconds with Undo on a bar, which goes when the hold ends,
    // either way (tigerduck-app#555).
    val snackbar = remember { SnackbarHostState() }
    val sendingHold = stringResource(R.string.school_mail_sending_hold)
    val undoSend = stringResource(R.string.school_mail_undo_send)
    val sendUndone = stringResource(R.string.school_mail_send_undone)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(state.holding) {
        if (!state.holding) return@LaunchedEffect
        // In place of what an earlier Undo said, at once rather than after it.
        snackbar.currentSnackbarData?.dismiss()
        val answer = snackbar.showSnackbar(sendingHold, actionLabel = undoSend, duration = SnackbarDuration.Indefinite)
        if (answer == SnackbarResult.ActionPerformed) viewModel.undoSend()
    }
    LaunchedEffect(state.undone) {
        if (!state.undone) return@LaunchedEffect
        snackbar.showSnackbar(sendUndone)
        viewModel.acknowledgeUndone()
    }
    // Undo is out of reach once the screen leaves the foreground, for another app, the recent apps
    // or the screen turning off, and the app may be closed from there: the held mail goes at once.
    // Not for a dark-mode or font-size change, which rebuilds the activity with the screen still on it.
    val activity = LocalActivity.current
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { if (activity?.isChangingConfigurations != true) viewModel.sendHeldNow() }
    }

    // While sending, back (and the nav-up icon, which shares this) does nothing: popping the
    // screen mid-send would clear the ViewModel and cancel the send, the original would never be
    // marked answered, a sent draft would never be discarded, and a failure would have nowhere to
    // show (spec §8.4).
    val leave = {
        when {
            // Ignored, so a tab or a link the save is for still goes once it is saved.
            state.sending -> Unit
            state.dirty -> { heldMove.drop(); showLeave = true }
            else -> { heldMove.drop(); onDone() }
        }
    }
    BackHandler(onBack = leave)
    // A bottom-bar tap or a link would pop this page as back does, so it asks the same: nothing
    // while sending, Save draft with text to lose. Once saved or discarded, as it slides away, it
    // asks nothing more.
    AskBeforeLeaving(leaveGuard) { move ->
        if (heldMove.ask(move, left = state.done, cannotLeave = state.sending, nothingToLose = !state.dirty)) showLeave = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addPicked(uris)
    }
    // Photos through the system Photo Picker, the usual way to send one, as the iPhone's Photos
    // picker sits beside its file importer. The same size checks as a picked file.
    val pickPhotos = rememberPhotoPicker(viewModel::addPicked)
    val fieldsEnabled = !state.sending && !state.loading

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                    IconButton(onClick = pickPhotos, enabled = fieldsEnabled) {
                        Icon(Icons.Filled.Image, contentDescription = stringResource(R.string.class_table_import_choose_photo))
                    }
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = fieldsEnabled) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.school_mail_add_attachment))
                    }
                    // A pick still being described/measured off the io dispatcher shows its own
                    // spinner here, distinct from the send spinner -- Send/Save must not run
                    // against a form whose attachment list isn't done changing yet.
                    if (state.pendingPicks > 0) {
                        Box(Modifier.padding(horizontal = 8.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                    if (state.sending) {
                        Box(Modifier.padding(horizontal = 16.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        TextButton(
                            onClick = { if (viewModel.requestSend()) showSendConfirm = true },
                            enabled = !state.loading && state.pendingPicks == 0,
                        ) {
                            Text(stringResource(R.string.school_mail_send))
                        }
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
            state.loadError?.let { LoadErrorBanner(it, onRetry = { viewModel.retryPrefill(labels) }) }
            state.error?.let { ErrorText(it) }
            if (state.lostAttachments.isNotEmpty()) {
                // Inline only: a mail opened again for review, which nothing failed on just now.
                Text(
                    stringResource(R.string.school_mail_held_attachments_missing).replaceIosArg(1, state.lostAttachments.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            MailRecipientTokenField(
                stringResource(R.string.school_mail_to), state.to, { viewModel.updateRecipients(RecipientSlot.TO, it) },
                enabled = fieldsEnabled, suggestions = state.suggestions(RecipientSlot.TO),
            )
            if (state.showCcBcc) {
                MailRecipientTokenField(
                    stringResource(R.string.school_mail_cc), state.cc, { viewModel.updateRecipients(RecipientSlot.CC, it) },
                    enabled = fieldsEnabled, suggestions = state.suggestions(RecipientSlot.CC),
                )
                MailRecipientTokenField(
                    stringResource(R.string.school_mail_bcc), state.bcc, { viewModel.updateRecipients(RecipientSlot.BCC, it) },
                    enabled = fieldsEnabled, suggestions = state.suggestions(RecipientSlot.BCC),
                )
            } else {
                TextButton(onClick = viewModel::showCcBcc, enabled = fieldsEnabled) { Text(stringResource(R.string.school_mail_show_cc_bcc)) }
            }
            OutlinedTextField(
                value = state.subject,
                onValueChange = viewModel::setSubject,
                label = { Text(stringResource(R.string.school_mail_subject)) },
                singleLine = true,
                enabled = fieldsEnabled,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                label = { Text(stringResource(R.string.school_mail_body)) },
                enabled = fieldsEnabled,
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
                        // An original (server) attachment's sizeBytes is already the *encoded*
                        // octet count -- shown as an approximate decoded size (base64's 3/4 ratio)
                        // so the row reads like the actual file size, not the wire size.
                        val displaySize = if (attachment.source is ComposeAttachment.Source.Original) attachment.sizeBytes * 3 / 4 else attachment.sizeBytes
                        Text(
                            Formatter.formatShortFileSize(context, displaySize),
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

    state.error?.let { error ->
        if (!state.errorAcknowledged) ComposeErrorDialog(error, onDismiss = viewModel::acknowledgeError)
    }

    // Guarded by state.sending as well as the click site: a send that started while this was up
    // (there is no such path today, but nothing about the state shape rules it out later) must not
    // leave the confirmation floating over an in-flight send.
    if (showSendConfirm && !state.sending) {
        TigerDuckDialog(
            onDismissRequest = { showSendConfirm = false },
            title = stringResource(R.string.school_mail_send_confirm_title),
            confirmText = stringResource(R.string.school_mail_send),
            onConfirm = { showSendConfirm = false; heldMove.drop(); viewModel.send(sendHoldMillis(accessibility)) },
            dismissText = stringResource(R.string.school_mail_keep_editing),
            onDismiss = { showSendConfirm = false },
        )
    }

    if (showLeave) {
        TigerDuckDialog(
            onDismissRequest = { showLeave = false; heldMove.drop() },
            title = stringResource(R.string.school_mail_leave_title),
            confirmText = stringResource(R.string.school_mail_save_draft),
            onConfirm = { showLeave = false; viewModel.saveDraft() },
            confirmEnabled = state.pendingPicks == 0,
            dismissText = stringResource(R.string.school_mail_keep_editing),
            onDismiss = { showLeave = false; heldMove.drop() },
        ) {
            TextButton(onClick = { showLeave = false; viewModel.discard() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.school_mail_discard), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Launches the system Photo Picker for images and hands back what was picked. Where the platform
 * has no picker of its own, androidx falls back to the document picker for images, with no Play
 * services involved, so the F-Droid build gets the same button.
 */
@Composable
internal fun rememberPhotoPicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** The one place that turns a [ComposeError] into display text, shared by the inline [ErrorText]
 *  and [ComposeErrorDialog] so the two can never drift apart. */
@Composable
private fun composeErrorMessage(error: ComposeError): String = when (error) {
    is ComposeError.InvalidRecipients -> stringResource(R.string.school_mail_invalid_recipients).replaceIosArg(1, error.tokens.joinToString(", "))
    ComposeError.NoRecipient -> stringResource(R.string.school_mail_no_recipient)
    ComposeError.TooLarge -> stringResource(R.string.school_mail_too_large)
    is ComposeError.SendFailed -> sendFailureLines(error.error).map { stringResource(it) }.joinToString("\n")
    is ComposeError.DraftFailed -> stringResource(error.error.messageRes())
}

/**
 * The lines a failed send shows, as string resources: that it failed and why, or, for one that
 * may have gone out ([MailError.SendUnconfirmed]), only that, as the iPhone says it in place of
 * the failure line.
 */
internal fun sendFailureLines(error: MailError): List<Int> =
    if (error is MailError.SendUnconfirmed) {
        listOf(error.messageRes())
    } else {
        listOf(R.string.school_mail_send_failed, error.messageRes())
    }

@Composable
private fun ErrorText(error: ComposeError) {
    Text(composeErrorMessage(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** A must-dismiss popup for [error], raised *in addition to* [ErrorText] -- never instead of it.
 *  Dismissing only flips [SchoolMailComposeViewModel.acknowledgeError]; it never clears
 *  [SchoolMailComposeViewModel.UiState.error] itself, so the inline message stays on screen. */
@Composable
private fun ComposeErrorDialog(error: ComposeError, onDismiss: () -> Unit) {
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.feature_school_mail),
        message = composeErrorMessage(error),
        confirmText = stringResource(R.string.settings_acknowledged),
        onConfirm = onDismiss,
    )
}

/** A failed [SchoolMailComposeViewModel.prefill]/[SchoolMailComposeViewModel.retryPrefill], kept
 *  in its own state slot: this banner (and its Retry action) stays up regardless of any attachment
 *  change or send/save error happening alongside it, until a load actually succeeds. */
@Composable
private fun LoadErrorBanner(error: MailError, onRetry: () -> Unit) {
    Column {
        Text(stringResource(error.messageRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
