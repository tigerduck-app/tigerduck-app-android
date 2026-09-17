package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    sealed interface Source {
        class Local(val open: () -> InputStream) : Source
        data class Original(val folder: String, val uid: Long, val partId: String) : Source
    }
}

/** Plain-text compose (spec §6.4, §8.4). A failed send keeps every field; there is no outbox. */
@HiltViewModel
class SchoolMailComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val cache: MailCache,
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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var prefilled = false
    private var inReplyTo: String? = null
    private var references: String? = null

    fun prefill(labels: ComposePrefill.Labels) {
        if (prefilled) return
        prefilled = true
        if (mode == ComposeMode.NEW || sourceUid < 0) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
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
                _state.update {
                    val next = it.copy(
                        loading = false, to = draft.to, cc = draft.cc, subject = draft.subject, body = draft.body,
                        showCcBcc = draft.cc.isNotBlank(), attachments = carried,
                    )
                    next.copy(baseline = next.fields)
                }
            } catch (e: MailError) {
                if (e is MailError.AuthFailed) account.onAuthFailure()
                _state.update { it.copy(loading = false, error = ComposeError.LoadFailed(e)) }
            }
        }
    }

    fun setTo(value: String) = _state.update { it.copy(to = value, error = null) }
    fun setCc(value: String) = _state.update { it.copy(cc = value, error = null) }
    fun setBcc(value: String) = _state.update { it.copy(bcc = value, error = null) }
    fun setSubject(value: String) = _state.update { it.copy(subject = value) }
    fun setBody(value: String) = _state.update { it.copy(body = value) }
    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun addAttachments(list: List<ComposeAttachment>) =
        _state.update { s -> s.copy(attachments = s.attachments + list.filter { a -> s.attachments.none { it.id == a.id } }, error = null) }

    fun removeAttachment(id: String) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }, error = null) }

    fun send() {
        val s = _state.value
        if (s.sending || s.loading) return
        val to = ComposeRules.parseRecipients(s.to)
        val cc = ComposeRules.parseRecipients(s.cc)
        val bcc = ComposeRules.parseRecipients(s.bcc)
        val invalid = to.invalid + cc.invalid + bcc.invalid
        val error = when {
            invalid.isNotEmpty() -> ComposeError.InvalidRecipients(invalid)
            to.addresses.isEmpty() && cc.addresses.isEmpty() && bcc.addresses.isEmpty() -> ComposeError.NoRecipient
            !ComposeRules.fitsSizeLimit(s.body, s.attachments.map { it.sizeBytes }) -> ComposeError.TooLarge
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        withStaged(onError = { ComposeError.SendFailed(it) }) { attachments ->
            val mail = OutgoingMail(repository.selfAddress(), to.addresses, cc.addresses, bcc.addresses,
                s.subject.trim(), s.body, attachments, inReplyTo, references)
            val answered = if (mode == ComposeMode.REPLY || mode == ComposeMode.REPLY_ALL) sourceFolder to sourceUid else null
            repository.send(mail, answered)
            if (mode == ComposeMode.DRAFT) discardSentDraft()
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
        if (s.sending || s.loading) return
        withStaged(onError = { ComposeError.DraftFailed(it) }) { attachments ->
            val mail = OutgoingMail(
                repository.selfAddress(),
                ComposeRules.parseRecipients(s.to).addresses,
                ComposeRules.parseRecipients(s.cc).addresses,
                ComposeRules.parseRecipients(s.bcc).addresses,
                s.subject.trim(), s.body, attachments, inReplyTo, references,
            )
            repository.saveDraft(mail, replacingUid = if (mode == ComposeMode.DRAFT) sourceUid else null)
            _state.update { it.copy(done = true, savedDraft = true) }
        }
    }

    fun discard() = _state.update { it.copy(done = true) }

    /** Downloads carried-over attachments to temp files for the duration of [block], then deletes them. */
    private fun withStaged(onError: (MailError) -> ComposeError, block: suspend (List<OutgoingAttachment>) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            val staging = File(cache.attachmentsDir, "outgoing-${UUID.randomUUID()}")
            try {
                val attachments = _state.value.attachments.map { a ->
                    when (val src = a.source) {
                        is ComposeAttachment.Source.Local -> OutgoingAttachment(a.fileName, a.contentType, a.sizeBytes, src.open)
                        is ComposeAttachment.Source.Original -> {
                            val file = File(staging, "${a.id}-${SchoolMailMessageViewModel.safeFileName(a.fileName)}")
                            withContext(io) { staging.mkdirs(); file.outputStream() }.use { out ->
                                repository.writeAttachment(src.folder, src.uid, src.partId, out)
                            }
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
                withContext(io) { staging.deleteRecursively() }
                _state.update { it.copy(sending = false) }
            }
        }
    }
}
