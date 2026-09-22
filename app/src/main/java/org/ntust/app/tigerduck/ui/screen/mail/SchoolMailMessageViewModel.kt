package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.di.IoDispatcher
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.MailSite
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.sanitize.MailLink
import org.ntust.app.tigerduck.mail.sanitize.SanitizedHtml
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.warning.LinkVerdict
import org.ntust.app.tigerduck.mail.warning.MailWarning
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import java.io.File
import java.io.OutputStream
import java.net.IDN
import java.net.URLDecoder
import java.util.UUID
import javax.inject.Inject

/** One mail (spec §6.3, §9): three views, warnings, attachments, and the actions that close it. */
@HiltViewModel
class SchoolMailMessageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val notifier: MailNotifier,
    private val cache: MailCache,
    private val site: MailSite,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val folder: String = savedStateHandle.get<String>("folder").orEmpty()
    val uid: Long = savedStateHandle.get<Long>("uid") ?: -1L

    /** The account's own domain, which the External badge and the bounce rule measure against. */
    val mailDomain: String get() = site.domain()

    enum class ViewMode { FORMATTED, PLAIN, SOURCE }

    enum class AttachmentAction { OPEN, SAVE }

    data class PendingAttachment(val attachment: MailAttachment, val action: AttachmentAction)

    /** What the link dialog judges, shows and opens for one tapped anchor. */
    data class LinkTarget(
        /** Judged by [verdict], shown (bidi-stripped) and opened exactly as it is. */
        val href: String,
        val verdict: LinkVerdict,
        /** False for an http(s) href a browser-like parser rejects: the dialog shows it without Open. */
        val canOpen: Boolean,
    )

    sealed interface Content {
        data object Loading : Content
        data class Failed(val error: MailError) : Content
        /** Sender, subject and date are known (from [SchoolMailRepository.summary]) while the body is still on its way. */
        data class LoadingBody(val summary: MailSummary) : Content
        data class Ready(
            val summary: MailSummary,
            val body: MailBody,
            val html: SanitizedHtml?,
            /** [html] as the WebView loads it; taps address its links (see [MailHtmlDocument.rewriteLinks]). */
            val document: LinkedHtml?,
            val plain: String,
            val warnings: List<MailWarning>,
        ) : Content
    }

    data class OpenRequest(val file: File, val contentType: String)

    data class UiState(
        val folder: String = "",
        val content: Content = Content.Loading,
        val mode: ViewMode = ViewMode.FORMATTED,
        val folders: ResolvedFolders? = null,
        val remoteImagesAllowed: Boolean = false,
        /** [loadRemoteImages] is re-sanitizing and re-inlining off the main thread: the banner shows progress instead of an unresponsive button. */
        val loadingRemoteImages: Boolean = false,
        val parseFailed: Boolean = false,
        val source: String? = null,
        val sourceLoading: Boolean = false,
        /** An attachment (open or save) that needs the risky/HTML/SVG confirmation first. */
        val confirmAttachment: PendingAttachment? = null,
        val openRequest: OpenRequest? = null,
        /** Set once a save is confirmed (or needed no confirmation): the screen launches the SAF picker for it. */
        val saveRequest: MailAttachment? = null,
        /** Attachment part IDs currently being downloaded, open or save alike -- keyed per part so two different attachments don't clear each other's spinner. */
        val downloading: Set<String> = emptySet(),
        val savedCount: Int = 0,
        val confirmDelete: Boolean = false,
        val confirmDeleteForever: Boolean = false,
        val actionError: MailError? = null,
        /** Moved, deleted or marked unread: the screen pops. */
        val closed: Boolean = false,
    ) {
        val folderKind: SpecialFolder? get() = folders?.kindOf(folder)
    }

    private val _state = MutableStateFlow(UiState(folder = folder))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The app's own colours for the mail HTML page (spec §9.3 no longer means white paper). There
     * is no Compose theme access from a view model, so [SchoolMailMessageScreen] pushes its current
     * `MaterialTheme.colorScheme` in through [setMailTheme] from a `LaunchedEffect` keyed on it;
     * [render] and [loadRemoteImages] read it whenever they (re)build the document.
     *
     * A genuine change -- not merely a recomposition that recomputes the same colours -- also
     * rebuilds the document already on screen, whenever content is already [Content.Ready], from
     * the sanitized HTML and inline images already in hand: no re-fetch, no re-sanitize, just
     * [MailHtmlDocument.build] run again. Without this, the WebView's *native* background (set
     * independently, straight from the current colour scheme) repaints immediately on a
     * dark/light flip while a mail is open, but the document itself would keep the colours it was
     * built with -- a stale, wrongly-coloured page inside a freshly-coloured frame, which is
     * exactly what this theming exists to prevent.
     */
    private var mailTheme = MailHtmlTheme(background = "#ffffff", foreground = "#000000", isDark = false)

    fun setMailTheme(theme: MailHtmlTheme) {
        if (theme == mailTheme) return
        mailTheme = theme
        rebuildDocument(theme)
    }

    private var themeJob: Job? = null

    /**
     * Rebuilds the open mail's document in [theme], from the sanitized HTML and inline images
     * already in hand.
     *
     * The result is applied only if the content it was built from is still the content on screen.
     * [loadRemoteImages] re-sanitizes and swaps *both* `html` and `document` for a pair that
     * allows remote images; a theme rebuild that started before it and landed after would put the
     * blocking document back underneath `remoteImagesAllowed = true` -- images missing, and the
     * action that would ask for them again already spent. So the allowance is read once, here,
     * alongside the HTML it belongs with, and both are checked again before the swap.
     *
     * Only the newest rebuild matters, so a second one cancels the first rather than racing it.
     */
    private fun rebuildDocument(theme: MailHtmlTheme) {
        val ready = _state.value.content as? Content.Ready ?: return
        val html = ready.html ?: return
        val allowRemote = _state.value.remoteImagesAllowed
        themeJob?.cancel()
        themeJob = viewModelScope.launch {
            val document = withContext(io) {
                MailHtmlDocument.build(html.html, ready.body.inlineImages, allowRemote, theme)
            }
            update { st ->
                val current = st.content as? Content.Ready ?: return@update st
                if (current.html !== html || st.remoteImagesAllowed != allowRemote) return@update st
                st.copy(content = current.copy(document = document))
            }
        }
    }

    private fun update(transform: (UiState) -> UiState) = _state.update(transform)

    /**
     * A no-op once the mail is already [Content.Ready]: `LaunchedEffect(Unit) { load() }` reruns
     * whenever the screen re-enters composition (returning from Reply, or an activity/config
     * recreation), and without this guard that flashed [Content.Loading], reset [UiState.mode]
     * back to its default, and re-sanitized the HTML with `allowRemoteImages = false` while
     * [UiState.remoteImagesAllowed] stayed true -- the loaded images would vanish with no banner
     * left to reload them. A retry after [Content.Failed] still reloads.
     */
    fun load() {
        if (_state.value.content is Content.Ready) return
        viewModelScope.launch {
            update { it.copy(content = Content.Loading) }
            try {
                val folders = repository.folders()
                update { it.copy(folders = folders) }
                val summary = repository.summary(folder, uid) ?: throw MailError.Protocol("message is gone")
                update { it.copy(content = Content.LoadingBody(summary)) }
                val body = try {
                    repository.body(folder, uid)
                } catch (e: MailError.Protocol) {
                    // A body we cannot parse is still readable as its source (spec §9.1).
                    update { it.copy(content = Content.Ready(summary, EMPTY_BODY, null, null, "", emptyList()), parseFailed = true) }
                    markSeen(summary)
                    selectMode(ViewMode.SOURCE)
                    resolvePendingSave()
                    return@launch
                }
                // Whenever this sanitizes, it honors whatever the state already says about
                // remote images rather than hard-coding false, so a load that runs while that
                // flag is already true (in principle reachable from a future retry path) never
                // silently re-blocks images the user already opted into.
                val ready = render(summary, body, allowRemote = _state.value.remoteImagesAllowed)
                update {
                    it.copy(
                        content = ready,
                        mode = if (ready.html != null) ViewMode.FORMATTED else ViewMode.PLAIN,
                    )
                }
                markSeen(summary)
                resolvePendingSave()
            } catch (e: MailError) {
                if (e is MailError.AuthFailed) account.onAuthFailure()
                update { it.copy(content = Content.Failed(e)) }
                resolvePendingSave()
            }
        }
    }

    /**
     * Turns a fetched body into the [Content.Ready] the screen renders, entirely on [io].
     *
     * Every step here is a jsoup parse or worse over a string the *sender* chose: the sanitizer's
     * parse plus `Cleaner` plus its per-element CSS filter, [MailHtmlDocument.build]'s second
     * parse and its Base64-encoding of every inline image into the document string, a third parse
     * for the plain-text fallback, and the link/warning scan on top. `viewModelScope.launch`
     * resumes on `Main.immediate`, so with none of this hopped off it, a mail with a large HTML
     * part and a few MB of inline images janked and could ANR -- on attacker-chosen input.
     */
    private suspend fun render(summary: MailSummary, body: MailBody, allowRemote: Boolean): Content.Ready =
        withContext(io) {
            val html = body.html?.let { HtmlSanitizer.sanitize(it, allowRemoteImages = allowRemote) }
            val document = html?.let { MailHtmlDocument.build(it.html, body.inlineImages, allowRemote, mailTheme) }
            val plain = body.plain ?: html?.let { HtmlSanitizer.plainText(it.html) }.orEmpty()
            val warnings = MailWarnings.evaluate(
                summary.from, summary.subject, plain, html?.links.orEmpty(), body.attachments, summary.returnPath,
                domain = mailDomain,
            )
            Content.Ready(summary, body, html, document, plain, warnings)
        }

    private suspend fun markSeen(summary: MailSummary) {
        // Notification ids are derived from the UID alone, and only the inbox ever posts one.
        // A UID is unique only within its folder, so cancelling from anywhere else would clear
        // the notification of a different, unread inbox mail that happens to share the number --
        // reachable from Sent directly, and one tap away in All mail.
        if (_state.value.folderKind == SpecialFolder.INBOX) notifier.cancelMessage(uid)
        if (!summary.flags.seen) {
            try {
                repository.setSeen(folder, uid, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort: failing to mark a mail seen must not fail the whole load.
            }
        }
    }

    /**
     * Re-runs the same sanitize/inline pipeline [render] does, with remote images allowed -- and
     * like [render] it runs on [io]. This used to be called straight from the banner's `onClick`,
     * not from a coroutine at all, so the whole two-parse-plus-Base64 pass ran on the main thread
     * with nothing on screen to say why the app had stopped responding. [UiState.loadingRemoteImages]
     * is what the banner shows instead, and it also keeps a second tap from starting a second pass.
     *
     * Only `html`/`document` are replaced, exactly as before: `plain`, `warnings` and the summary
     * are unchanged by allowing images, and recomputing them could only introduce drift.
     */
    fun loadRemoteImages() {
        val ready = _state.value.content as? Content.Ready ?: return
        val source = ready.body.html ?: return
        if (_state.value.loadingRemoteImages) return
        viewModelScope.launch {
            update { it.copy(loadingRemoteImages = true) }
            try {
                // Captured, not read again at the end: the theme can change while this is in
                // flight, and the comparison below is what notices.
                val builtWith = mailTheme
                val (html, document) = withContext(io) {
                    val sanitized = HtmlSanitizer.sanitize(source, allowRemoteImages = true)
                    sanitized to MailHtmlDocument.build(sanitized.html, ready.body.inlineImages, allowRemoteImages = true, theme = builtWith)
                }
                update { st ->
                    // The mail cannot change underneath this (load() is a no-op once Ready), but
                    // read the current content rather than closing over the captured one so a
                    // future path that does replace it can never be silently reverted here.
                    val current = st.content as? Content.Ready ?: return@update st
                    st.copy(remoteImagesAllowed = true, content = current.copy(html = html, document = document))
                }
                // This swap wins over any theme rebuild that was in flight -- their guard sees the
                // new html and stands down -- so if the theme moved while this ran, the colours it
                // has just installed are stale and only this knows it.
                if (mailTheme != builtWith) rebuildDocument(mailTheme)
            } finally {
                update { it.copy(loadingRemoteImages = false) }
            }
        }
    }

    /**
     * Switching to the source view just loads it. There used to be a size check and a
     * "load the full source?" confirmation in front of this, because the source was never
     * cached and a large mail was therefore re-downloaded on every visit; the repository now
     * caches it alongside the body, so there is nothing left to warn about.
     */
    fun selectMode(mode: ViewMode) {
        update { it.copy(mode = mode) }
        val s = _state.value
        if (mode != ViewMode.SOURCE || s.source != null || s.sourceLoading) return
        viewModelScope.launch { loadSource() }
    }

    private suspend fun loadSource() {
        update { it.copy(sourceLoading = true) }
        try {
            val raw = repository.rawSource(folder, uid)
            update { it.copy(source = TextCleaning.stripBidi(raw), sourceLoading = false) }
        } catch (e: MailError) {
            if (e is MailError.AuthFailed) account.onAuthFailure()
            update { it.copy(sourceLoading = false, actionError = e) }
        }
    }

    /**
     * [index] is `n` from a tapped `https://link.invalid/<n>` ([MailWebView] range-checks it), addressing
     * the list [MailHtmlDocument.rewriteLinks] read off the very anchors the WebView shows -- never
     * `SanitizedHtml.links`, whose indices a re-parse can shift. Null for an index with no link.
     */
    fun linkTarget(index: Int): LinkTarget? =
        (_state.value.content as? Content.Ready)?.document?.links?.getOrNull(index)?.let { targetOf(it) }

    // --- attachments --------------------------------------------------------------------

    /** Spec §9.5: never auto-open; risky files and HTML/SVG ask first (A.4), whether opening or saving. */
    fun needsConfirmation(attachment: MailAttachment): Boolean {
        val ready = _state.value.content as? Content.Ready
        val context = listOfNotNull(ready?.summary?.subject, ready?.plain).joinToString("\n")
        val type = attachment.contentType.lowercase()
        return MailWarnings.riskReason(attachment.fileName, attachment.contentType, context) != null ||
            type == "text/html" || type == "image/svg+xml"
    }

    fun requestOpen(attachment: MailAttachment) = request(attachment, AttachmentAction.OPEN)

    fun requestSave(attachment: MailAttachment) = request(attachment, AttachmentAction.SAVE)

    private fun request(attachment: MailAttachment, action: AttachmentAction) {
        if (attachment.partId in _state.value.downloading) return
        if (needsConfirmation(attachment)) {
            update { it.copy(confirmAttachment = PendingAttachment(attachment, action)) }
        } else {
            proceed(attachment, action)
        }
    }

    /** Answers the dialog [request] raised for a risky or HTML/SVG attachment, open or save alike. */
    fun confirmAttachment(proceed: Boolean) {
        val pending = _state.value.confirmAttachment ?: return
        update { it.copy(confirmAttachment = null) }
        if (proceed) proceed(pending.attachment, pending.action)
    }

    private fun proceed(attachment: MailAttachment, action: AttachmentAction) {
        when (action) {
            AttachmentAction.OPEN -> open(attachment)
            // The SAF document picker is a Compose activity-result launcher this view model
            // can't drive directly; saveRequest tells the screen to launch it, and the actual
            // write happens in saveAttachment() once it returns a target.
            AttachmentAction.SAVE -> update { it.copy(saveRequest = attachment) }
        }
    }

    fun consumeSaveRequest() = update { it.copy(saveRequest = null) }

    private fun open(attachment: MailAttachment) {
        if (attachment.partId in _state.value.downloading) return
        act {
            update { it.copy(downloading = it.downloading + attachment.partId) }
            try {
                val file = download(attachment)
                update { it.copy(openRequest = OpenRequest(file, attachment.contentType)) }
            } finally {
                update { it.copy(downloading = it.downloading - attachment.partId) }
            }
        }
    }

    fun consumeOpenRequest() = update { it.copy(openRequest = null) }

    /**
     * Into `attachmentsDir/<random>/<name>`, which the FileProvider serves; files older than a
     * day are pruned. All of it -- mkdir/prune, opening the file and the write
     * [SchoolMailRepository.writeAttachment] does through it -- runs on [io], never the caller's
     * (Main.immediate in production) dispatcher; a failed write deletes the partial file/slot
     * rather than leaving it behind.
     */
    private suspend fun download(attachment: MailAttachment): File = withContext(io) {
        val dir = cache.attachmentsDir
        dir.mkdirs()
        val cutoff = System.currentTimeMillis() - DAY_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val slot = File(dir, UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(slot, safeFileName(attachment.fileName))
        var committed = false
        try {
            file.outputStream().use { out -> repository.writeAttachment(folder, uid, attachment.partId, out) }
            committed = true
        } finally {
            if (!committed) withContext(NonCancellable) { slot.deleteRecursively() }
        }
        file
    }

    /**
     * [open] and [onFailure] both do real IO -- opening (and, on failure, deleting) a document
     * the caller resolved from a SAF `Uri` is a binder call into whatever app owns that document
     * provider -- so both run on [io], not the caller's dispatcher.
     *
     * `ACTION_CREATE_DOCUMENT` can hand back a `Uri` the user picked to *overwrite* an existing
     * file, so [onFailure] must only run once a stream was actually obtained from [open]: if
     * [open] itself throws or returns null, nothing was touched yet, and deleting would destroy
     * a file the user never asked to lose. Only a write that starts (a stream was opened) and
     * then fails runs the cleanup.
     */
    fun saveAttachment(attachment: MailAttachment, open: () -> OutputStream?, onFailure: () -> Unit = {}) {
        if (attachment.partId in _state.value.downloading) return
        act {
            update { it.copy(downloading = it.downloading + attachment.partId) }
            var opened = false
            var committed = false
            try {
                withContext(io) {
                    val out = open() ?: throw MailError.Protocol("no output stream")
                    opened = true
                    out.use { repository.writeAttachment(folder, uid, attachment.partId, it) }
                    // Set right here, inside the io block, immediately after the stream closes
                    // successfully -- not after withContext(io) returns. A cancellation landing
                    // in the gap between that return and the next line would otherwise skip
                    // "committed = true" while the write had already fully succeeded, and the
                    // finally block below would then delete a document that was actually fine.
                    committed = true
                }
                update { it.copy(savedCount = it.savedCount + 1) }
            } finally {
                if (opened && !committed) withContext(io + NonCancellable) { onFailure() }
                update { it.copy(downloading = it.downloading - attachment.partId) }
            }
        }
    }

    fun consumeSaved() = update { it.copy(savedCount = 0) }

    /** A save the document picker has already answered, waiting for the body that names its part. */
    private class PendingSave(val partId: String?, val open: () -> OutputStream?, val onFailure: () -> Unit)

    private var pendingSave: PendingSave? = null

    /**
     * The document picker came back with somewhere to write [partId] to.
     *
     * Resolving the part ID against the loaded body belongs here, not on the screen. The screen
     * remembers the pending part ID across process death (`rememberSaveable`), but the body it
     * would look the attachment up in is view model state, which is **not** restored -- so a
     * process death while the SAF picker was foregrounded (routine on a low-RAM device) delivered
     * the result before [load] had finished, the lookup found nothing, and the save was dropped
     * in silence: no write, no message, no spinner, and a user convinced they had saved a file
     * that does not exist.
     *
     * So a request that arrives before the body does simply waits for the load already running
     * ([resolvePendingSave]), and a part that is genuinely not there once it lands is reported as
     * an action error rather than as nothing at all.
     */
    fun saveToPart(partId: String?, open: () -> OutputStream?, onFailure: () -> Unit = {}) {
        val pending = PendingSave(partId, open, onFailure)
        when (_state.value.content) {
            is Content.Ready, is Content.Failed -> resolveSave(pending)
            else -> pendingSave = pending
        }
    }

    private fun resolvePendingSave() {
        val pending = pendingSave ?: return
        pendingSave = null
        resolveSave(pending)
    }

    private fun resolveSave(pending: PendingSave) {
        val attachment = (_state.value.content as? Content.Ready)?.body?.attachments
            // A null partId (the screen lost it) matches nothing, and lands in the branch below.
            ?.firstOrNull { it.partId == pending.partId }
        if (attachment == null) {
            // [MailError.Protocol] reads as school_mail_error_generic. [PendingSave.onFailure] is
            // deliberately *not* run: nothing was ever written, and the picker's target can be a
            // file the user chose to overwrite -- deleting it would destroy what they still have.
            update { it.copy(actionError = MailError.Protocol("attachment is no longer loaded")) }
            return
        }
        saveAttachment(attachment, pending.open, pending.onFailure)
    }

    // --- actions ------------------------------------------------------------------------

    fun markUnread() = act {
        repository.setSeen(folder, uid, false)
        update { it.copy(closed = true) }
    }

    fun moveTargets(): List<String> {
        val folders = _state.value.folders ?: return emptyList()
        return (SpecialFolder.entries.mapNotNull { folders.nameOf(it) } + folders.others).filter { it != folder }
    }

    /**
     * [MailError.FolderChanged] (the folder's UIDVALIDITY moved server-side; the repository has
     * already dropped its cache) surfaces through [act] like any other action error, so the mail
     * stays open with a toast rather than popping as if the move had gone through. It does not
     * need to reach into the list screen: both screens share the same singleton
     * [SchoolMailRepository], and the list's own `load()` always re-fetches the selected folder
     * from the server whenever the list screen is re-entered, so once the user backs out it
     * shows the fresh page on its own.
     */
    fun moveTo(target: String) = act {
        repository.move(folder, uid, target)
        update { it.copy(closed = true) }
    }

    /** Both confirmations funnel into the same [repository.delete] call once answered; only one of the two dialogs ever fires for a given delete. */
    fun delete() = act {
        if (repository.deletesPermanently(folder)) {
            update { it.copy(confirmDeleteForever = true) }
        } else {
            update { it.copy(confirmDelete = true) }
        }
    }

    fun confirmDelete(confirm: Boolean) {
        update { it.copy(confirmDelete = false) }
        if (confirm) act {
            repository.delete(folder, uid)
            update { it.copy(closed = true) }
        }
    }

    fun confirmDeleteForever(confirm: Boolean) {
        update { it.copy(confirmDeleteForever = false) }
        if (confirm) act {
            repository.delete(folder, uid)
            update { it.copy(closed = true) }
        }
    }

    fun dismissActionError() = update { it.copy(actionError = null) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = MailErrors.classify(e)
                if (error is MailError.AuthFailed) account.onAuthFailure()
                update { it.copy(actionError = error) }
            }
        }
    }

    /**
     * Nothing to release here. `SessionHolder`'s hold is a flag, not a count, and only the list
     * view model owns it -- it pairs `acquire()` with `release()` around the page's lifecycle.
     * This view model never calls `acquire()`, so the `release()` that used to live here armed
     * an idle close on a connection someone else was holding: popping back to the list runs the
     * list's ON_RESUME `acquire()` first and this `onCleared()` after it.
     */
    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_NAME_LENGTH = 120
        private val EMPTY_BODY = MailBody(null, null, emptyList(), emptyMap())
        private val UNPARSEABLE_VERDICT = LinkVerdict(host = "", shownHost = null, mismatch = false, punycode = false, insecure = false)
        private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

        /**
         * The only schemes `openLink` can actually launch, and therefore the only ones
         * [targetOf] offers an Open button for. Both sides read this one list, so the dialog
         * cannot drift into an enabled Open that does nothing -- which is what it showed for
         * every other scheme, `tel:`/`ftp:`/`intent:` included. (The sanitizer's own `a[href]`
         * allowlist is these same three, so nothing new becomes launchable either way.)
         */
        internal val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

        /**
         * An http(s) href is canonicalized once, with OkHttp's `HttpUrl`, which reads a URL the way a
         * browser does (`\` is `/`, the last `@` ends the userinfo), and that one string is what
         * [MailWarnings.checkLink] judges, what the dialog shows and what Open launches. Judged as written,
         * `https://evil.example\@ntust.edu.tw` passed as ntust.edu.tw while the browser went to
         * evil.example. An http(s) href `HttpUrl` rejects is shown as written, claims no host and cannot be
         * opened. Anything else (mailto:) is judged and opened as written.
         */
        internal fun targetOf(link: MailLink): LinkTarget {
            val href = link.href.trim()
            val scheme = href.substringBefore(':', missingDelimiterValue = "").lowercase()
            if (scheme != "http" && scheme != "https") {
                return LinkTarget(link.href, MailWarnings.checkLink(link.text, link.href), canOpen = scheme in OPENABLE_SCHEMES)
            }
            val canonical = href.toHttpUrlOrNull()?.toString()
                ?: return LinkTarget(link.href, UNPARSEABLE_VERDICT, canOpen = false)
            return LinkTarget(canonical, MailWarnings.checkLink(link.text, canonical), canOpen = true)
        }

        /**
         * At most [MAX_NAME_LENGTH] UTF-16 code units, the extension kept intact (the stem is
         * shortened instead), never cutting a surrogate pair in half, and trimmed of leading or
         * trailing dots/spaces only *after* truncation -- truncating first can otherwise leave a
         * new trailing "." or " " that trimming beforehand never saw.
         */
        fun safeFileName(name: String): String {
            val cleaned = TextCleaning.clean(name).replace(UNSAFE_NAME, "_")
            val truncated = truncateKeepingExtension(cleaned, MAX_NAME_LENGTH)
            return truncated.trim('.', ' ').ifBlank { "attachment" }
        }

        private fun truncateKeepingExtension(name: String, maxLength: Int): String {
            if (name.length <= maxLength) return name
            val dot = name.lastIndexOf('.')
            // Only treat it as a real extension when there's a non-empty stem before it and the
            // suffix is short enough to plausibly be one, not just some dot deep in a long name.
            val hasExtension = dot > 0 && dot < name.length - 1 && (name.length - dot - 1) <= 10
            val extension = if (hasExtension) name.substring(dot) else ""
            val stem = if (hasExtension) name.substring(0, dot) else name
            val stemBudget = (maxLength - extension.length).coerceAtLeast(1)
            return truncateAtCodePointBoundary(stem, stemBudget) + extension
        }

        private fun truncateAtCodePointBoundary(s: String, maxLength: Int): String {
            if (maxLength <= 0) return ""
            if (s.length <= maxLength) return s
            var end = maxLength
            if (Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }

        /**
         * Lenient like [MailWarnings]' own host extraction (`URL_HOST`): the host is "whatever
         * comes after `scheme://[userinfo@]` up to the next `/`, `?`, `#` or `:`", never
         * validated against `java.net.URI`'s stricter reg-name grammar. `URI.getHost()` returns
         * null for a host `java.net.URI` refuses to parse -- an underscore is enough -- which
         * previously fell through this whole function to a "different from everything" fallback
         * instead of comparing against **a** host, the same host WebView itself reports.
         *
         * [RegexOption.DOT_MATCHES_ALL] plus possessive quantifiers (`*+`/`++`) on every group:
         * without them, a line terminator (e.g. inside the fragment) makes `.` fail to match,
         * and the engine then backtracks across this pattern's several optional groups hunting
         * for an alternative split -- quadratic on a long attacker-controlled string (an image
         * URL flows through here on the main thread, via the remembered allowlist). Every group
         * here is unambiguous with its neighbor (each character class excludes the delimiter
         * that starts the next group), so a possessive quantifier never rejects a match a greedy
         * one would have found -- it only forecloses backtracking that could never have
         * succeeded anyway. The port group additionally requires at least one digit (`[0-9]++`,
         * not `[0-9]*+`): a bare `:` with no digits after it is not a port at all, so it's left
         * for the path group to absorb instead of being parsed as an empty one.
         */
        private val URL_PARTS = Regex(
            """^([a-zA-Z][a-zA-Z0-9+.\-]*+)://(?:[^/?#@]*+@)?([^/?#:]*+)(?::([0-9]++))?([^?#]*+)(\?[^#]*+)?(#.*+)?$""",
            RegexOption.DOT_MATCHES_ALL,
        )

        // scheme://[userinfo@]host[:port|/path|?query|#fragment...], host captured separately so
        // it can be punycode-encoded before URL_PARTS ever sees it (see parseUrl).
        private val AUTHORITY_HOST = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?)([^/?#:]+)(.*)$""", RegexOption.DOT_MATCHES_ALL)

        private val SCHEME_PREFIX = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")

        private data class UrlParts(val scheme: String, val host: String, val port: Int?, val path: String, val query: String, val fragment: String)

        /**
         * Splits [raw] the way [normalizedHref] needs (used only for the remote-image allowlist
         * -- link matching addresses by index instead, see [linkTarget]), or null for anything
         * that isn't a `scheme://...` URL (e.g. `mailto:`) or ends up with no host at all. Two
         * WebView/Chromium quirks are folded in before the lenient host/path split, since a
         * mail's own un-normalized markup needs to match the href WebView hands back on tap:
         * - for a "special" scheme (`http`/`https`), a backslash anywhere in the URL is
         *   equivalent to a forward slash (WHATWG URL Standard) -- `https://evil.example\ntust`
         *   is `https://evil.example/ntust`, not a URL with a literal backslash in its path;
         * - a Unicode host is punycode-encoded ([IDN.toASCII]) so it matches the ASCII form
         *   WebView always reports.
         */
        private fun parseUrl(raw: String): UrlParts? {
            val trimmed = raw.trim()
            val scheme = SCHEME_PREFIX.find(trimmed)?.groupValues?.get(1)?.lowercase()
            val backslashFolded = if (scheme == "http" || scheme == "https") trimmed.replace('\\', '/') else trimmed
            val ascii = toAsciiAuthority(backslashFolded)
            val m = URL_PARTS.find(ascii) ?: return null
            val parsedScheme = m.groupValues[1].lowercase()
            val host = m.groupValues[2].lowercase()
            if (host.isEmpty()) return null
            val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it != defaultPort(parsedScheme) }
            val path = canonicalPath(m.groupValues[4]).ifEmpty { "/" }
            val query = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { "?" + canonicalPath(it.removePrefix("?")) }.orEmpty()
            val fragment = m.groupValues[6].takeIf { it.isNotEmpty() }?.let { "#" + canonicalPath(it.removePrefix("#")) }.orEmpty()
            return UrlParts(parsedScheme, host, port, path, query, fragment)
        }

        /**
         * A same-URL key tolerant of WebView's own URL normalization: lowercase scheme and host
         * -- **never** the path, which stays case-sensitive -- "/" for an empty path, default
         * http(s) port dropped, and the path/query/fragment run through [canonicalPath] so a raw
         * space or non-ASCII character compares equal to Chromium's percent-encoded form of the
         * same character. Falls back to a trimmed, lowercased copy of the raw string for anything
         * that isn't a `scheme://...` URL at all (e.g. `mailto:`) so those still compare equal
         * when identical.
         */
        internal fun normalizedHref(raw: String): String {
            val trimmed = raw.trim()
            val parts = parseUrl(trimmed) ?: return trimmed.lowercase()
            return buildString {
                append(parts.scheme).append("://").append(parts.host)
                if (parts.port != null) append(':').append(parts.port)
                append(parts.path).append(parts.query).append(parts.fragment)
            }
        }

        private fun toAsciiAuthority(s: String): String {
            val match = AUTHORITY_HOST.find(s) ?: return s
            val (prefix, host, rest) = match.destructured
            if (host.isEmpty() || host.all { it.code < 0x80 }) return s
            return prefix + runCatching { IDN.toASCII(host) }.getOrDefault(host) + rest
        }

        private fun defaultPort(scheme: String): Int = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

        private fun decodePercent(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        // Unreserved (RFC 3986) plus the sub-delims and structural characters a path, query or
        // fragment carries literally; everything else -- space, control characters, quotes,
        // angle/curly brackets and any non-ASCII byte -- is exactly what Chromium's own percent-
        // encode sets cover, so it's percent-encoded here the same way.
        private const val PATH_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?#"

        /**
         * Decodes any existing percent-encoding first, then re-encodes: this way a raw space, a
         * raw non-ASCII character and an already-percent-encoded form of either all converge on
         * the identical canonical output, the way Chromium's own URL canonicalization does,
         * instead of only matching when both sides happened to already agree on encoding.
         */
        private fun canonicalPath(raw: String): String {
            if (raw.isEmpty()) return raw
            val decoded = decodePercent(raw)
            val bytes = decoded.toByteArray(Charsets.UTF_8)
            val needsEncoding = bytes.any { (it.toInt() and 0xFF).toChar() !in PATH_SAFE }
            if (!needsEncoding) return decoded
            return buildString {
                for (byte in bytes) {
                    val c = (byte.toInt() and 0xFF)
                    if (c.toChar() in PATH_SAFE) append(c.toChar()) else append('%').append("%02X".format(c))
                }
            }
        }
    }
}
