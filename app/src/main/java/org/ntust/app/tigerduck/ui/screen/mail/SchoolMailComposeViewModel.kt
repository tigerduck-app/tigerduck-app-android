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

/** Plain-text compose (spec §6.4, §8.4). A failed send keeps every field; there is no outbox. */
@HiltViewModel
class SchoolMailComposeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
        data object TooLarge : ComposeError
        data class SendFailed(val error: MailError) : ComposeError
        data class DraftFailed(val error: MailError) : ComposeError
        data class LoadFailed(val error: MailError) : ComposeError
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
        /** Picked documents still being described/measured off the [io] dispatcher (Minor #4) --
         *  a count, not a flag, so two overlapping picks don't clear each other's pending state. */
        val pendingPicks: Int = 0,
        val sending: Boolean = false,
        val error: ComposeError? = null,
        val done: Boolean = false,
        val savedDraft: Boolean = false,
        internal val baseline: Fields = Fields(),
    ) {
        internal val fields get() = Fields(to, cc, bcc, subject, body, attachments.map { it.id })
        /** Anything changed since the screen opened — leaving asks to save a draft (spec §6.4). */
        val dirty: Boolean get() = fields != baseline
    }

    private val _state = MutableStateFlow(
        UiState(
            to = savedStateHandle.get<String>(KEY_TO).orEmpty(),
            cc = savedStateHandle.get<String>(KEY_CC).orEmpty(),
            bcc = savedStateHandle.get<String>(KEY_BCC).orEmpty(),
            subject = savedStateHandle.get<String>(KEY_SUBJECT).orEmpty(),
            body = savedStateHandle.get<String>(KEY_BODY).orEmpty(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Set the moment the user types into any field, and persisted (spec: survive process death
     * while a picker or the source fetch is in flight). This -- not a "prefill already ran" flag
     * -- is what [prefill] uses to decide whether restored/typed text should win over a freshly
     * recomputed one: persisting "already prefilled" instead (round 1's design) meant a restored
     * REPLY/REPLY_ALL lost In-Reply-To/References and its answered-marking, a restored DRAFT lost
     * its carried attachments and could be saved as a duplicate instead of replaced, and a death
     * mid-load restored a blank form with no way to retry. Prefill now always re-runs for
     * REPLY/REPLY_ALL/FORWARD/DRAFT; this flag only ever changes what it does with the *text*.
     */
    private var userEdited: Boolean = savedStateHandle.get<Boolean>(KEY_USER_EDITED) ?: false
        set(value) { field = value; savedStateHandle[KEY_USER_EDITED] = value }
    private var inReplyTo: String? = null
    private var references: String? = null

    /** True only once [prefill] actually loaded the original mail. A form whose source never
     *  loaded (prefill failed, or hasn't finished) must never be treated as replacing a draft,
     *  discarding one, or answering an original it never confirmed. */
    private var sourceLoaded = false

    /**
     * Fetches the original mail and fills the form from it; always re-runs (no "already ran"
     * guard) -- the screen calls this once on first composition and again from the Retry action
     * after a failed load. If [userEdited] text (typed this session, or restored after process
     * death) already exists, it wins: prefill only supplies what the user could never have typed
     * themselves -- threading headers, carried attachments, [sourceLoaded], and Cc/Bcc visibility
     * for whatever cc/bcc ends up showing. Otherwise it fills the text fields as a fresh
     * reply/forward/draft normally would.
     */
    fun prefill(labels: ComposePrefill.Labels) {
        if (mode == ComposeMode.NEW || sourceUid < 0) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
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
                if (!userEdited) persistFields(draft.to, draft.cc, _state.value.bcc, draft.subject, draft.body)
                _state.update {
                    val next = if (userEdited) {
                        it.copy(loading = false, showCcBcc = it.showCcBcc || it.cc.isNotBlank() || it.bcc.isNotBlank(), attachments = carried)
                    } else {
                        it.copy(
                            loading = false, to = draft.to, cc = draft.cc, subject = draft.subject, body = draft.body,
                            showCcBcc = draft.cc.isNotBlank(), attachments = carried,
                        )
                    }
                    next.copy(baseline = next.fields)
                }
            } catch (e: MailError) {
                if (e is MailError.AuthFailed) account.onAuthFailure()
                _state.update { it.copy(loading = false, error = ComposeError.LoadFailed(e)) }
            }
        }
    }

    fun setTo(value: String) = editField { it.copy(to = value) }
    fun setCc(value: String) = editField { it.copy(cc = value) }
    fun setBcc(value: String) = editField { it.copy(bcc = value) }
    fun setSubject(value: String) = editField { it.copy(subject = value) }
    fun setBody(value: String) = editField { it.copy(body = value) }
    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** Applies [transform], persists the result (Important #3: capped at [MAX_PERSISTED_CHARS]
     *  combined), and marks the form user-edited. A [ComposeError.LoadFailed] is kept rather than
     *  cleared -- editing a field is not a retry, so the Retry action must stay visible until a
     *  load actually succeeds; any other error (a stale invalid-recipient banner, say) still
     *  clears on edit as before. */
    private fun editField(transform: (UiState) -> UiState) {
        userEdited = true
        _state.update { s -> transform(s).copy(error = s.error as? ComposeError.LoadFailed) }
        val s = _state.value
        persistFields(s.to, s.cc, s.bcc, s.subject, s.body)
    }

    /** A `TransactionTooLargeException` on `onStop` is a hard crash, not a degraded experience --
     *  above the combined cap the fields are simply not persisted (process death then loses the
     *  in-progress edit, which is an acceptable trade against crashing the app outright). */
    private fun persistFields(to: String, cc: String, bcc: String, subject: String, body: String) {
        if (to.length + cc.length + bcc.length + subject.length + body.length <= MAX_PERSISTED_CHARS) {
            savedStateHandle[KEY_TO] = to
            savedStateHandle[KEY_CC] = cc
            savedStateHandle[KEY_BCC] = bcc
            savedStateHandle[KEY_SUBJECT] = subject
            savedStateHandle[KEY_BODY] = body
        } else {
            savedStateHandle.remove<String>(KEY_TO)
            savedStateHandle.remove<String>(KEY_CC)
            savedStateHandle.remove<String>(KEY_BCC)
            savedStateHandle.remove<String>(KEY_SUBJECT)
            savedStateHandle.remove<String>(KEY_BODY)
        }
    }

    fun addAttachments(list: List<ComposeAttachment>) =
        _state.update { s -> s.copy(attachments = s.attachments + list.filter { a -> s.attachments.none { it.id == a.id } }, error = null) }

    fun removeAttachment(id: String) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }, error = null) }

    /**
     * Resolves each picked document's metadata off the injected [io] dispatcher (never Main) via
     * [pickedAttachmentReader], then merges the results in like any other attachment.
     * [pendingPicks] tracks the in-flight count so [send]/[saveDraft] can refuse to run against a
     * form that isn't done changing yet.
     */
    fun addPicked(uris: List<Uri?>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(pendingPicks = it.pendingPicks + 1) }
        viewModelScope.launch {
            try {
                val picked = withContext(io) { uris.mapNotNull(pickedAttachmentReader::describe) }
                addAttachments(picked)
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
        // at all, or an over-budget attachment, blocks saving (Minor #2, Important #4).
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
     *  sizes are already the encoded octet count IMAP reported and must be added as-is (Important #5). */
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

    private companion object {
        const val KEY_TO = "field_to"
        const val KEY_CC = "field_cc"
        const val KEY_BCC = "field_bcc"
        const val KEY_SUBJECT = "field_subject"
        const val KEY_BODY = "field_body"
        const val KEY_USER_EDITED = "field_user_edited"

        /** Binder transactions (the Bundle SavedStateHandle rides on `onStop`) fail hard above
         *  roughly 1 MB; 100,000 chars of UTF-16 text is already ~200 KB and a generous margin
         *  under that, while still covering realistically long quoted/forwarded bodies. */
        const val MAX_PERSISTED_CHARS = 100_000
    }
}
