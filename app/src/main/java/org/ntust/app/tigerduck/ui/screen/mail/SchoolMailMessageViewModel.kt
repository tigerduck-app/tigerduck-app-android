package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.sanitize.SanitizedHtml
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.warning.LinkVerdict
import org.ntust.app.tigerduck.mail.warning.MailWarning
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import java.io.File
import java.io.OutputStream
import java.net.URI
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
) : ViewModel() {
    val folder: String = savedStateHandle.get<String>("folder").orEmpty()
    val uid: Long = savedStateHandle.get<Long>("uid") ?: -1L

    enum class ViewMode { FORMATTED, PLAIN, SOURCE }

    sealed interface Content {
        data object Loading : Content
        data class Failed(val error: MailError) : Content
        data class Ready(
            val summary: MailSummary,
            val body: MailBody,
            val html: SanitizedHtml?,
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
        val parseFailed: Boolean = false,
        val source: String? = null,
        val sourceLoading: Boolean = false,
        /** The mail's size in bytes while asking whether to load a large source. */
        val confirmLargeSource: Long? = null,
        val confirmOpen: MailAttachment? = null,
        val openRequest: OpenRequest? = null,
        val downloading: String? = null,
        val savedCount: Int = 0,
        val confirmDeleteForever: Boolean = false,
        val actionError: MailError? = null,
        /** Moved, deleted or marked unread: the screen pops. */
        val closed: Boolean = false,
    ) {
        val folderKind: SpecialFolder? get() = folders?.kindOf(folder)
    }

    private val _state = MutableStateFlow(UiState(folder = folder))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun update(transform: (UiState) -> UiState) = _state.update(transform)

    fun load() {
        viewModelScope.launch {
            update { it.copy(content = Content.Loading) }
            try {
                val folders = repository.folders()
                update { it.copy(folders = folders) }
                val summary = repository.summary(folder, uid) ?: throw MailError.Protocol("message is gone")
                val body = try {
                    repository.body(folder, uid)
                } catch (e: MailError.Protocol) {
                    // A body we cannot parse is still readable as its source (spec §9.1).
                    update { it.copy(content = Content.Ready(summary, EMPTY_BODY, null, "", emptyList()), parseFailed = true) }
                    markSeen(summary)
                    selectMode(ViewMode.SOURCE)
                    return@launch
                }
                val html = body.html?.let { HtmlSanitizer.sanitize(it, allowRemoteImages = false) }
                val plain = body.plain ?: html?.let { HtmlSanitizer.plainText(it.html) }.orEmpty()
                val warnings = MailWarnings.evaluate(summary.from, summary.subject, plain, html?.links.orEmpty(), body.attachments)
                update {
                    it.copy(
                        content = Content.Ready(summary, body, html, plain, warnings),
                        mode = if (html != null) ViewMode.FORMATTED else ViewMode.PLAIN,
                    )
                }
                markSeen(summary)
            } catch (e: MailError) {
                if (e is MailError.AuthFailed) account.onAuthFailure()
                update { it.copy(content = Content.Failed(e)) }
            }
        }
    }

    private suspend fun markSeen(summary: MailSummary) {
        notifier.cancelMessage(uid)
        if (!summary.flags.seen) runCatching { repository.setSeen(folder, uid, true) }
    }

    fun loadRemoteImages() {
        val ready = _state.value.content as? Content.Ready ?: return
        val html = ready.body.html ?: return
        update { it.copy(remoteImagesAllowed = true, content = ready.copy(html = HtmlSanitizer.sanitize(html, allowRemoteImages = true))) }
    }

    fun selectMode(mode: ViewMode) {
        update { it.copy(mode = mode) }
        val s = _state.value
        if (mode != ViewMode.SOURCE || s.source != null || s.sourceLoading) return
        viewModelScope.launch {
            val size = runCatching { repository.messageSize(folder, uid) }.getOrDefault(0L)
            if (size > LARGE_SOURCE_BYTES) update { it.copy(confirmLargeSource = size) } else loadSource()
        }
    }

    fun confirmLargeSource(load: Boolean) {
        update { it.copy(confirmLargeSource = null) }
        if (load) {
            viewModelScope.launch { loadSource() }
        } else if (!_state.value.parseFailed) {
            update { it.copy(mode = readableMode()) }
        }
    }

    private fun readableMode(): ViewMode =
        if ((_state.value.content as? Content.Ready)?.html != null) ViewMode.FORMATTED else ViewMode.PLAIN

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
     * [href] comes from the WebView, which normalizes the URL it hands back on tap (lowercase
     * scheme/host, "/" for an empty path, default port dropped, its own percent-encoding) --
     * while [org.ntust.app.tigerduck.mail.sanitize.MailLink.href] is exactly what the sanitizer
     * kept from the mail's own markup. Comparing the two strings directly would miss the link
     * whenever they differ only by that normalization, silently dropping its display text and,
     * with it, [MailWarnings.checkLink]'s display-name/link mismatch check -- so both sides are
     * compared through [normalizedHref] instead of `==`.
     */
    fun linkVerdict(href: String): LinkVerdict {
        val key = normalizedHref(href)
        val text = (_state.value.content as? Content.Ready)?.html?.links
            ?.firstOrNull { normalizedHref(it.href) == key }?.text.orEmpty()
        return MailWarnings.checkLink(text, href)
    }

    // --- attachments --------------------------------------------------------------------

    /** Spec §9.5: never auto-open; risky files and HTML/SVG ask first (A.4). */
    fun needsConfirmation(attachment: MailAttachment): Boolean {
        val ready = _state.value.content as? Content.Ready
        val context = listOfNotNull(ready?.summary?.subject, ready?.plain).joinToString("\n")
        val type = attachment.contentType.lowercase()
        return MailWarnings.riskReason(attachment.fileName, attachment.contentType, context) != null ||
            type == "text/html" || type == "image/svg+xml"
    }

    fun requestOpen(attachment: MailAttachment) {
        if (needsConfirmation(attachment)) update { it.copy(confirmOpen = attachment) } else open(attachment)
    }

    fun confirmOpen(open: Boolean) {
        val attachment = _state.value.confirmOpen ?: return
        update { it.copy(confirmOpen = null) }
        if (open) open(attachment)
    }

    private fun open(attachment: MailAttachment) = act {
        update { it.copy(downloading = attachment.partId) }
        try {
            val file = download(attachment)
            update { it.copy(openRequest = OpenRequest(file, attachment.contentType)) }
        } finally {
            update { it.copy(downloading = null) }
        }
    }

    fun consumeOpenRequest() = update { it.copy(openRequest = null) }

    /**
     * Into `attachmentsDir/<random>/<name>`, which the FileProvider serves; files older than a
     * day are pruned. The directory bookkeeping here (mkdir, pruning, opening an empty output
     * stream) is deliberately not pushed onto a background dispatcher itself: [SchoolMailRepository.writeAttachment]
     * is the actual IO boundary and already dispatches there in production, so this stays on
     * the calling coroutine's dispatcher like every other action in this view model.
     */
    private suspend fun download(attachment: MailAttachment): File {
        val dir = cache.attachmentsDir
        dir.mkdirs()
        val cutoff = System.currentTimeMillis() - DAY_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val slot = File(dir, UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(slot, safeFileName(attachment.fileName))
        file.outputStream().use { out ->
            repository.writeAttachment(folder, uid, attachment.partId, out)
        }
        return file
    }

    fun saveAttachment(attachment: MailAttachment, open: () -> OutputStream?) = act {
        val out = open() ?: throw MailError.Protocol("no output stream")
        out.use { repository.writeAttachment(folder, uid, attachment.partId, it) }
        update { it.copy(savedCount = it.savedCount + 1) }
    }

    fun consumeSaved() = update { it.copy(savedCount = 0) }

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
     * [SchoolMailRepository], so once the user backs out, [SchoolMailListViewModel.onResume]
     * finds that folder's cache gone and reloads it from the server on its own.
     */
    fun moveTo(target: String) = act {
        repository.move(folder, uid, target)
        update { it.copy(closed = true) }
    }

    fun delete() = act {
        if (repository.deletesPermanently(folder)) {
            update { it.copy(confirmDeleteForever = true) }
        } else {
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

    override fun onCleared() {
        repository.release()
    }

    companion object {
        const val LARGE_SOURCE_BYTES = 5L * 1024 * 1024
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val EMPTY_BODY = MailBody(null, null, emptyList(), emptyMap())
        private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

        fun safeFileName(name: String): String =
            TextCleaning.clean(name).replace(UNSAFE_NAME, "_").trim('.', ' ').take(120).ifBlank { "attachment" }

        /**
         * A same-URL key tolerant of WebView's own URL normalization: lowercase scheme and host,
         * "/" for an empty path, default http(s) port dropped, percent-encoding decoded, and
         * leading/trailing whitespace trimmed. Falls back to a trimmed, lowercased copy of the
         * raw string for anything [URI] can't parse (or that has no scheme/host, e.g. `mailto:`)
         * so those still compare equal when identical.
         */
        internal fun normalizedHref(raw: String): String {
            val trimmed = raw.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host?.lowercase()
            if (uri == null || scheme == null || host == null) return trimmed.lowercase()
            val port = uri.port.takeIf { it != -1 && it != defaultPort(scheme) }
            val path = decodePercent(uri.rawPath.let { if (it.isNullOrEmpty()) "/" else it })
            val query = uri.rawQuery?.let { "?${decodePercent(it)}" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#${decodePercent(it)}" }.orEmpty()
            return buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(':').append(port)
                append(path).append(query).append(fragment)
            }
        }

        private fun defaultPort(scheme: String): Int = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

        private fun decodePercent(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    }
}
