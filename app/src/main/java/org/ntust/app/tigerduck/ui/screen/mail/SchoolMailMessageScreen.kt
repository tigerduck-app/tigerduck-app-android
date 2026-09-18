package org.ntust.app.tigerduck.ui.screen.mail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.resolveAttachmentMimeType
import org.ntust.app.tigerduck.mail.warning.MailWarning
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.Content
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.ViewMode
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.util.replaceIosArg

private val WarningOrange = Color(0xFFFF9500)
private const val SOURCE_CHUNK_LINES = 200

/** Spec §6.3 — AnnouncementDetail's layout over one mail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolMailMessageScreen(
    browserPreference: String,
    onBack: () -> Unit,
    onCompose: (mode: ComposeMode, folder: String, uid: Long) -> Unit,
    viewModel: SchoolMailMessageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var pendingLink by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingSavePart by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.actionError) {
        val error = state.actionError ?: return@LaunchedEffect
        Toast.makeText(context, error.messageRes(), Toast.LENGTH_SHORT).show()
        viewModel.dismissActionError()
    }
    LaunchedEffect(state.savedCount) {
        if (state.savedCount == 0) return@LaunchedEffect
        Toast.makeText(context, R.string.school_mail_saved, Toast.LENGTH_SHORT).show()
        viewModel.consumeSaved()
    }
    LaunchedEffect(state.openRequest) {
        val request = state.openRequest ?: return@LaunchedEffect
        openAttachment(context, request)
        viewModel.consumeOpenRequest()
    }

    val ready = state.content as? Content.Ready
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val attachment = ready?.body?.attachments?.firstOrNull { it.partId == pendingSavePart }
        pendingSavePart = null
        if (uri != null && attachment != null) {
            viewModel.saveAttachment(
                attachment,
                // "wt": write + truncate. Plain "w" leaves a provider free to not truncate, so
                // overwriting a larger existing file with a smaller attachment would leave its
                // old trailing bytes in place.
                open = { context.contentResolver.openOutputStream(uri, "wt") },
                onFailure = {
                    // Best-effort: not every document provider supports deleting what it just
                    // handed out (some throw UnsupportedOperationException), so a partial write
                    // is cleaned up where possible and left alone otherwise.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                },
            )
        }
    }
    // Confirmation (needsConfirmation) already happened, if needed, before saveRequest was set --
    // this only launches the SAF picker the confirmed/unconfirmed save asked for.
    LaunchedEffect(state.saveRequest) {
        val attachment = state.saveRequest ?: return@LaunchedEffect
        pendingSavePart = attachment.partId
        saveLauncher.launch(SchoolMailMessageViewModel.safeFileName(attachment.fileName))
        viewModel.consumeSaveRequest()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (ready != null && state.folderKind != SpecialFolder.DRAFTS) {
                        IconButton(onClick = { onCompose(ComposeMode.REPLY, viewModel.folder, viewModel.uid) }) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.school_mail_reply))
                        }
                        IconButton(onClick = { onCompose(ComposeMode.REPLY_ALL, viewModel.folder, viewModel.uid) }) {
                            Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = stringResource(R.string.school_mail_reply_all))
                        }
                        IconButton(onClick = { onCompose(ComposeMode.FORWARD, viewModel.folder, viewModel.uid) }) {
                            Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.school_mail_forward))
                        }
                    }
                    if (state.content !is Content.Loading) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.school_mail_view_mode))
                            }
                            MessageMenu(
                                expanded = menuOpen,
                                mode = state.mode,
                                canFormat = ready?.html != null,
                                onDismiss = { menuOpen = false },
                                onMode = { menuOpen = false; viewModel.selectMode(it) },
                                onMarkUnread = { menuOpen = false; viewModel.markUnread() },
                                onMove = { menuOpen = false; showMove = true },
                                onDelete = { menuOpen = false; viewModel.delete() },
                            )
                        }
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val content = state.content) {
            Content.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is Content.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyStateView(
                    icon = Icons.Filled.WarningAmber,
                    title = stringResource(R.string.school_mail_load_failed_title),
                    message = stringResource(content.error.messageRes()),
                )
            }
            is Content.LoadingBody -> Column(Modifier.fillMaxSize().padding(padding)) {
                MessageHeader(content.summary)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is Content.Ready -> {
                // Splitting a source that can run to hundreds of KB is too expensive to redo on
                // every recomposition, and LazyColumn's content lambda is not a composable scope,
                // so it cannot be remembered where the chunks are consumed. Each chunk is joined
                // back into its final string here too, so the items only read it.
                val sourceChunks = remember(state.source) {
                    state.source?.lines()?.chunked(SOURCE_CHUNK_LINES)?.map { it.joinToString("\n") }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item(key = "header") { MessageHeader(content.summary) }
                    if (state.parseFailed) {
                        item(key = "parse-failed") { WarningCard(stringResource(R.string.school_mail_parse_failed), null) }
                    }
                    items(content.warnings, key = { it.toString() }) { warning -> WarningCard(warningTitle(warning), null) }
                    val html = content.html
                    if (state.mode == ViewMode.FORMATTED && html != null && html.blockedRemoteImages > 0 && !state.remoteImagesAllowed) {
                        item(key = "remote-images") {
                            WarningCard(stringResource(R.string.school_mail_remote_images_blocked)) {
                                TextButton(onClick = viewModel::loadRemoteImages) { Text(stringResource(R.string.school_mail_load_images)) }
                            }
                        }
                    }
                    when (state.mode) {
                        ViewMode.FORMATTED -> if (html != null && content.document != null) item(key = "html") {
                            val document = content.document
                            // Chromium hands shouldInterceptRequest its own normalized request URL,
                            // which a raw <img src> string won't match byte-for-byte even when it's
                            // the same URL, so both sides go through the same normalizer.
                            val allowedRemoteUrls = remember(html.remoteImageUrls, state.remoteImagesAllowed) {
                                if (state.remoteImagesAllowed) {
                                    html.remoteImageUrls.map { SchoolMailMessageViewModel.normalizedHref(it) }.toSet()
                                } else {
                                    emptySet()
                                }
                            }
                            // Spec §9.3: HTML always sits on white paper, dark mode included.
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                MailWebView(
                                    document = document.html,
                                    allowedRemoteUrls = allowedRemoteUrls,
                                    linkCount = document.links.size,
                                    onLink = { pendingLink = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        ViewMode.PLAIN -> item(key = "plain") {
                            SelectionContainer {
                                Text(
                                    content.plain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                        ViewMode.SOURCE -> {
                            val source = state.source
                            val chunks = sourceChunks
                            if (source == null || chunks == null) {
                                item(key = "source-loading") {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        if (state.sourceLoading) CircularProgressIndicator()
                                    }
                                }
                            } else {
                                item(key = "source-copy") {
                                    TextButton(onClick = { copyToClipboard(context, source) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                        Text(stringResource(R.string.school_mail_copy_all))
                                    }
                                }
                                items(chunks.size, key = { "source-$it" }) { index ->
                                    SelectionContainer {
                                        Text(
                                            chunks[index],
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (content.body.attachments.isNotEmpty()) {
                        item(key = "attachments-header") {
                            SectionHeader(stringResource(R.string.school_mail_attachments), Modifier.padding(top = 16.dp))
                        }
                        items(content.body.attachments, key = { "att-${it.partId}" }) { attachment ->
                            AttachmentRow(
                                attachment = attachment,
                                downloading = attachment.partId in state.downloading,
                                onOpen = { viewModel.requestOpen(attachment) },
                                onSave = { viewModel.requestSave(attachment) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingLink?.let { index ->
        // MailWebView only calls onLink with an index it range-checked against the same
        // document.links it was given, so this is null only if the content changed underneath
        // since the tap; skip the dialog rather than show one with nothing to open.
        val target = viewModel.linkTarget(index)
        if (target != null) {
            LinkDialog(
                target = target,
                onOpen = {
                    pendingLink = null
                    openLink(context, target.href, browserPreference)
                },
                onDismiss = { pendingLink = null },
            )
        }
    }
    state.confirmAttachment?.let { pending ->
        // Spec lines 416/653 (「開啟或儲存前再確認一次」): the same confirmation, whether the
        // attachment is about to be opened or saved -- "Confirm" rather than "Open" on the
        // button since it now covers both.
        TigerDuckDialog(
            onDismissRequest = { viewModel.confirmAttachment(false) },
            title = stringResource(R.string.school_mail_risky_title),
            message = stringResource(R.string.school_mail_risky_message).replaceIosArg(1, pending.attachment.fileName),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { viewModel.confirmAttachment(true) },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
    if (state.confirmDelete) {
        TigerDuckDialog(
            onDismissRequest = { viewModel.confirmDelete(false) },
            title = stringResource(R.string.school_mail_delete_confirm_title),
            message = stringResource(R.string.school_mail_delete_confirm_message),
            confirmText = stringResource(R.string.school_mail_delete),
            onConfirm = { viewModel.confirmDelete(true) },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
    if (state.confirmDeleteForever) {
        TigerDuckDialog(
            onDismissRequest = { viewModel.confirmDeleteForever(false) },
            title = stringResource(R.string.school_mail_delete_forever_title),
            message = stringResource(R.string.school_mail_delete_forever_message),
            confirmText = stringResource(R.string.school_mail_delete),
            onConfirm = { viewModel.confirmDeleteForever(true) },
            dismissText = stringResource(R.string.action_cancel),
            confirmColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        )
    }
    if (showMove) {
        val folders = state.folders
        TigerDuckDialog(
            onDismissRequest = { showMove = false },
            title = stringResource(R.string.school_mail_move_to),
            dismissText = stringResource(R.string.action_cancel),
        ) {
            viewModel.moveTargets().forEach { name ->
                val kind = folders?.kindOf(name)
                TextButton(onClick = { showMove = false; viewModel.moveTo(name) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (kind != null) stringResource(kind.labelRes()) else name)
                }
            }
        }
    }
}

@Composable
private fun MessageMenu(
    expanded: Boolean,
    mode: ViewMode,
    canFormat: Boolean,
    onDismiss: () -> Unit,
    onMode: (ViewMode) -> Unit,
    onMarkUnread: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (canFormat) {
            ModeItem(stringResource(R.string.school_mail_view_formatted), mode == ViewMode.FORMATTED) { onMode(ViewMode.FORMATTED) }
        }
        ModeItem(stringResource(R.string.school_mail_view_plain), mode == ViewMode.PLAIN) { onMode(ViewMode.PLAIN) }
        ModeItem(stringResource(R.string.school_mail_view_source), mode == ViewMode.SOURCE) { onMode(ViewMode.SOURCE) }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.school_mail_mark_unread)) }, onClick = onMarkUnread)
        DropdownMenuItem(text = { Text(stringResource(R.string.school_mail_move_to)) }, onClick = onMove)
        HorizontalDivider()
        // Red, like deleting a course from the class table (ClassTableCourseCell) -- the one
        // entry in this menu that loses mail should read as destructive before it is tapped,
        // not only once the confirmation dialog is up.
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.school_mail_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
        )
    }
}

@Composable
private fun ModeItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            RadioButton(
                selected = selected,
                onClick = null
            )
        }
    )
}

/** Name plus the full address, always (spec §6.3); recipients collapsed behind a tap. */
@Composable
private fun MessageHeader(summary: MailSummary) {
    val cs = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            summary.subject.ifBlank { stringResource(R.string.school_mail_no_subject) },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        val from = summary.from
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    from?.display?.takeIf { it.isNotBlank() } ?: stringResource(R.string.school_mail_no_sender),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A bounce's `<MAILER-DAEMON>` leaves no address to print under the name; an
                // empty second line would just look like the address failed to load.
                if (from != null && from.isRoutable) {
                    Text(from.address, style = MaterialTheme.typography.bodySmall, color = cs.outline)
                }
            }
            if (MailWarnings.isExternalSender(from, summary.returnPath)) {
                Surface(shape = RoundedCornerShape(50), color = WarningOrange.copy(alpha = 0.18f)) {
                    Text(
                        stringResource(R.string.school_mail_external_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(MailDateFormat.full(summary.sentAt ?: summary.receivedAt), style = MaterialTheme.typography.labelSmall, color = cs.outline)
        val to = summary.to.joinToString(", ") { it.display }
        val cc = summary.cc.joinToString(", ") { it.display }
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(
                stringResource(R.string.school_mail_details_to).replaceIosArg(1, to),
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded && cc.isNotEmpty()) {
            Text(stringResource(R.string.school_mail_details_cc).replaceIosArg(1, cc), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The warning, with the address it is about on a second line -- unless there isn't one to name. */
private fun String.withAddress(address: String): String = if (address.isBlank()) this else "$this\n$address"

@Composable
private fun warningTitle(warning: MailWarning): String = when (warning) {
    is MailWarning.ExternalSender -> stringResource(R.string.school_mail_warning_external).withAddress(warning.address)
    is MailWarning.DisplayNameMismatch ->
        stringResource(R.string.school_mail_warning_display_name).withAddress(warning.actualAddress)
    MailWarning.PasswordBait -> stringResource(R.string.school_mail_warning_password)
    MailWarning.MistypedRecipient -> stringResource(R.string.school_mail_bounce_warning)
    is MailWarning.RiskyAttachments ->
        stringResource(R.string.school_mail_warning_attachment).replaceIosArg(1, warning.fileNames.joinToString(", "))
}

/** FdroidNoticeCard's look: ContentCard, WarningAmber in FF9500, titleSmall. */
@Composable
private fun WarningCard(title: String, action: (@Composable () -> Unit)?) {
    ContentCard(applyOuterPadding = false, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = WarningOrange)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
private fun AttachmentRow(attachment: MailAttachment, downloading: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        if (downloading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.school_mail_open)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/**
 * Spec §6.3: show the real host before leaving the app; a mismatch is spelled out in red.
 * [target]'s href is the one string the verdict judged and [onOpen] launches (for http(s), the
 * browser-like canonical form; see [SchoolMailMessageViewModel.linkTarget]). It can still carry
 * bidi control characters meant to disguise it, so it is shown only through
 * [TextCleaning.stripBidi]. A target that cannot be opened gets no Open button.
 */
@Composable
private fun LinkDialog(target: SchoolMailMessageViewModel.LinkTarget, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val verdict = target.verdict
    val displayHref = TextCleaning.stripBidi(target.href)
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.school_mail_link_title),
        confirmText = if (target.canOpen) stringResource(R.string.school_mail_open) else null,
        onConfirm = if (target.canOpen) onOpen else onDismiss,
        dismissText = stringResource(R.string.action_cancel),
    ) {
        Text(
            stringResource(R.string.school_mail_link_host).replaceIosArg(1, verdict.host.ifBlank { displayHref }),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (verdict.insecure) {
            Text(stringResource(R.string.school_mail_link_insecure), style = MaterialTheme.typography.bodySmall, color = WarningOrange)
        }
        if (verdict.punycode) {
            Text(stringResource(R.string.school_mail_link_punycode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (verdict.mismatch) {
            Text(
                stringResource(R.string.school_mail_link_mismatch)
                    .replaceIosArg(1, verdict.shownHost.orEmpty())
                    .replaceIosArg(2, verdict.host),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(displayHref, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun openLink(context: Context, href: String, browserPreference: String) {
    val uri = Uri.parse(href)
    when (uri.scheme?.lowercase()) {
        "http", "https" -> openMailLink(context, href, browserPreference)
        "mailto" -> runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else -> Unit
    }
}

private fun openAttachment(context: Context, request: SchoolMailMessageViewModel.OpenRequest) {
    // FileProvider.getUriForFile throws IllegalArgumentException for any file outside the
    // configured provider path, same as a startActivity failure -- both belong inside this one
    // guarded block so either shows the same error toast instead of crashing.
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mailfiles", request.file)
        val mimeType = resolveAttachmentMimeType(request.contentType, request.file.name)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndTypeAndNormalize(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, request.file.name))
    }.onFailure { Toast.makeText(context, R.string.school_mail_error_generic, Toast.LENGTH_SHORT).show() }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("mail source", text))
}
