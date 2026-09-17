package org.ntust.app.tigerduck.ui.screen.mail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.IoDispatcher
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.compose.OutgoingAttachment
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.store.MailCache
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

/** A file to send: picked on this device, or carried over from the mail being forwarded or the draft. */
class ComposeAttachment(
    val id: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val source: Source,
) {
    /** [Local]'s [sizeBytes] is the raw (decoded) file size -- base64 growth still applies when
     *  sizing the outgoing mail. [Original]'s [sizeBytes] is the *encoded* octet count IMAP
     *  already reported for the original mail's attachment (spec's BODYSTRUCTURE size) -- it must
     *  never be grown again. */
    sealed interface Source {
        class Local(val open: () -> InputStream) : Source
        data class Original(val folder: String, val uid: Long, val partId: String) : Source
    }
}

/**
 * Plain-text compose (spec §6.4, §8.4). A failed send keeps every field; there is no outbox.
 *
 * Nothing here survives process death (round 3 drops that entirely -- it kept producing new
 * state bugs: a blank restore above a persistence cap, restored edits silently losing `dirty`,
 * and prefill re-running on an ordinary Activity recreation and wiping picked files). Losing
 * typed text to a process death while the picker is open is an accepted, ledgered trade-off; the
 * brief never required surviving it.
 */
@HiltViewModel
class SchoolMailComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val cache: MailCache,
    private val pickedAttachmentReader: PickedAttachmentReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val mode: ComposeMode = runCatching { ComposeMode.valueOf(savedStateHandle.get<String>("mode").orEmpty()) }
        .getOrDefault(ComposeMode.NEW)
    private val sourceFolder: String = savedStateHandle.get<String>("folder").orEmpty()
    private val sourceUid: Long = savedStateHandle.get<Long>("uid") ?: -1L

    sealed interface ComposeError {
        data class InvalidRecipients(val tokens: List<String>) : ComposeError
        data object NoRecipient : ComposeError
        /** Also shown for a picked attachment whose size could not be determined at all (spec:
         *  never silently drop it -- an unmeasurable file is treated the same as an over-budget
         *  one, since it can't be proven to fit either). */
        data object TooLarge : ComposeError
        data class SendFailed(val error: MailError) : ComposeError
        data class DraftFailed(val error: MailError) : ComposeError
    }

    data class Fields(
        val to: String = "",
        val cc: String = "",
        val bcc: String = "",
        val subject: String = "",
        val body: String = "",
        val attachmentIds: List<String> = emptyList(),
    )

    data class UiState(
        val loading: Boolean = false,
        val to: String = "",
        val cc: String = "",
        val bcc: String = "",
        val showCcBcc: Boolean = false,
        val subject: String = "",
        val body: String = "",
        val attachments: List<ComposeAttachment> = emptyList(),
        /** Picked documents still being described/measured off the [io] dispatcher -- a count,
         *  not a flag, so two overlapping picks don't clear each other's pending state. */
        val pendingPicks: Int = 0,
        val sending: Boolean = false,
        /** A failed [prefill]/[retryPrefill], kept entirely separate from [error]: an attachment
         *  change, an empty pick, or a send/save validation error or failure must never clear the
         *  Retry action this drives -- only a successful load does. */
        val loadError: MailError? = null,
        val error: ComposeError? = null,
        val done: Boolean = false,
        val savedDraft: Boolean = false,
        internal val baseline: Fields = Fields(),
    ) {
        internal val fields get() = Fields(to, cc, bcc, subject, body, attachments.map { it.id })
        /** Anything changed since the screen opened — leaving asks to save a draft (spec §6.4). */
        val dirty: Boolean get() = fields != baseline
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var prefillStarted = false
    private var inReplyTo: String? = null
    private var references: String? = null

    /** True only once [prefill]/[retryPrefill] actually loaded the original mail. A form whose
     *  source never loaded must never be treated as replacing a draft, discarding one, or
     *  answering an original it never confirmed. */
    private var sourceLoaded = false

    /** Which text fields the user has typed into this session (in-memory only). A successful load
     *  keeps an edited field's current value instead of overwriting it with the freshly computed
     *  one -- both on the very first [prefill] (unlikely to matter, since fields start blank) and
     *  on [retryPrefill] after a failure, which is the case this actually protects. */
    private class Edited {
        var to = false
        var cc = false
        var subject = false
        var body = false
    }
    private val edited = Edited()

    /** Starts the one load this form ever needs. A second call -- the screen's `LaunchedEffect`
     *  re-entering after an ordinary Activity recreation (a dark-mode switch, say), not a process
     *  death -- does nothing, whether the first load is still running, already succeeded, or
     *  already failed. Only [retryPrefill] can start another. */
    fun prefill(labels: ComposePrefill.Labels) {
        if (prefillStarted) return
        prefillStarted = true
        runPrefill(labels)
    }

    /** Retries after a failed [prefill]. Refuses to overlap a load already running ("never two at
     *  once") but is otherwise unguarded -- the screen only ever wires this to the Retry action it
     *  shows while [UiState.loadError] is set. */
    fun retryPrefill(labels: ComposePrefill.Labels) {
        if (_state.value.loading) return
        runPrefill(labels)
    }

    private fun runPrefill(labels: ComposePrefill.Labels) {
        if (mode == ComposeMode.NEW || sourceUid < 0) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val original = repository.summary(sourceFolder, sourceUid) ?: throw MailError.Protocol("message is gone")
                val body = repository.body(sourceFolder, sourceUid)
                val text = body.plain
                    ?: body.html?.let { HtmlSanitizer.plainText(HtmlSanitizer.sanitize(it, allowRemoteImages = false).html) }.orEmpty()
                val self = repository.selfAddress().address
                val draft = when (mode) {
                    ComposeMode.REPLY -> ComposePrefill.reply(original, text, all = false, selfAddress = self, labels = labels)
                    ComposeMode.REPLY_ALL -> ComposePrefill.reply(original, text, all = true, selfAddress = self, labels = labels)
                    ComposeMode.FORWARD -> ComposePrefill.forward(original, text, labels)
                    ComposeMode.DRAFT, ComposeMode.NEW -> ComposePrefill.draft(original, text)
                }
                val carried = if (mode == ComposeMode.FORWARD || mode == ComposeMode.DRAFT) {
                    body.attachments.map {
                        ComposeAttachment("orig-${it.partId}", it.fileName, it.contentType, it.sizeBytes,
                            ComposeAttachment.Source.Original(sourceFolder, sourceUid, it.partId))
                    }
                } else emptyList()
                inReplyTo = draft.inReplyTo
                references = draft.references
                sourceLoaded = true
                _state.update {
                    val to = if (edited.to) it.to else draft.to
                    val cc = if (edited.cc) it.cc else draft.cc
                    val subject = if (edited.subject) it.subject else draft.subject
                    val body2 = if (edited.body) it.body else draft.body
                    val next = it.copy(
                        loading = false, to = to, cc = cc, subject = subject, body = body2,
                        showCcBcc = it.showCcBcc || cc.isNotBlank() || it.bcc.isNotBlank(),
                        // Local picks made while the load had failed are kept; the carried
                        // originals are simply added alongside them, never replacing the list.
                        attachments = it.attachments + carried,
                    )
                    // The baseline is always this fresh prefill's own fields, never the user's
                    // edits -- an edit made before or after a retry must still leave the form
                    // dirty. Bcc is never part of a prefilled draft (spec §8.4), so it stays "" in
                    // the baseline regardless of what's currently typed there.
                    next.copy(baseline = Fields(draft.to, draft.cc, "", draft.subject, draft.body, carried.map { c -> c.id }))
                }
            } catch (e: MailError) {
                if (e is MailError.AuthFailed) account.onAuthFailure()
                _state.update { it.copy(loading = false, loadError = e) }
            }
        }
    }

    fun setTo(value: String) { edited.to = true; _state.update { it.copy(to = value, error = null) } }
    fun setCc(value: String) { edited.cc = true; _state.update { it.copy(cc = value, error = null) } }
    fun setBcc(value: String) = _state.update { it.copy(bcc = value, error = null) }
    fun setSubject(value: String) { edited.subject = true; _state.update { it.copy(subject = value) } }
    fun setBody(value: String) { edited.body = true; _state.update { it.copy(body = value) } }
    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun addAttachments(list: List<ComposeAttachment>) =
        _state.update { s -> s.copy(attachments = s.attachments + list.filter { a -> s.attachments.none { it.id == a.id } }, error = null) }

    fun removeAttachment(id: String) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }, error = null) }

    /**
     * Resolves each picked document's metadata off the injected [io] dispatcher (never Main) via
     * [pickedAttachmentReader], then merges the results in like any other attachment.
     * [UiState.pendingPicks] tracks the in-flight count so [send]/[saveDraft] can refuse to run
     * against a form that isn't done changing yet. A `null` result (the reader couldn't determine
     * a size at all) is never silently dropped -- it shows the same [ComposeError.TooLarge] an
     * over-budget attachment would.
     */
    fun addPicked(uris: List<Uri?>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(pendingPicks = it.pendingPicks + 1) }
        viewModelScope.launch {
            try {
                val described = withContext(io) { uris.map(pickedAttachmentReader::describe) }
                addAttachments(described.filterNotNull())
                if (described.any { it == null }) _state.update { it.copy(error = ComposeError.TooLarge) }
            } finally {
                _state.update { it.copy(pendingPicks = it.pendingPicks - 1) }
            }
        }
    }

    fun send() {
        val s = _state.value
        if (s.sending || s.loading || s.pendingPicks > 0) return
        val to = ComposeRules.parseRecipients(s.to)
        val cc = ComposeRules.parseRecipients(s.cc)
        val bcc = ComposeRules.parseRecipients(s.bcc)
        val invalid = to.invalid + cc.invalid + bcc.invalid
        val error = when {
            invalid.isNotEmpty() -> ComposeError.InvalidRecipients(invalid)
            to.addresses.isEmpty() && cc.addresses.isEmpty() && bcc.addresses.isEmpty() -> ComposeError.NoRecipient
            !fitsSizeLimit(s) -> ComposeError.TooLarge
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        withStaged(onError = { ComposeError.SendFailed(it) }) { attachments ->
            val mail = OutgoingMail(repository.selfAddress(), to.addresses, cc.addresses, bcc.addresses,
                s.subject.trim(), s.body, attachments, inReplyTo, references)
            val answered = if (sourceLoaded && (mode == ComposeMode.REPLY || mode == ComposeMode.REPLY_ALL)) sourceFolder to sourceUid else null
            repository.send(mail, answered)
            if (sourceLoaded && mode == ComposeMode.DRAFT) discardSentDraft()
            _state.update { it.copy(done = true) }
        }
    }

    /**
     * Best-effort: the mail already sent successfully, so a failure here (e.g. a
     * [MailError.FolderChanged] against the drafts folder) must never surface as a send failure --
     * it only leaves the old draft copy behind, which is harmless. Cancellation is not "best
     * effort" and always propagates.
     */
    private suspend fun discardSentDraft() {
        try {
            repository.discardDraft(sourceUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left in place; see kdoc above.
        }
    }

    fun saveDraft() {
        val s = _state.value
        if (s.sending || s.loading || s.pendingPicks > 0) return
        val to = ComposeRules.parseRecipients(s.to)
        val cc = ComposeRules.parseRecipients(s.cc)
        val bcc = ComposeRules.parseRecipients(s.bcc)
        val invalid = to.invalid + cc.invalid + bcc.invalid
        // A draft may legitimately have no recipients yet -- only a token that couldn't be read
        // at all, or an over-budget attachment, blocks saving.
        val error = when {
            invalid.isNotEmpty() -> ComposeError.InvalidRecipients(invalid)
            !fitsSizeLimit(s) -> ComposeError.TooLarge
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        withStaged(onError = { ComposeError.DraftFailed(it) }) { attachments ->
            val mail = OutgoingMail(repository.selfAddress(), to.addresses, cc.addresses, bcc.addresses,
                s.subject.trim(), s.body, attachments, inReplyTo, references)
            repository.saveDraft(mail, replacingUid = if (sourceLoaded && mode == ComposeMode.DRAFT) sourceUid else null)
            _state.update { it.copy(done = true, savedDraft = true) }
        }
    }

    fun discard() = _state.update { it.copy(done = true) }

    /** [ComposeAttachment.Source.Local] sizes are raw and still need base64 growth; [Source.Original]
     *  sizes are already the encoded octet count IMAP reported and must be added as-is. */
    private fun fitsSizeLimit(s: UiState): Boolean {
        val raw = s.attachments.filter { it.source is ComposeAttachment.Source.Local }.map { it.sizeBytes }
        val encoded = s.attachments.filter { it.source is ComposeAttachment.Source.Original }.map { it.sizeBytes }
        return ComposeRules.fitsSizeLimit(s.body, raw, encoded)
    }

    /** Downloads carried-over attachments to temp files for the duration of [block], then deletes them. */
    private fun withStaged(onError: (MailError) -> ComposeError, block: suspend (List<OutgoingAttachment>) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            val staging = File(cache.attachmentsDir, "outgoing-${UUID.randomUUID()}")
            try {
                val attachments = _state.value.attachments.map { a ->
                    when (val src = a.source) {
                        is ComposeAttachment.Source.Local -> OutgoingAttachment(a.fileName, a.contentType, a.sizeBytes, src.open)
                        // Everything -- open, the write through repository.writeAttachment, the
                        // close .use performs, and length() -- runs inside this one withContext(io)
                        // so none of it (least of all the FileOutputStream close, which can block
                        // on a flush) lands on Main.
                        is ComposeAttachment.Source.Original -> withContext(io) {
                            val file = File(staging, "${a.id}-${SchoolMailMessageViewModel.safeFileName(a.fileName)}")
                            staging.mkdirs()
                            file.outputStream().use { out -> repository.writeAttachment(src.folder, src.uid, src.partId, out) }
                            OutgoingAttachment(a.fileName, a.contentType, file.length()) { file.inputStream() }
                        }
                    }
                }
                block(attachments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = MailErrors.classify(e)
                if (error is MailError.AuthFailed) account.onAuthFailure()
                _state.update { it.copy(error = onError(error)) }
            } finally {
                // Back is refused while sending (the screen's BackHandler), but this still runs
                // under NonCancellable like SchoolMailMessageViewModel's own cleanup: a cancelled
                // job (process death, config change tearing down the nav entry) must not skip
                // deleting the staged files and leak them into cache/attachments forever.
                withContext(io + NonCancellable) { staging.deleteRecursively() }
                _state.update { it.copy(sending = false) }
            }
        }
    }
}
