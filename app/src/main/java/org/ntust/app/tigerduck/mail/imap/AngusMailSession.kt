package org.ntust.app.tigerduck.mail.imap

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimePart
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.eclipse.angus.mail.imap.protocol.SearchSequence
import org.eclipse.angus.mail.imap.protocol.Status
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.MailLimits
import org.ntust.app.tigerduck.mail.MailProperties
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.MailServerConfigSource
import org.ntust.app.tigerduck.mail.mime.AddressParser
import org.ntust.app.tigerduck.mail.mime.EncodedWords
import org.ntust.app.tigerduck.mail.mime.MailCharsets
import org.ntust.app.tigerduck.mail.mime.MimeWalker
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.InlineImage
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class AngusMailSessionFactory(private val configs: MailServerConfigSource) : MailSessionFactory {
    constructor(config: MailServerConfig) : this(MailServerConfigSource { config })

    override fun open(credentials: MailCredentials): MailSession {
        val config = configs.current()
        val session = Session.getInstance(MailProperties.imap(config))
        val store = session.getStore(MailProperties.imapProtocol(config)) as IMAPStore
        try {
            store.connect(config.imap.host, config.imap.port, credentials.loginName, credentials.password)
        } catch (e: Exception) {
            runCatching { store.close() }
            throw MailErrors.classify(e)
        }
        return AngusMailSession(store, session)
    }
}

/**
 * Keeps at most one folder open, so a session is one TCP connection: every
 * store-level command (LIST, STATUS, APPEND) closes the open folder first
 * and reuses the same pooled connection. Folders are always closed without
 * expunging (Angus EXAMINEs before CLOSE when the server has no UNSELECT).
 */
class AngusMailSession internal constructor(
    private val store: IMAPStore,
    private val session: Session,
) : MailSession {
    private var openFolder: IMAPFolder? = null

    /** What one MIME leaf becomes in a [MailBody]. Internal so the rules in [kindOf] are directly testable. */
    internal sealed interface PartKind {
        data class Text(val html: Boolean) : PartKind
        data class Inline(val contentId: String) : PartKind
        data object Attachment : PartKind
    }

    // --- plumbing ------------------------------------------------------------

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw MailErrors.classify(e)
    }

    private fun closeOpen() {
        openFolder?.takeIf { it.isOpen }?.let { runCatching { it.close(false) } }
        openFolder = null
    }

    private fun folder(name: String, mode: Int): IMAPFolder {
        val current = openFolder
        if (current != null && current.isOpen && current.fullName == name &&
            (current.mode == mode || current.mode == Folder.READ_WRITE)
        ) {
            // A held-open folder only learns about mail delivered by another
            // connection through an untagged EXISTS, and the server attaches
            // that to the *next* command's response rather than retroactively
            // to whatever command happened to be racing the delivery. Without
            // this NOOP, the first fetch after new mail arrives can see the
            // updated count but miss that message's data in the same round
            // trip (reproduced against GreenMail; not a test-double quirk).
            current.doCommand(IMAPFolder.ProtocolCommand { p: IMAPProtocol -> p.simpleCommand("NOOP", null) })
            return current
        }
        closeOpen()
        val f = store.getFolder(name) as IMAPFolder
        f.open(mode)
        openFolder = f
        return f
    }

    private fun message(f: IMAPFolder, uid: Long): IMAPMessage =
        f.getMessageByUID(uid) as? IMAPMessage ?: throw MailError.Protocol("message $uid is gone")

    private fun summaryProfile() = FetchProfile().apply {
        add(FetchProfile.Item.FLAGS)
        add(UIDFolder.FetchProfileItem.UID)
        add(IMAPFolder.FetchProfileItem.INTERNALDATE)
        add(FetchProfile.Item.SIZE)
        add(FetchProfile.Item.CONTENT_INFO)
        SUMMARY_HEADERS.forEach { add(it) }
    }

    private fun toSummary(f: IMAPFolder, m: IMAPMessage): MailSummary {
        fun header(name: String): String? = m.getHeader(name)?.firstOrNull()
        val flags = m.flags
        return MailSummary(
            uid = f.getUID(m),
            from = AddressParser.parseList(header("From")).firstOrNull(),
            replyTo = AddressParser.parseList(header("Reply-To")),
            to = AddressParser.parseList(header("To")),
            cc = AddressParser.parseList(header("Cc")),
            subject = TextCleaning.clean(EncodedWords.decode(header("Subject"))),
            sentAt = runCatching { m.sentDate?.toInstant() }.getOrNull(),
            receivedAt = runCatching { m.receivedDate?.toInstant() }.getOrNull(),
            flags = MailFlags(
                seen = flags.contains(Flags.Flag.SEEN),
                answered = flags.contains(Flags.Flag.ANSWERED),
                flagged = flags.contains(Flags.Flag.FLAGGED),
                deleted = flags.contains(Flags.Flag.DELETED),
                draft = flags.contains(Flags.Flag.DRAFT),
            ),
            sizeBytes = m.size.toLong().coerceAtLeast(0),
            hasAttachments = runCatching { MimeWalker.leaves(m).any { kindOf(it.part) == PartKind.Attachment } }
                .getOrDefault(false),
            messageId = header("Message-ID")?.trim(),
            inReplyTo = header("In-Reply-To")?.trim(),
            references = header("References")?.replace(WHITESPACE, " ")?.trim(),
            returnPath = header("Return-Path")?.trim(),
        )
    }

    // --- read path -------------------------------------------------------------

    /**
     * Unlike [status], this never closes an already-open folder first: it
     * NOOPs whichever folder is currently selected (NOOP is valid in any
     * IMAP state), or a throwaway, never-opened `IMAPFolder` object when
     * none is -- either way, one lightweight round trip with no
     * UNSELECT/re-SELECT cost for the next real command.
     */
    override fun noop() = io {
        val f = openFolder?.takeIf { it.isOpen } ?: store.getFolder("INBOX") as IMAPFolder
        f.doCommand(IMAPFolder.ProtocolCommand { p: IMAPProtocol -> p.simpleCommand("NOOP", null) })
        Unit
    }

    override fun listFolders(): List<String> = io {
        closeOpen()
        store.defaultFolder.list("*")
            .filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
            .map { it.fullName }
    }

    /**
     * [name] is the decoded name, never [SpecialFolder.imapName]: Angus takes
     * mailbox names as Unicode and encodes them to modified UTF-7 on the wire
     * itself (`IMAPProtocol.writeMailboxName` -> `BASE64MailboxEncoder`,
     * because Mail2000's CAPABILITY has no `UTF8=ACCEPT`). Handing it the
     * already-encoded form would encode the `&` again and leave the account
     * with a mailbox literally named `&W8RO9lCZTv1TIw-`.
     *
     * `IMAPFolder.create` answers false for any refusal — including "already
     * exists", which the server reports as a tagged NO rather than an error —
     * so a fresh `LIST` decides before that is treated as a failure.
     */
    override fun createFolder(name: String) = io {
        closeOpen()
        val f = store.getFolder(name) as IMAPFolder
        if (!f.create(Folder.HOLDS_MESSAGES) && !f.exists()) {
            throw MailError.Protocol("could not create folder $name")
        }
        Unit
    }

    override fun status(folder: String): FolderStatus = io {
        closeOpen()
        val f = store.getFolder(folder) as IMAPFolder
        val status = f.doCommand(IMAPFolder.ProtocolCommand { p: IMAPProtocol ->
            p.status(folder, arrayOf("UIDVALIDITY", "UIDNEXT", "MESSAGES", "UNSEEN"))
        }) as Status
        FolderStatus(
            uidValidity = status.uidvalidity,
            uidNext = status.uidnext,
            messages = status.total,
            unseen = status.unseen,
        )
    }

    override fun fetchPage(folder: String, beforeSeq: Int?, pageSize: Int): MailPage = io {
        val f = folder(folder, Folder.READ_ONLY)
        val total = f.messageCount
        val end = (beforeSeq ?: (total + 1)) - 1
        if (end < 1) return@io MailPage(f.uidValidity, total, emptyList(), null)
        val start = maxOf(1, end - pageSize + 1)
        val messages = f.getMessages(start, end)
        f.fetch(messages, summaryProfile())
        val summaries = messages.reversed().map { toSummary(f, it as IMAPMessage) }.filterNot { it.flags.deleted }
        MailPage(f.uidValidity, total, summaries, if (start > 1) start else null)
    }

    override fun fetchSince(folder: String, fromUid: Long): List<MailSummary> = io {
        val f = folder(folder, Folder.READ_ONLY)
        val messages = f.getMessagesByUID(fromUid, UIDFolder.LASTUID)
        if (messages.isEmpty()) return@io emptyList()
        f.fetch(messages, summaryProfile())
        // IMAP's `n:*` always includes the last mail even when its UID is below n.
        messages.map { toSummary(f, it as IMAPMessage) }.filter { it.uid >= fromUid }
    }

    override fun fetchByUids(folder: String, uids: List<Long>): List<MailSummary> = io {
        if (uids.isEmpty()) return@io emptyList()
        val f = folder(folder, Folder.READ_ONLY)
        val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        if (messages.isEmpty()) return@io emptyList()
        f.fetch(messages, summaryProfile())
        messages.map { toSummary(f, it as IMAPMessage) }.filterNot { it.flags.deleted }.sortedByDescending { it.uid }
    }

    override fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags> = io {
        if (uids.isEmpty()) return@io emptyMap()
        val f = folder(folder, Folder.READ_ONLY)
        val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
        messages.associate { m ->
            val fl = m.flags
            f.getUID(m) to MailFlags(
                seen = fl.contains(Flags.Flag.SEEN),
                answered = fl.contains(Flags.Flag.ANSWERED),
                flagged = fl.contains(Flags.Flag.FLAGGED),
                deleted = fl.contains(Flags.Flag.DELETED),
                draft = fl.contains(Flags.Flag.DRAFT),
            )
        }
    }

    override fun fetchBody(folder: String, uid: Long): MailBody = io {
        val f = folder(folder, Folder.READ_ONLY)
        val m = message(f, uid)
        val html = mutableListOf<String>()
        val plain = mutableListOf<String>()
        val attachments = mutableListOf<MailAttachment>()
        val inline = LinkedHashMap<String, InlineImage>()
        for (leaf in MimeWalker.leaves(m)) {
            when (val kind = kindOf(leaf.part)) {
                is PartKind.Text -> if (kind.html) html += readText(leaf.part) else plain += readText(leaf.part)
                // [kindOf] only names a part inline once BODYSTRUCTURE has put it under the
                // limit, and the same limit bounds the read: what the structure claimed and
                // what the server then sends need not agree.
                is PartKind.Inline -> inline[kind.contentId] =
                    InlineImage(baseType(leaf.part), readBounded(leaf.part, MailLimits.INLINE_IMAGE_BYTES.toInt()))
                PartKind.Attachment -> attachments += MailAttachment(
                    partId = leaf.path,
                    fileName = TextCleaning.clean(runCatching { leaf.part.fileName }.getOrNull()).ifBlank { "attachment" },
                    contentType = baseType(leaf.part),
                    sizeBytes = leaf.part.size.toLong().coerceAtLeast(0),
                    contentId = runCatching { (leaf.part as? MimePart)?.contentID }.getOrNull()
                        ?.trim()?.removePrefix("<")?.removeSuffix(">"),
                )
            }
        }
        MailBody(
            html = html.joinToString("\n").ifEmpty { null },
            plain = plain.joinToString("\n\n").ifEmpty { null },
            attachments = attachments,
            inlineImages = inline,
        )
    }

    override fun writeRawSource(folder: String, uid: Long, out: OutputStream) = io {
        // With mail.imap.peek this is BODY.PEEK[]: reading the source never marks the mail read.
        message(folder(folder, Folder.READ_ONLY), uid).writeTo(out)
    }

    /**
     * Bounded by [MailLimits.ATTACHMENT_BYTES] as it streams, never by the size the attachment
     * declared: that number is `BODYSTRUCTURE` metadata, chosen by the sender, and a part is free
     * to claim a few kilobytes and then deliver without end. [writeRawSource]'s caller can refuse
     * an oversized mail up front because the server's own `RFC822.SIZE` covers the whole message;
     * there is no equally trustworthy number for one part, so the ceiling is applied to the bytes
     * as they arrive.
     *
     * Past the ceiling nothing is truncated — [MailError.TooLarge] is thrown, and the caller drops
     * whatever had already reached [out] (the cache slot, or the document the save targeted).
     */
    override fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) = io {
        val m = message(folder(folder, Folder.READ_ONLY), uid)
        val part = MimeWalker.find(m, partId) ?: throw MailError.Protocol("part $partId not found")
        writeBounded(part, out, MailLimits.ATTACHMENT_BYTES)
    }

    // --- write path ----------------------------------------------------------------

    /**
     * `UID SEARCH [CHARSET UTF-8] <criteria>` sent directly through the
     * protocol via [IMAPFolder.doCommand], parsing UIDs straight out of the
     * untagged `* SEARCH` response in one round trip.
     *
     * Deliberately bypasses [IMAPFolder.search]: verified against Angus
     * 2.0.5 bytecode, that method's `catch (CommandFailedException)` branch
     * (a tagged NO) unconditionally falls back to [Folder]'s default,
     * client-side search -- fetching and matching every message locally --
     * regardless of `mail.imap.throwsearchexception`, which only gates the
     * separate `SearchException` case (an unsupported/unformattable search).
     * Running the command ourselves means a NO or BAD always throws (via
     * `doCommand`'s own `handleResult`) instead of silently degrading into a
     * full local scan, so callers can tell "the server won't do this search"
     * from "here are the results" and act on spec §8.3 accordingly.
     */
    private fun uidSearch(f: IMAPFolder, term: SearchTerm): Set<Long> {
        @Suppress("UNCHECKED_CAST")
        return f.doCommand(IMAPFolder.ProtocolCommand { p: IMAPProtocol ->
            val ascii = SearchSequence.isAscii(term)
            val args = SearchSequence(p).generateSequence(term, if (ascii) null else "UTF-8")
            val responses = p.command(if (ascii) "UID SEARCH" else "UID SEARCH CHARSET UTF-8", args)
            val last = responses[responses.size - 1]
            val found = mutableSetOf<Long>()
            if (last.isOK) {
                for (r in responses) {
                    if (r is IMAPResponse && r.keyEquals("SEARCH")) {
                        while (true) {
                            val uid = r.readLong()
                            if (uid == -1L) break
                            found += uid
                        }
                    }
                }
            }
            p.notifyResponseHandlers(responses)
            p.handleResult(last)
            found
        }) as Set<Long>
    }

    /**
     * Spec §8.3 step 3: EXPUNGE only when every `\Deleted` mail in the
     * folder is one we flagged. The `\Deleted` set is read fresh from the
     * server via [uidSearch] rather than a locally cached flag state, so
     * another client's `\Deleted` mail is caught even if this session never
     * fetched (or fetched before) that message. A NO/BAD from the server
     * propagates as a thrown [MailError] instead of defaulting to "safe to
     * expunge" -- see [uidSearch]'s doc for why that matters here.
     *
     * This still cannot close the race entirely: another client can flag or
     * expunge mail server-side in the gap between this SEARCH and the
     * EXPUNGE below. Real Mail2000 has no UIDPLUS, so there is no atomic
     * "expunge exactly these UIDs" primitive to close it with; this is the
     * narrowest window achievable with COPY + STORE + EXPUNGE.
     */
    private fun expungeIfOnlyOurs(f: IMAPFolder, ours: Set<Long>): Boolean {
        val deletedUids = uidSearch(f, FlagTerm(Flags(Flags.Flag.DELETED), true))
        if (deletedUids.isEmpty() || !ours.containsAll(deletedUids)) return false
        f.expunge()
        return true
    }

    override fun setSeen(folder: String, uids: List<Long>, seen: Boolean) = io {
        if (uids.isEmpty()) return@io
        val f = folder(folder, Folder.READ_WRITE)
        val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        if (messages.isNotEmpty()) f.setFlags(messages, Flags(Flags.Flag.SEEN), seen)
    }

    override fun setAnswered(folder: String, uid: Long) = io {
        val f = folder(folder, Folder.READ_WRITE)
        // A UID that's already gone (expunged elsewhere) is a no-op, same as setSeen.
        f.getMessageByUID(uid)?.setFlag(Flags.Flag.ANSWERED, true)
        Unit
    }

    override fun move(folder: String, uid: Long, target: String, ownedDeleted: Set<Long>): Boolean = io {
        val f = folder(folder, Folder.READ_WRITE)
        val m = message(f, uid)
        // If copyMessages throws (e.g. target doesn't exist), we return here
        // and never reach setFlag below, so uid is not left \Deleted.
        f.copyMessages(arrayOf(m), store.getFolder(target))
        m.setFlag(Flags.Flag.DELETED, true)
        expungeIfOnlyOurs(f, ownedDeleted + uid)
    }

    override fun deletePermanently(folder: String, uid: Long, ownedDeleted: Set<Long>): Boolean = io {
        val f = folder(folder, Folder.READ_WRITE)
        message(f, uid).setFlag(Flags.Flag.DELETED, true)
        expungeIfOnlyOurs(f, ownedDeleted + uid)
    }

    override fun search(folder: String, query: String): List<Long> = io {
        val f = folder(folder, Folder.READ_ONLY)
        val term = OrTerm(arrayOf(FromStringTerm(query), SubjectTerm(query), BodyTerm(query)))
        val uids = try {
            uidSearch(f, term)
        } catch (e: Exception) {
            // Classify first so a real Network/Certificate/AuthFailed/ServerBusy
            // failure is never mislabeled as "search unsupported"; only the
            // generic fallback (a NO/BAD we don't otherwise recognize) and an
            // already-classified SearchException become SearchUnsupported.
            val classified = MailErrors.classify(e)
            throw if (classified is MailError.Protocol) MailError.SearchUnsupported(e) else classified
        }
        uids.sortedDescending()
    }

    override fun append(folder: String, rfc822: ByteArray, flags: Set<AppendFlag>) = io {
        closeOpen()
        val msg = MimeMessage(session, ByteArrayInputStream(rfc822))
        if (AppendFlag.SEEN in flags) msg.setFlag(Flags.Flag.SEEN, true)
        if (AppendFlag.DRAFT in flags) msg.setFlag(Flags.Flag.DRAFT, true)
        store.getFolder(folder).appendMessages(arrayOf(msg))
    }

    override fun hasRecentMessageId(folder: String, messageId: String, window: Int): Boolean {
        require(window > 0) { "window must be positive" }
        return io {
            val f = folder(folder, Folder.READ_ONLY)
            val count = f.messageCount
            if (count == 0) return@io false
            val messages = f.getMessages(maxOf(1, count - window + 1), count)
            f.fetch(messages, FetchProfile().apply { add("Message-ID") })
            messages.any { it.getHeader("Message-ID")?.firstOrNull()?.trim() == messageId }
        }
    }

    override fun newestSenderName(folder: String, address: String, window: Int): String? {
        require(window > 0) { "window must be positive" }
        return io {
            val f = folder(folder, Folder.READ_ONLY)
            val count = f.messageCount
            if (count == 0) return@io null
            val messages = f.getMessages(maxOf(1, count - window + 1), count)
            f.fetch(messages, FetchProfile().apply { add("From") })
            messages.reversed().asSequence()
                .mapNotNull { AddressParser.parseList(it.getHeader("From")?.firstOrNull()).firstOrNull() }
                .firstOrNull { it.address.equals(address, ignoreCase = true) && !it.name.isNullOrBlank() }
                ?.name
        }
    }

    override fun close() {
        closeOpen()
        runCatching { store.close() }
    }

    internal companion object {
        // Fetched as one BODY.PEEK[HEADER.FIELDS (...)] per message, so "Return-Path" -- which
        // the receiving server writes and MailWarnings.isBounce reads -- costs no extra round
        // trip and is available to the list, not just to an opened mail.
        val SUMMARY_HEADERS =
            arrayOf("From", "Reply-To", "To", "Cc", "Subject", "Date", "Message-ID", "In-Reply-To", "References", "Return-Path")
        val WHITESPACE = Regex("\\s+")

        fun kindOf(part: Part): PartKind {
            val disposition = runCatching { part.disposition?.lowercase() }.getOrNull()
            val fileName = runCatching { part.fileName }.getOrNull()
            val attachment = disposition == "attachment"
            if (!attachment && fileName == null) {
                if (part.isMimeType("text/html")) return PartKind.Text(html = true)
                if (part.isMimeType("text/plain")) return PartKind.Text(html = false)
            }
            val contentId = runCatching { (part as? MimePart)?.contentID }.getOrNull()
                ?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() }
            // A negative size means BODYSTRUCTURE did not say how big the part is. That used to
            // count as inlineable, which skipped the limit altogether for exactly the parts whose
            // size nothing had vouched for; an unknown size is now simply not small enough.
            if (!attachment && contentId != null && part.isMimeType("image/*") &&
                part.size in 0..MailLimits.INLINE_IMAGE_BYTES
            ) return PartKind.Inline(contentId)
            return PartKind.Attachment
        }

        fun baseType(part: Part): String =
            runCatching { ContentType(part.contentType).baseType.lowercase() }.getOrDefault("application/octet-stream")

        fun readText(part: Part): String {
            val bytes = readBounded(part, MailLimits.TEXT_PART_BYTES)
            val charset = runCatching { ContentType(part.contentType).getParameter("charset") }.getOrNull()
            return MailCharsets.decode(bytes, charset)
        }

        /**
         * At most [limit] bytes of [part], however much the part claims to hold (or does not claim:
         * `BODYSTRUCTURE` need not say, and nothing makes what it says true). The body of a message
         * is chosen entirely by whoever sent it, and reading it whole into a `ByteArrayOutputStream`
         * first is what turns an oversized one into an `OutOfMemoryError` -- an `Error`, so the
         * session's own `catch (e: Exception)` wrapper lets it through to the uncaught handler.
         *
         * Truncates rather than refuses: a mail that shows its first few megabytes of text, or
         * drops one outsized inline image, still opens. [writeBounded] makes the opposite trade.
         */
        fun readBounded(part: Part, limit: Int): ByteArray = part.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            copyBounded(input, out, limit.toLong())
            out.toByteArray()
        }

        /**
         * [part] into [out], up to [limit] bytes, refusing rather than truncating: a part that
         * runs past the ceiling raises [MailError.TooLarge] once the caller can still throw away
         * what was written. Truncating here would hand back a file that is short by an unknown
         * amount and indistinguishable from a whole one.
         *
         * The reported size is the smallest that is actually known to be true. The part's declared
         * size is not used even in the message: a part only reaches this point by contradicting it.
         */
        fun writeBounded(part: Part, out: OutputStream, limit: Long) {
            val more = part.inputStream.use { copyBounded(it, out, limit) }
            if (more) throw MailError.TooLarge(limit + 1, limit)
        }

        /**
         * At most [limit] bytes from [input] to [out], answering whether [input] still had more to
         * give at that point -- the one bounded copy behind both [readBounded] and [writeBounded],
         * which differ only in what they do with that answer.
         *
         * Not `InputStream.readNBytes`, which needs API 33.
         */
        private fun copyBounded(input: InputStream, out: OutputStream, limit: Long): Boolean {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (written < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                if (read < 0) return false
                out.write(buffer, 0, read)
                written += read
            }
            // Exactly at the ceiling: one more byte decides between a part that just fits and one
            // that does not, without reading any further into a stream that may never end.
            return input.read() >= 0
        }
    }
}
