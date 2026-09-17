package org.ntust.app.tigerduck.ui.screen.mail

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
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val folder: String = savedStateHandle.get<String>("folder").orEmpty()
    val uid: Long = savedStateHandle.get<Long>("uid") ?: -1L

    enum class ViewMode { FORMATTED, PLAIN, SOURCE }

    enum class AttachmentAction { OPEN, SAVE }

    data class PendingAttachment(val attachment: MailAttachment, val action: AttachmentAction)

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
        /** An attachment (open or save) that needs the risky/HTML/SVG confirmation first. */
        val confirmAttachment: PendingAttachment? = null,
        val openRequest: OpenRequest? = null,
        /** Set once a save is confirmed (or needed no confirmation): the screen launches the SAF picker for it. */
        val saveRequest: MailAttachment? = null,
        /** Attachment part IDs currently being downloaded, open or save alike -- keyed per part so two different attachments don't clear each other's spinner. */
        val downloading: Set<String> = emptySet(),
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
                val body = try {
                    repository.body(folder, uid)
                } catch (e: MailError.Protocol) {
                    // A body we cannot parse is still readable as its source (spec §9.1).
                    update { it.copy(content = Content.Ready(summary, EMPTY_BODY, null, "", emptyList()), parseFailed = true) }
                    markSeen(summary)
                    selectMode(ViewMode.SOURCE)
                    return@launch
                }
                // Whenever this sanitizes, it honors whatever the state already says about
                // remote images rather than hard-coding false, so a load that runs while that
                // flag is already true (in principle reachable from a future retry path) never
                // silently re-blocks images the user already opted into.
                val allowRemote = _state.value.remoteImagesAllowed
                val html = body.html?.let { HtmlSanitizer.sanitize(it, allowRemoteImages = allowRemote) }
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
            val size = try {
                repository.messageSize(folder, uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A failed size check must not skip the large-source confirmation -- treat
            // "unknown" as "ask", using the confirmation threshold itself as the shown size
            // since the real one couldn't be read.
            if (size == null || size > LARGE_SOURCE_BYTES) {
                update { it.copy(confirmLargeSource = size ?: LARGE_SOURCE_BYTES) }
            } else {
                loadSource()
            }
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
     *
     * A decoy link sharing the same normalized href as a legitimate one must not hide a mismatch
     * (spec A.4.2): every link matching [href] is evaluated, and if any of them mismatches, that
     * one wins. When no link matches at all -- a normalization gap, or a navigation that isn't
     * from any `<a>` the sanitizer kept -- there is no display text to compare against, so
     * [noMatchVerdict] answers directly rather than reaching [MailWarnings.checkLink] through an
     * incidental empty-string call: it still reports the href's own host/insecure/punycode, just
     * never a mismatch, since there was never any claimed link text to be wrong about.
     */
    fun linkVerdict(href: String): LinkVerdict {
        val key = normalizedHref(href)
        val links = (_state.value.content as? Content.Ready)?.html?.links.orEmpty().filter { normalizedHref(it.href) == key }
        if (links.isEmpty()) return noMatchVerdict(href)
        val verdicts = links.map { MailWarnings.checkLink(it.text, href) }
        return verdicts.firstOrNull { it.mismatch } ?: verdicts.first()
    }

    /** See [linkVerdict]: no sanitized link shares this href, so there is nothing to mismatch. */
    private fun noMatchVerdict(href: String): LinkVerdict {
        val trimmedHref = href.trim()
        if (trimmedHref.startsWith("mailto:", ignoreCase = true)) {
            return LinkVerdict(
                host = trimmedHref.substringAfter(':').substringBefore('?'),
                shownHost = null,
                mismatch = false,
                punycode = false,
                insecure = false,
            )
        }
        val host = parseUrl(trimmedHref)?.host.orEmpty()
        return LinkVerdict(host = host, shownHost = null, mismatch = false, punycode = "xn--" in host, insecure = trimmedHref.startsWith("http://", ignoreCase = true))
    }

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
                }
                committed = true
                update { it.copy(savedCount = it.savedCount + 1) }
            } finally {
                if (opened && !committed) withContext(io + NonCancellable) { onFailure() }
                update { it.copy(downloading = it.downloading - attachment.partId) }
            }
        }
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
     * [SchoolMailRepository], and the list's own `load()` always re-fetches the selected folder
     * from the server whenever the list screen is re-entered, so once the user backs out it
     * shows the fresh page on its own.
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
        private const val MAX_NAME_LENGTH = 120
        private val EMPTY_BODY = MailBody(null, null, emptyList(), emptyMap())
        private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

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
         */
        private val URL_PARTS = Regex(
            """^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/?#@]*@)?([^/?#:]*)(?::([0-9]*))?([^?#]*)(\?[^#]*)?(#.*)?$""",
        )

        // scheme://[userinfo@]host[:port|/path|?query|#fragment...], host captured separately so
        // it can be punycode-encoded before URL_PARTS ever sees it (see parseUrl).
        private val AUTHORITY_HOST = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?)([^/?#:]+)(.*)$""", RegexOption.DOT_MATCHES_ALL)

        private val SCHEME_PREFIX = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")

        private data class UrlParts(val scheme: String, val host: String, val port: Int?, val path: String, val query: String, val fragment: String)

        /**
         * Splits [raw] the way [normalizedHref] needs, or null for anything that isn't a
         * `scheme://...` URL (e.g. `mailto:`, which [LinkVerdict] callers handle separately) or
         * ends up with no host at all. Two WebView/Chromium quirks are folded in before the
         * lenient host/path split, since a mail's own un-normalized markup needs to match the
         * href WebView hands back on tap:
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
