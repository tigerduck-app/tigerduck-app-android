package org.ntust.app.tigerduck.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.ntust.app.tigerduck.mail.imap.AppendFlag
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.mime.AddressParser
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.Properties

/** An in-memory mailbox behind the MailSession interface, for logic tests. */
class FakeMailServer {
    class Stored(var summary: MailSummary, val body: MailBody)

    var uidValidity = 1L
    var nextUid = 1L
    val folders = linkedMapOf<String, MutableList<Stored>>(
        "INBOX" to mutableListOf(), "寄件備份匣" to mutableListOf(), "草稿匣" to mutableListOf(),
        "廣告信匣" to mutableListOf(), "回收筒" to mutableListOf(),
    )
    var openError: MailError? = null
    private val callErrors = ArrayDeque<MailError?>()

    /** Thrown once by the next session call, then cleared. */
    var nextCallError: MailError?
        get() = callErrors.firstOrNull()
        set(value) {
            callErrors.clear()
            if (value != null) callErrors.addLast(value)
        }

    /** Queues one outcome per successive session call (any method); `null` lets that call through untouched. Lets a test skip a liveness probe before failing the call it actually cares about. */
    fun queueCallErrors(vararg errors: MailError?) {
        callErrors.clear()
        callErrors.addAll(errors)
    }
    var searchUnsupported = false
    var expungeOnMove = true

    /** When true, `move`/`deletePermanently` flag \Deleted (and, for `move`, COPY) before throwing a network error -- simulating the flag having landed server-side even though the call itself failed. */
    var failAfterFlag = false
    var opens = 0
        private set
    var openSessions = 0
        private set
    val passwords = mutableMapOf("B10000001" to "pw")

    fun deliver(
        subject: String,
        folder: String = "INBOX",
        from: MailAddress = MailAddress(null, "x@example.com"),
        seen: Boolean = false,
        messageId: String? = null,
        body: MailBody = MailBody(null, "body of $subject", emptyList(), emptyMap()),
    ): Long {
        val uid = nextUid++
        folders.getOrPut(folder) { mutableListOf() } += Stored(
            MailSummary(uid, from, emptyList(), emptyList(), emptyList(), subject, null, null,
                MailFlags.NONE.copy(seen = seen), 100, false, messageId, null, null),
            body,
        )
        return uid
    }

    fun subjects(folder: String) = folders.getValue(folder).map { it.summary.subject }

    fun factory() = MailSessionFactory { credentials ->
        openError?.let { throw it }
        if (passwords[credentials.loginName] != credentials.password) throw MailError.AuthFailed()
        opens++
        openSessions++
        FakeSession()
    }

    inner class FakeSession : MailSession {
        private var closed = false

        private fun <T> call(block: () -> T): T {
            check(!closed) { "session used after close" }
            if (callErrors.isNotEmpty()) callErrors.removeFirst()?.let { throw it }
            return block()
        }

        private fun list(folder: String) = folders[folder] ?: throw MailError.Protocol("no folder $folder")

        override fun noop() = call { Unit }

        override fun listFolders() = call { folders.keys.toList() }

        override fun status(folder: String) = call {
            val l = list(folder)
            FolderStatus(uidValidity, nextUid, l.size, l.count { !it.summary.flags.seen })
        }

        override fun fetchPage(folder: String, beforeSeq: Int?, pageSize: Int) = call {
            val l = list(folder).sortedBy { it.summary.uid }
            val end = (beforeSeq ?: (l.size + 1)) - 1
            if (end < 1) MailPage(uidValidity, l.size, emptyList(), null) else {
                val start = maxOf(1, end - pageSize + 1)
                MailPage(uidValidity, l.size, l.subList(start - 1, end).reversed().map { it.summary }
                    .filterNot { it.flags.deleted }, if (start > 1) start else null)
            }
        }

        override fun fetchSince(folder: String, fromUid: Long) =
            call { list(folder).map { it.summary }.filter { it.uid >= fromUid } }

        override fun fetchByUids(folder: String, uids: List<Long>) =
            call { list(folder).map { it.summary }.filter { it.uid in uids }.sortedByDescending { it.uid } }

        override fun refreshFlags(folder: String, uids: List<Long>) =
            call { list(folder).filter { it.summary.uid in uids }.associate { it.summary.uid to it.summary.flags } }

        override fun fetchBody(folder: String, uid: Long) = call { find(folder, uid).body }

        override fun messageSize(folder: String, uid: Long) = call { find(folder, uid).summary.sizeBytes }

        override fun writeRawSource(folder: String, uid: Long, out: OutputStream) =
            call { out.write("Subject: ${find(folder, uid).summary.subject}\r\n\r\nraw".toByteArray()) }

        override fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) =
            call { out.write("attachment $partId".toByteArray()) }

        override fun setSeen(folder: String, uids: List<Long>, seen: Boolean) = call {
            list(folder).filter { it.summary.uid in uids }.forEach { it.summary = it.summary.copy(flags = it.summary.flags.copy(seen = seen)) }
        }

        override fun setAnswered(folder: String, uid: Long) = call {
            val s = find(folder, uid)
            s.summary = s.summary.copy(flags = s.summary.flags.copy(answered = true))
        }

        override fun move(folder: String, uid: Long, target: String, ownedDeleted: Set<Long>) = call {
            val s = find(folder, uid)
            val copyUid = nextUid++
            list(target) += Stored(s.summary.copy(uid = copyUid), s.body)
            if (failAfterFlag) {
                s.summary = s.summary.copy(flags = s.summary.flags.copy(deleted = true))
                throw MailError.Network()
            }
            if (expungeOnMove) list(folder).remove(s) else s.summary = s.summary.copy(flags = s.summary.flags.copy(deleted = true))
            expungeOnMove
        }

        override fun deletePermanently(folder: String, uid: Long, ownedDeleted: Set<Long>) = call {
            if (failAfterFlag) {
                val s = find(folder, uid)
                s.summary = s.summary.copy(flags = s.summary.flags.copy(deleted = true))
                throw MailError.Network()
            }
            list(folder).remove(find(folder, uid))
        }

        override fun search(folder: String, query: String) = call {
            if (searchUnsupported) throw MailError.SearchUnsupported()
            list(folder).map { it.summary }.filter { query in it.subject }.map { it.uid }.sortedDescending()
        }

        override fun append(folder: String, rfc822: ByteArray, flags: Set<AppendFlag>) = call {
            val msg = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(rfc822))
            val from = AddressParser.parseList(msg.getHeader("From")?.firstOrNull()).firstOrNull()
            deliver(msg.subject.orEmpty(), folder, from ?: MailAddress(null, "unknown@x"),
                seen = AppendFlag.SEEN in flags, messageId = msg.messageID)
            Unit
        }

        override fun hasRecentMessageId(folder: String, messageId: String, window: Int) =
            call { list(folder).takeLast(window).any { it.summary.messageId == messageId } }

        override fun newestSenderName(folder: String, address: String, window: Int) = call {
            list(folder).takeLast(window).reversed().map { it.summary.from }
                .firstOrNull { it != null && it.address.equals(address, true) && !it.name.isNullOrBlank() }?.name
        }

        override fun close() {
            if (!closed) openSessions--
            closed = true
        }

        private fun find(folder: String, uid: Long) =
            list(folder).firstOrNull { it.summary.uid == uid } ?: throw MailError.Protocol("gone")
    }
}
