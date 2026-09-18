package org.ntust.app.tigerduck.mail.imap

import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.io.Closeable
import java.io.OutputStream

enum class AppendFlag { SEEN, DRAFT }

/**
 * One authenticated IMAP connection. Blocking and single-threaded: callers
 * run it on an IO dispatcher and never share it between coroutines at once.
 * Every method throws [org.ntust.app.tigerduck.mail.MailError].
 */
interface MailSession : Closeable {
    /** A cheap round trip that proves the connection is still alive without SELECTing (or deselecting) any folder. */
    fun noop()
    fun listFolders(): List<String>
    fun status(folder: String): FolderStatus
    fun fetchPage(folder: String, beforeSeq: Int?, pageSize: Int): MailPage
    fun fetchSince(folder: String, fromUid: Long): List<MailSummary>
    fun fetchByUids(folder: String, uids: List<Long>): List<MailSummary>
    fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags>
    fun fetchBody(folder: String, uid: Long): MailBody
    fun writeRawSource(folder: String, uid: Long, out: OutputStream)
    fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream)
    fun setSeen(folder: String, uids: List<Long>, seen: Boolean)
    fun setAnswered(folder: String, uid: Long)

    /**
     * COPY + `\Deleted`; EXPUNGE only if every `\Deleted` mail in [folder] is
     * ours. Returns whether it expunged. On throw, [uid] may already be
     * `\Deleted` on the server (the flag is set before the EXPUNGE check);
     * the caller must still record it as owned so a later call can expunge it.
     */
    fun move(folder: String, uid: Long, target: String, ownedDeleted: Set<Long>): Boolean

    /**
     * Sets `\Deleted` and EXPUNGEs only if every `\Deleted` mail in [folder]
     * is ours. Returns whether it expunged. On throw, [uid] may already be
     * `\Deleted` on the server; the caller must still record it as owned so
     * a later call can expunge it.
     */
    fun deletePermanently(folder: String, uid: Long, ownedDeleted: Set<Long>): Boolean

    /** UIDs newest first. Throws [org.ntust.app.tigerduck.mail.MailError.SearchUnsupported] when the server can't. */
    fun search(folder: String, query: String): List<Long>
    fun append(folder: String, rfc822: ByteArray, flags: Set<AppendFlag>)
    fun hasRecentMessageId(folder: String, messageId: String, window: Int): Boolean
    fun newestSenderName(folder: String, address: String, window: Int): String?
}

fun interface MailSessionFactory {
    /** Connects and logs in. Throws [org.ntust.app.tigerduck.mail.MailError]. */
    fun open(credentials: MailCredentials): MailSession
}
