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

    /** Thrown by [inboxStatus] -- the first call the page poll makes. */
    var statusError: MailError? = null
    var statusCalls = 0
    var loadError: MailError? = null
    var searchUnsupported = false
    val bodies = mutableMapOf<Long, MailBody>()
    var bodyError: MailError? = null
    var moveError: MailError? = null
    var deleteError: MailError? = null
    val seenCalls = mutableListOf<Triple<String, Long, Boolean>>()
    val moved = mutableListOf<Triple<String, Long, String>>()
    val deleted = mutableListOf<Pair<String, Long>>()

    /**
     * Every folder name handed to any folder-taking call, in order. A merged view has no folder
     * of its own, so nothing here may ever be a name the server would not recognise -- this is
     * what a test asserts against to prove no synthetic name reaches an IMAP command.
     */
    val foldersTouched = mutableListOf<String>()
    /** Mirrors the real repository's cache: [loadPage]'s first page populates it, [dropCache] simulates a [MailError.FolderChanged] eviction. */
    private val cachedPages = mutableMapOf<String, MailPage>()
    val sent = mutableListOf<Pair<OutgoingMail, Pair<String, Long>?>>()
    val drafts = mutableListOf<Pair<OutgoingMail, Long?>>()
    val discardedDrafts = mutableListOf<Long>()
    val sentAttachments = mutableListOf<List<Pair<String, String>>>()
    var sendError: MailError? = null
    /** Any [Throwable], not just [MailError] -- lets a test inject a [kotlinx.coroutines.CancellationException] too. */
    var discardDraftError: Throwable? = null
    var self = MailAddress("測試", "b10000001@mail.ntust.edu.tw")
    var raw = "Subject: x\r\n\r\nraw"
    var writeAttachmentError: MailError? = null
    /** Fires synchronously at the start of every [writeAttachment] call, before any error/write -- for tests that need to observe state mid-download. */
    var onWriteAttachment: (() -> Unit)? = null
    /** Fires synchronously at the start of every [body] call, before any error/return -- for tests that need to observe state while the body is still loading. */
    var onBody: (() -> Unit)? = null
    var released = 0
    var acquired = 0

    fun add(folder: String, vararg summaries: MailSummary) {
        mail.getOrPut(folder) { mutableListOf() } += summaries
    }

    private fun sorted(folder: String) = mail.getOrPut(folder) { mutableListOf() }.sortedByDescending { it.uid }

    private fun <T> touching(folder: String, block: () -> T): T {
        foldersTouched += folder
        return block()
    }

    private fun update(folder: String, uid: Long, transform: (MailSummary) -> MailSummary) {
        val list = mail[folder] ?: return
        val i = list.indexOfFirst { it.uid == uid }
        if (i >= 0) list[i] = transform(list[i])
    }

    override suspend fun folders() = resolved
    override suspend fun cachedPage(folder: String): MailPage? = touching(folder) { cachedPages[folder] }

    /** Simulates the repository dropping a folder's cache once [MailError.FolderChanged] fires for it. */
    fun dropCache(folder: String) {
        cachedPages.remove(folder)
    }

    override suspend fun loadPage(folder: String, beforeSeq: Int?): MailPage {
        foldersTouched += folder
        loadError?.let { throw it }
        val all = sorted(folder)
        val from = beforeSeq ?: 0
        val chunk = all.drop(from).take(pageSize)
        val page = MailPage(1, all.size, chunk, (from + chunk.size).takeIf { it < all.size })
        if (beforeSeq == null) cachedPages[folder] = page
        return page
    }

    override suspend fun inboxStatus(): FolderStatus {
        statusCalls++
        statusError?.let { throw it }
        return status
    }
    override suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags> =
        touching(folder) { sorted(folder).filter { it.uid in uids }.associate { it.uid to it.flags } }
    override suspend fun summary(folder: String, uid: Long) = touching(folder) { sorted(folder).firstOrNull { it.uid == uid } }
    override suspend fun body(folder: String, uid: Long): MailBody {
        foldersTouched += folder
        onBody?.invoke()
        bodyError?.let { throw it }
        return bodies[uid] ?: throw MailError.Protocol("gone")
    }

    override suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        foldersTouched += folder
        seenCalls += Triple(folder, uid, seen)
        update(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
    }

    override suspend fun move(folder: String, uid: Long, target: String) {
        foldersTouched += folder
        foldersTouched += target
        moveError?.let { throw it }
        moved += Triple(folder, uid, target)
        mail[folder]?.removeAll { it.uid == uid }
    }

    override suspend fun deletesPermanently(folder: String) =
        touching(folder) { folder == resolved.nameOf(SpecialFolder.TRASH) }

    override suspend fun delete(folder: String, uid: Long) {
        foldersTouched += folder
        deleteError?.let { throw it }
        deleted += folder to uid
        mail[folder]?.removeAll { it.uid == uid }
    }

    override suspend fun search(folder: String, query: String): SearchOutcome {
        foldersTouched += folder
        val hits = sorted(folder).filter { query in it.subject }
        return if (searchUnsupported) SearchOutcome.LoadedOnly(hits) else SearchOutcome.Server(hits)
    }

    var rawSourceError: MailError? = null
    var rawSourceCalls = 0

    override suspend fun rawSource(folder: String, uid: Long): String {
        foldersTouched += folder
        rawSourceCalls++
        rawSourceError?.let { throw it }
        return raw
    }

    override suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) {
        foldersTouched += folder
        onWriteAttachment?.invoke()
        writeAttachmentError?.let { throw it }
        out.write("attachment $partId".toByteArray())
    }

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?) {
        sendError?.let { throw it }
        sentAttachments += mail.attachments.map { it.fileName to it.open().use { s -> s.readBytes().decodeToString() } }
        sent += mail to answered
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        drafts += mail to replacingUid
    }

    override suspend fun discardDraft(uid: Long) {
        discardDraftError?.let { throw it }
        discardedDrafts += uid
    }

    override fun selfAddress() = self
    override fun release() {
        released++
    }

    override fun acquire() {
        acquired++
    }
}
