package org.ntust.app.tigerduck.ui.screen.mail

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.di.IoDispatcher
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.SentCopyNotice
import org.ntust.app.tigerduck.mail.SentCopyNotices
import org.ntust.app.tigerduck.mail.SentCopyOrigin
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.compose.MailtoLink
import org.ntust.app.tigerduck.mail.compose.OutgoingAttachment
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.compose.RecipientSuggestions
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.mail.store.MailCache
import java.io.File
import java.io.InputStream
import java.time.Instant
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
 * Nothing here survives process death. Persisting draft state across it is deceptively easy to
 * get wrong -- a blank restore above a persistence cap, restored edits silently losing `dirty`,
 * and prefill re-running on an ordinary Activity recreation and wiping picked files are all
 * failure modes a partial attempt reintroduces. Losing typed text to a process death while the
 * picker is open is accordingly an accepted, ledgered trade-off.
 */
@HiltViewModel
class SchoolMailComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val cache: MailCache,
    private val pickedAttachmentReader: PickedAttachmentReader,
    private val sentCopyNotices: SentCopyNotices,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val mode: ComposeMode = runCatching { ComposeMode.valueOf(savedStateHandle.get<String>("mode").orEmpty()) }
        .getOrDefault(ComposeMode.NEW)
    private val sourceFolder: String = savedStateHandle.get<String>("folder").orEmpty()
    private val sourceUid: Long = savedStateHandle.get<Long>("uid") ?: -1L

    /**
     * The screen this compose closes back to, which is where a missing sent copy is told: the mail
     * being answered or forwarded, the mail a `mailto:` link was tapped in (it rides along as the
     * source), or the list for a new mail and a draft.
     */
    private val sentCopyOrigin: SentCopyOrigin =
        if (sourceUid >= 0 && mode != ComposeMode.DRAFT) SentCopyOrigin.Message(sourceFolder, sourceUid) else SentCopyOrigin.List

    /** A new mail started from a `mailto:` link tapped in a mail. */
    private val mailto: MailtoLink.Draft? =
        savedStateHandle.get<String>("mailto")?.takeIf { mode == ComposeMode.NEW }?.let(MailtoLink::parse)

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

    /** What leaving compares against; recipients as their [RecipientField.entries]. */
    data class Fields(
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String = "",
        val body: String = "",
        val attachmentIds: List<String> = emptyList(),
    )

    enum class RecipientSlot { TO, CC, BCC }

    data class UiState(
        val loading: Boolean = false,
        val to: RecipientField = RecipientField(),
        val cc: RecipientField = RecipientField(),
        val bcc: RecipientField = RecipientField(),
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
        /** Whether the popup raised for [error] has been dismissed. Kept apart from [error] itself
         *  so dismissing the popup never clears the inline message -- they are two separate views
         *  of the same failure (spec: the popup is *in addition to* the inline text, not instead of
         *  it). Every place that sets [error] to a new value -- including one that is structurally
         *  equal to the last one, e.g. a repeated [ComposeError.TooLarge] -- resets this to false so
         *  the popup is never silently swallowed by an old acknowledgement. */
        val errorAcknowledged: Boolean = false,
        val done: Boolean = false,
        val savedDraft: Boolean = false,
        /**
         * A send the student confirmed, held for [SEND_HOLD_MS] before it goes out, so Undo can
         * still take it back (tigerduck-app#555). [sending] is true with it: the form is the one
         * being sent, and nothing else may be sent or saved, nor the screen left, meanwhile.
         */
        val holding: Boolean = false,
        /** An Undo took the held mail back; the screen says so once and [acknowledgeUndone]s it. */
        val undone: Boolean = false,
        /** The people the recipient fields suggest, from the mail on the phone; see [suggestions]. */
        val contacts: List<RecipientSuggestions.Contact> = emptyList(),
        internal val baseline: Fields = Fields(),
    ) {
        internal val fields get() = Fields(to.entries, cc.entries, bcc.entries, subject, body, attachments.map { it.id })
        /** Anything changed since the screen opened — leaving asks to save a draft (spec §6.4). */
        val dirty: Boolean get() = fields != baseline

        /**
         * Who [slot]'s field suggests for what is being typed in it, leaving out anyone a bubble
         * of the mail already names. Not what is being typed: an address typed in full still
         * suggests its contact, with the name the mail gave.
         */
        fun suggestions(slot: RecipientSlot): List<MailAddress> {
            val field = when (slot) {
                RecipientSlot.TO -> to
                RecipientSlot.CC -> cc
                RecipientSlot.BCC -> bcc
            }
            if (field.draft.isBlank()) return emptyList()
            val taken = listOf(to, cc, bcc)
                .flatMap { f -> ComposeRules.parseRecipients(f.tokens.map { it.text }).addresses }
                .map { it.address }
            return RecipientSuggestions.matching(contacts, field.draft, taken)
        }
    }

    /** Who a new message is to when the screen was opened for someone, such as a course member. */
    private val prefilledTo: String = savedStateHandle.get<String>("to").orEmpty()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Moved by every sign-out and every student signing in over another, on the main thread: a
     * load of the contacts lands only while it has not moved since the load began. The account's
     * generation is not enough, as a student change is told before the next sign-in moves it.
     */
    private var contactsTicket = 0

    init {
        // Kept on the phone for the account they came from alone (tigerduck-app#552). Subscribed
        // before this constructor returns, as the repository subscribes, so no change can come
        // between the load starting and the screen hearing of it: neither flow replays.
        for (left in listOf(account.signOuts, account.studentChanges)) {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                left.collect {
                    contactsTicket++
                    _state.update { it.copy(contacts = emptyList()) }
                }
            }
        }
        loadContacts()
    }

    /**
     * Ranks the people in the mail on the phone for the recipient fields, once, off the main
     * thread: a few thousand mail to read. Nothing is suggested when that fails, and nothing of an
     * account that left meanwhile.
     */
    private fun loadContacts() {
        val ticket = contactsTicket
        viewModelScope.launch {
            val generation = account.accountGeneration
            val contacts = try {
                withContext(io) {
                    RecipientSuggestions.contacts(repository.correspondence(), repository.selfAddress().address, Instant.now())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (ticket == contactsTicket && account.accountGeneration == generation) _state.update { it.copy(contacts = contacts) }
        }
    }

    /**
     * A new message opened for someone starts addressed to them, and that address is its
     * baseline: backing out untouched asks nothing about a draft.
     */
    private fun initialState(): UiState {
        if (mode != ComposeMode.NEW || prefilledTo.isBlank()) return UiState()
        val to = RecipientField.of(prefilledTo)
        return UiState(to = to, baseline = Fields(to = to.entries))
    }

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
        mailto?.let { link ->
            // Filled in once, from the link alone: nothing to load, and the fields as the link
            // left them are the baseline, so backing out untouched asks nothing.
            _state.update {
                val to = RecipientField.of(link.to)
                val cc = RecipientField.of(link.cc)
                it.copy(
                    to = to, cc = cc, subject = link.subject, body = link.body,
                    showCcBcc = cc.entries.isNotEmpty(),
                    baseline = Fields(to.entries, cc.entries, emptyList(), link.subject, link.body, emptyList()),
                )
            }
            return
        }
        if (mode == ComposeMode.NEW || sourceUid < 0) return
        viewModelScope.launch {
            // The sign-in a rejection is for: one that comes back once it has left is not the next
            // one's (spec §7.4), as the repository's own record of it already holds.
            val generation = account.accountGeneration
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val original = repository.summary(sourceFolder, sourceUid) ?: throw MailError.Protocol("message is gone")
                val body = repository.body(sourceFolder, sourceUid)
                // Two full jsoup passes (sanitize, then plainText) over whatever the original
                // sender sent. viewModelScope resumes on Main.immediate, so left here it quoted a
                // large HTML mail by janking the compose screen; it belongs on [io] like every
                // other non-trivial piece of work this view model does.
                val text = body.plain
                    ?: withContext(io) {
                        body.html?.let { HtmlSanitizer.plainText(HtmlSanitizer.sanitize(it, allowRemoteImages = false).html) }.orEmpty()
                    }
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
                    // A recipient field nobody has typed into has no text being typed either, so
                    // replacing it whole cannot take anything out from under the field on screen
                    // (see RecipientField.draft).
                    val to = if (edited.to) it.to else RecipientField.of(draft.to)
                    val cc = if (edited.cc) it.cc else RecipientField.of(draft.cc)
                    val subject = if (edited.subject) it.subject else draft.subject
                    val body2 = if (edited.body) it.body else draft.body
                    val next = it.copy(
                        loading = false, to = to, cc = cc, subject = subject, body = body2,
                        showCcBcc = it.showCcBcc || cc.entries.isNotEmpty() || it.bcc.entries.isNotEmpty(),
                        // Local picks made while the load had failed are kept; the carried
                        // originals are simply added alongside them, never replacing the list.
                        attachments = it.attachments + carried,
                    )
                    // The baseline is always this fresh prefill's own fields, never the user's
                    // edits -- an edit made before or after a retry must still leave the form
                    // dirty. Bcc is never part of a prefilled draft (spec §8.4), so it stays empty
                    // in the baseline regardless of what's currently typed there.
                    next.copy(
                        baseline = Fields(
                            RecipientField.of(draft.to).entries, RecipientField.of(draft.cc).entries, emptyList(),
                            draft.subject, draft.body, carried.map { c -> c.id },
                        ),
                    )
                }
            } catch (e: MailError) {
                if (e is MailError.AuthFailed) account.onAuthFailure(generation)
                _state.update { it.copy(loading = false, loadError = e) }
            }
        }
    }

    /**
     * Applies [change] to one recipient field and returns what the field became. The field on
     * screen hands every edit over as a change rather than a finished value, so it always applies
     * to the latest state, never to the copy the screen last drew, which trails the typing by a
     * frame. Only a change to the recipients themselves counts as an edit: finishing a bubble or
     * opening one to look at it moves nothing that would be sent.
     */
    fun updateRecipients(slot: RecipientSlot, change: (RecipientField) -> RecipientField): RecipientField {
        var updated = RecipientField()
        var changed = false
        _state.update { s ->
            val before = s.recipients(slot)
            updated = change(before)
            changed = updated.entries != before.entries
            val next = when (slot) {
                RecipientSlot.TO -> s.copy(to = updated)
                RecipientSlot.CC -> s.copy(cc = updated)
                RecipientSlot.BCC -> s.copy(bcc = updated)
            }
            if (changed) next.copy(error = null, errorAcknowledged = false) else next
        }
        if (changed) {
            when (slot) {
                RecipientSlot.TO -> edited.to = true
                RecipientSlot.CC -> edited.cc = true
                RecipientSlot.BCC -> Unit
            }
        }
        return updated
    }

    private fun UiState.recipients(slot: RecipientSlot) = when (slot) {
        RecipientSlot.TO -> to
        RecipientSlot.CC -> cc
        RecipientSlot.BCC -> bcc
    }
    fun setSubject(value: String) { edited.subject = true; _state.update { it.copy(subject = value) } }
    fun setBody(value: String) { edited.body = true; _state.update { it.copy(body = value) } }
    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }
    fun clearError() = _state.update { it.copy(error = null, errorAcknowledged = false) }

    /** Dismisses the error popup without touching [UiState.error] -- the inline message stays. */
    fun acknowledgeError() = _state.update { it.copy(errorAcknowledged = true) }

    fun addAttachments(list: List<ComposeAttachment>) =
        _state.update { s -> s.copy(attachments = s.attachments + list.filter { a -> s.attachments.none { it.id == a.id } }, error = null, errorAcknowledged = false) }

    fun removeAttachment(id: String) = _state.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }, error = null, errorAcknowledged = false) }

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
                if (described.any { it == null }) _state.update { it.copy(error = ComposeError.TooLarge, errorAcknowledged = false) }
            } finally {
                _state.update { it.copy(pendingPicks = it.pendingPicks - 1) }
            }
        }
    }

    /** The validation [send] always runs before it actually ships the mail, shared with
     *  [requestSend] so the confirmation prompt sees exactly the same verdict a bare [send] would. */
    private fun sendValidationError(s: UiState): ComposeError? {
        val to = ComposeRules.parseRecipients(s.to.entries)
        val cc = ComposeRules.parseRecipients(s.cc.entries)
        val bcc = ComposeRules.parseRecipients(s.bcc.entries)
        val invalid = to.invalid + cc.invalid + bcc.invalid
        return when {
            invalid.isNotEmpty() -> ComposeError.InvalidRecipients(invalid)
            to.addresses.isEmpty() && cc.addresses.isEmpty() && bcc.addresses.isEmpty() -> ComposeError.NoRecipient
            !fitsSizeLimit(s) -> ComposeError.TooLarge
            else -> null
        }
    }

    /** Called when Send is tapped, before the "are you sure?" prompt. Runs the same validation
     *  [send] itself runs -- an invalid form sets the existing inline+popup error (never the
     *  confirmation) and returns false; a form that would actually go out sets nothing and returns
     *  true, letting the screen raise the confirmation. */
    fun requestSend(): Boolean {
        val s = _state.value
        if (s.sending || s.loading || s.pendingPicks > 0) return false
        val error = sendValidationError(s)
        if (error != null) {
            _state.update { it.copy(error = error, errorAcknowledged = false) }
            return false
        }
        return true
    }

    /** How long a confirmed send waits for an Undo; zero in tests that are not about it. */
    @VisibleForTesting
    internal var sendHoldMs = SEND_HOLD_MS

    /** Answers the held send: true sends it now, false takes it back. Null while none is held. */
    private var heldSend: CompletableDeferred<Boolean>? = null

    /**
     * Sends the form as it is now, once [holdMs] has passed with no [undoSend]; [sendHeldNow]
     * ends the wait early. What goes out is what was confirmed: the form is read here, and cannot
     * change during the hold, the screen disabling it as for a send. The screen asks for longer
     * than [SEND_HOLD_MS] where the accessibility settings give an action more time
     * ([sendHoldMillis]).
     */
    fun send(holdMs: Long = sendHoldMs) {
        val s = _state.value
        if (s.sending || s.loading || s.pendingPicks > 0) return
        val error = sendValidationError(s)
        if (error != null) {
            _state.update { it.copy(error = error, errorAcknowledged = false) }
            return
        }
        // The sign-in the student confirmed the send under: a mail held while another student
        // signed in over them must not go out as the next one's.
        val confirmedUnder = account.accountGeneration
        val answer = CompletableDeferred<Boolean>()
        heldSend = answer
        _state.update { it.copy(sending = true, holding = true, undone = false) }
        viewModelScope.launch {
            val goes = withTimeoutOrNull(holdMs) { answer.await() } ?: true
            heldSend = null
            if (!goes || account.accountGeneration != confirmedUnder) {
                _state.update { it.copy(sending = false, holding = false, undone = !goes) }
                return@launch
            }
            _state.update { it.copy(holding = false) }
            ship(s)
        }
    }

    /**
     * Takes the held send back, the form left as it was for the student to change and send
     * again. Nothing once it has gone out.
     */
    fun undoSend() {
        heldSend?.complete(false)
    }

    /**
     * Ends the hold and sends at once: the screen is leaving the foreground, to another app, the
     * recent apps or the screen turning off, where Undo is out of reach. Held on, the mail would be
     * lost with the screen if the app were then closed; sent now, it goes as a send without a hold
     * would have.
     */
    fun sendHeldNow() {
        heldSend?.complete(true)
    }

    fun acknowledgeUndone() = _state.update { it.copy(undone = false) }

    /** The send itself, of the form [s] as it was confirmed. */
    private fun ship(s: UiState) {
        val to = ComposeRules.parseRecipients(s.to.entries)
        val cc = ComposeRules.parseRecipients(s.cc.entries)
        val bcc = ComposeRules.parseRecipients(s.bcc.entries)
        // Read before the mail goes out: an account that changes while it does must not leave
        // its notice to the next one.
        val accountGeneration = sentCopyNotices.accountGeneration
        val generation = account.accountGeneration
        withStaged(onError = { ComposeError.SendFailed(it) }) { attachments ->
            val mail = OutgoingMail(repository.selfAddress(), to.addresses, cc.addresses, bcc.addresses,
                s.subject.trim(), s.body, attachments, inReplyTo, references)
            val answered = if (sourceLoaded && (mode == ComposeMode.REPLY || mode == ComposeMode.REPLY_ALL)) sourceFolder to sourceUid else null
            val copy = repository.send(mail, answered)
            // The sent copy's APPEND logs in with the same stored password the send just used, so
            // a rejection there is the §7.4 rejected password and has to reach the account -- even
            // though the send itself succeeded and nothing was thrown for [withStaged] to classify.
            if (copy is SentCopy.Failed && copy.error is MailError.AuthFailed) account.onAuthFailure(generation)
            if (sourceLoaded && mode == ComposeMode.DRAFT) discardSentDraft()
            // The mail went out, but its copy did not reach the sent folder. A notice, never an
            // [UiState.error]: the send succeeded, so failing the screen over it would tell the
            // user the opposite of what happened. It is reported before the screen closes, to
            // stay on the screens it goes back to until the user dismisses it.
            SentCopyNotice.of(copy)?.let { sentCopyNotices.report(it, sentCopyOrigin, accountGeneration) }
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
        val to = ComposeRules.parseRecipients(s.to.entries)
        val cc = ComposeRules.parseRecipients(s.cc.entries)
        val bcc = ComposeRules.parseRecipients(s.bcc.entries)
        val invalid = to.invalid + cc.invalid + bcc.invalid
        // A draft may legitimately have no recipients yet -- only a token that couldn't be read
        // at all, or an over-budget attachment, blocks saving.
        val error = when {
            invalid.isNotEmpty() -> ComposeError.InvalidRecipients(invalid)
            !fitsSizeLimit(s) -> ComposeError.TooLarge
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error, errorAcknowledged = false) }
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
            // As in [runPrefill]: the sign-in a rejection is for.
            val generation = account.accountGeneration
            _state.update { it.copy(sending = true, error = null, errorAcknowledged = false) }
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
                if (error is MailError.AuthFailed) account.onAuthFailure(generation)
                _state.update { it.copy(error = onError(error), errorAcknowledged = false) }
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

    companion object {
        /** How long a confirmed send waits for an Undo before it goes out (tigerduck-app#555). */
        const val SEND_HOLD_MS = 5_000L
    }
}

