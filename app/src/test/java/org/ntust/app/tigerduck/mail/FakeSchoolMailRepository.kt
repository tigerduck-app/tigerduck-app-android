package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.io.OutputStream

/** Synchronous, in-memory [SchoolMailRepository] for ViewModel tests. `beforeSeq` is an offset token here. */
class FakeSchoolMailRepository : SchoolMailRepository {
    var resolved = ResolvedFolders(SpecialFolder.entries.associateWith { it.decodedName }, listOf("Moodle 課程討論區"))
    val mail = mutableMapOf<String, MutableList<MailSummary>>()
    var pageSize = 50
    var status = FolderStatus(1, 1, 0, 0)
    var loadError: MailError? = null
    /** Thrown once by the next [loadPage] call, then cleared -- for testing a single retry after recovery. */
    var loadErrorOnce: MailError? = null
    var searchUnsupported = false
    val bodies = mutableMapOf<Long, MailBody>()
    var bodyError: MailError? = null
    val seenCalls = mutableListOf<Pair<Long, Boolean>>()
    val moved = mutableListOf<Triple<String, Long, String>>()
    val deleted = mutableListOf<Pair<String, Long>>()
    val sent = mutableListOf<Pair<OutgoingMail, Pair<String, Long>?>>()
    val drafts = mutableListOf<Pair<OutgoingMail, Long?>>()
    var sendError: MailError? = null
    var self = MailAddress("測試", "b10000001@mail.ntust.edu.tw")
    var raw = "Subject: x\r\n\r\nraw"
    var size = 1_000L
    var released = 0
    var acquired = 0

    fun add(folder: String, vararg summaries: MailSummary) {
        mail.getOrPut(folder) { mutableListOf() } += summaries
    }

    private fun sorted(folder: String) = mail.getOrPut(folder) { mutableListOf() }.sortedByDescending { it.uid }

    private fun update(folder: String, uid: Long, transform: (MailSummary) -> MailSummary) {
        val list = mail[folder] ?: return
        val i = list.indexOfFirst { it.uid == uid }
        if (i >= 0) list[i] = transform(list[i])
    }

    override suspend fun folders() = resolved
    override fun cachedPage(folder: String): MailPage? = null

    override suspend fun loadPage(folder: String, beforeSeq: Int?): MailPage {
        loadError?.let { throw it }
        loadErrorOnce?.let { loadErrorOnce = null; throw it }
        val all = sorted(folder)
        val from = beforeSeq ?: 0
        val chunk = all.drop(from).take(pageSize)
        return MailPage(1, all.size, chunk, (from + chunk.size).takeIf { it < all.size })
    }

    override suspend fun inboxStatus() = status
    override suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags> =
        sorted(folder).filter { it.uid in uids }.associate { it.uid to it.flags }
    override suspend fun summary(folder: String, uid: Long) = sorted(folder).firstOrNull { it.uid == uid }
    override suspend fun body(folder: String, uid: Long): MailBody {
        bodyError?.let { throw it }
        return bodies[uid] ?: throw MailError.Protocol("gone")
    }

    override suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        seenCalls += uid to seen
        update(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
    }

    override suspend fun move(folder: String, uid: Long, target: String) {
        moved += Triple(folder, uid, target)
        mail[folder]?.removeAll { it.uid == uid }
    }

    override suspend fun deletesPermanently(folder: String) = folder == resolved.nameOf(SpecialFolder.TRASH)

    override suspend fun delete(folder: String, uid: Long) {
        deleted += folder to uid
        mail[folder]?.removeAll { it.uid == uid }
    }

    override suspend fun search(folder: String, query: String): SearchOutcome {
        val hits = sorted(folder).filter { query in it.subject }
        return if (searchUnsupported) SearchOutcome.LoadedOnly(hits) else SearchOutcome.Server(hits)
    }

    override suspend fun messageSize(folder: String, uid: Long) = size
    override suspend fun rawSource(folder: String, uid: Long) = raw
    override suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) {
        out.write("attachment $partId".toByteArray())
    }

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?) {
        sendError?.let { throw it }
        sent += mail to answered
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        drafts += mail to replacingUid
    }

    override fun selfAddress() = self
    override fun release() {
        released++
    }

    override fun acquire() {
        acquired++
    }
}
