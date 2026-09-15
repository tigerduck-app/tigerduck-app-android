package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.AppendFlag
import org.ntust.app.tigerduck.mail.imap.MailFolders
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mime.MailCharsets
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.store.toModel
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SearchOutcome {
    val messages: List<MailSummary>
    data class Server(override val messages: List<MailSummary>) : SearchOutcome
    /** The server couldn't search; these are matches among already-loaded mail. */
    data class LoadedOnly(override val messages: List<MailSummary>) : SearchOutcome
}

interface SchoolMailRepository {
    suspend fun folders(): ResolvedFolders
    fun cachedPage(folder: String): MailPage?
    suspend fun loadPage(folder: String, beforeSeq: Int?): MailPage
    suspend fun inboxStatus(): FolderStatus
    suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags>
    suspend fun summary(folder: String, uid: Long): MailSummary?
    suspend fun body(folder: String, uid: Long): MailBody
    suspend fun setSeen(folder: String, uid: Long, seen: Boolean)
    suspend fun move(folder: String, uid: Long, target: String)
    suspend fun deletesPermanently(folder: String): Boolean
    suspend fun delete(folder: String, uid: Long)
    suspend fun search(folder: String, query: String): SearchOutcome
    suspend fun messageSize(folder: String, uid: Long): Long
    suspend fun rawSource(folder: String, uid: Long): String
    suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream)
    suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?)
    suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?)
    fun selfAddress(): MailAddress
    fun release()
}

@Singleton
class MailRepository @Inject constructor(
    private val account: MailAccount,
    sessions: MailSessionFactory,
    private val cache: MailCache,
    private val state: MailStateStore,
    private val sender: MailSender,
    private val builder: MessageBuilder,
    private val demo: MailDemoGate,
    @param:ApplicationScope scope: CoroutineScope,
) : SchoolMailRepository {
    private val holder = SessionHolder(sessions, scope)
    @Volatile private var resolved: ResolvedFolders? = null
    private val demoFolders = mutableMapOf<String, MutableList<DemoMail>>()

    private fun credentials() = account.credentialsOrNull() ?: throw MailError.AuthFailed()

    private suspend fun <T> withSession(block: (MailSession) -> T): T = withContext(Dispatchers.IO) {
        if (account.isDemo) throw MailError.DemoMode()
        holder.use(credentials(), block)
    }

    // --- demo -------------------------------------------------------------------

    private fun demoList(folder: String): MutableList<DemoMail> = synchronized(demoFolders) {
        if (demoFolders.isEmpty()) demoFolders["INBOX"] = demo.mailbox().messages.toMutableList()
        demoFolders.getOrPut(folder) { mutableListOf() }
    }

    private fun demoPage(folder: String) = demoList(folder).let { list ->
        MailPage(1, list.size, list.map { it.summary }, null)
    }

    private fun demoUpdate(folder: String, uid: Long, transform: (MailSummary) -> MailSummary) {
        val list = demoList(folder)
        val i = list.indexOfFirst { it.summary.uid == uid }
        if (i >= 0) list[i] = list[i].copy(summary = transform(list[i].summary))
    }

    // --- folders & lists -----------------------------------------------------------

    override suspend fun folders(): ResolvedFolders {
        if (account.isDemo) return DEMO_FOLDERS
        resolved?.let { return it }
        return withSession { MailFolders.resolve(it.listFolders()) }.also { resolved = it }
    }

    override fun cachedPage(folder: String): MailPage? {
        if (account.isDemo) return demoPage(folder)
        val dto = cache.loadFolder(folder) ?: return null
        return MailPage(dto.uidValidity, dto.totalMessages, dto.messages.orEmpty().map { it.toModel() }, dto.nextBeforeSeq.takeIf { it > 0 })
    }

    override suspend fun loadPage(folder: String, beforeSeq: Int?): MailPage {
        if (account.isDemo) return demoPage(folder)
        val page = withSession { it.fetchPage(folder, beforeSeq, PAGE_SIZE) }
        if (beforeSeq == null) cache.saveFolder(folder, page)
        return page
    }

    override suspend fun inboxStatus(): FolderStatus {
        if (account.isDemo) return FolderStatus(1, 2_000, demoList("INBOX").size, 0)
        val inbox = folders().nameOf(SpecialFolder.INBOX) ?: "INBOX"
        return withSession { it.status(inbox) }
    }

    override suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags> {
        if (account.isDemo) return demoList(folder).filter { it.summary.uid in uids }.associate { it.summary.uid to it.summary.flags }
        val flags = withSession { it.refreshFlags(folder, uids) }
        updateCached(folder) { s -> flags[s.uid]?.let { s.copy(flags = it) } ?: s }
        return flags
    }

    override suspend fun summary(folder: String, uid: Long): MailSummary? {
        cachedPage(folder)?.messages?.firstOrNull { it.uid == uid }?.let { return it }
        if (account.isDemo) return null
        return withSession { it.fetchByUids(folder, listOf(uid)).firstOrNull() }
    }

    override suspend fun body(folder: String, uid: Long): MailBody {
        if (account.isDemo) return demoList(folder).firstOrNull { it.summary.uid == uid }?.body ?: throw MailError.Protocol("gone")
        val validity = uidValidity(folder)
        cache.loadBody(folder, uid, validity)?.let { return it }
        return withSession { it.fetchBody(folder, uid) }.also { cache.saveBody(folder, uid, validity, it) }
    }

    // --- actions -----------------------------------------------------------------------

    override suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        if (account.isDemo) return demoUpdate(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
        withSession { it.setSeen(folder, listOf(uid), seen) }
        updateCached(folder) { s -> if (s.uid == uid) s.copy(flags = s.flags.copy(seen = seen)) else s }
    }

    override suspend fun move(folder: String, uid: Long, target: String) {
        if (account.isDemo) {
            val list = demoList(folder)
            list.firstOrNull { it.summary.uid == uid }?.let { list.remove(it); demoList(target).add(0, it) }
            return
        }
        val validity = uidValidity(folder)
        val owned = state.ownedDeleted(folder, validity)
        try {
            val expunged = withSession { it.move(folder, uid, target, owned) }
            state.setOwnedDeleted(folder, validity, if (expunged) emptySet() else owned + uid)
            removeCached(folder, uid)
        } catch (e: MailError) {
            recordIfDeletedOnServer(folder, uid, validity, owned)
            throw e
        }
    }

    override suspend fun deletesPermanently(folder: String): Boolean {
        val trash = folders().nameOf(SpecialFolder.TRASH)
        return trash == null || trash == folder
    }

    override suspend fun delete(folder: String, uid: Long) {
        val trash = folders().nameOf(SpecialFolder.TRASH)
        if (trash != null && trash != folder) return move(folder, uid, trash)
        if (account.isDemo) {
            demoList(folder).removeAll { it.summary.uid == uid }
            return
        }
        val validity = uidValidity(folder)
        val owned = state.ownedDeleted(folder, validity)
        try {
            val expunged = withSession { it.deletePermanently(folder, uid, owned) }
            state.setOwnedDeleted(folder, validity, if (expunged) emptySet() else owned + uid)
            removeCached(folder, uid)
        } catch (e: MailError) {
            recordIfDeletedOnServer(folder, uid, validity, owned)
            throw e
        }
    }

    override suspend fun search(folder: String, query: String): SearchOutcome {
        val q = query.trim()
        if (account.isDemo) return SearchOutcome.LoadedOnly(demoPage(folder).messages.filter { matches(it, q) })
        return try {
            val uids = withSession { it.search(folder, q) }.take(PAGE_SIZE)
            SearchOutcome.Server(withSession { it.fetchByUids(folder, uids) })
        } catch (e: MailError.SearchUnsupported) {
            SearchOutcome.LoadedOnly(cachedPage(folder)?.messages.orEmpty().filter { matches(it, q) })
        }
    }

    override suspend fun messageSize(folder: String, uid: Long): Long {
        if (account.isDemo) return demoList(folder).firstOrNull { it.summary.uid == uid }?.summary?.sizeBytes ?: 0
        return withSession { it.messageSize(folder, uid) }
    }

    override suspend fun rawSource(folder: String, uid: Long): String {
        if (account.isDemo) {
            val mail = demoList(folder).firstOrNull { it.summary.uid == uid } ?: throw MailError.Protocol("gone")
            return "From: ${mail.summary.from?.address}\r\nSubject: ${mail.summary.subject}\r\n\r\n${mail.body.plain.orEmpty()}"
        }
        val out = ByteArrayOutputStream()
        withSession { it.writeRawSource(folder, uid, out) }
        return MailCharsets.decode(out.toByteArray(), null)
    }

    override suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) {
        if (account.isDemo) {
            withContext(Dispatchers.IO) { out.write("TigerDuck demo attachment".toByteArray()) }
            return
        }
        withSession { it.writeAttachment(folder, uid, partId, out) }
    }

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?) {
        if (account.isDemo) return
        val sent = folders().nameOf(SpecialFolder.SENT)
        withContext(Dispatchers.IO) { sender.send(credentials(), mail, sent) }
        answered?.let { (folder, uid) ->
            runCatching { withSession { it.setAnswered(folder, uid) } }
            updateCached(folder) { s -> if (s.uid == uid) s.copy(flags = s.flags.copy(answered = true)) else s }
        }
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        if (account.isDemo) return
        val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: throw MailError.Protocol("no drafts folder")
        val bytes = withContext(Dispatchers.IO) { builder.build(mail).toBytes() }
        withSession { session ->
            session.append(drafts, bytes, setOf(AppendFlag.SEEN, AppendFlag.DRAFT))
            if (replacingUid != null) {
                val validity = session.status(drafts).uidValidity
                val owned = state.ownedDeleted(drafts, validity)
                val expunged = session.deletePermanently(drafts, replacingUid, owned)
                state.setOwnedDeleted(drafts, validity, if (expunged) emptySet() else owned + replacingUid)
            }
        }
    }

    override fun selfAddress(): MailAddress = MailAddress(state.displayName, credentials().address)

    override fun release() = holder.releaseLater()

    // --- helpers -------------------------------------------------------------------------

    private suspend fun uidValidity(folder: String): Long =
        cachedPage(folder)?.uidValidity ?: withSession { it.status(folder).uidValidity }

    /**
     * A COPY + STORE \Deleted may have partly landed on the server before
     * [MailSession.move]/[MailSession.deletePermanently] threw. Recording the
     * UID as owned-deleted is only safe once we know the flag actually took
     * (checked via a fresh [MailSession.refreshFlags]); if that can't be
     * determined either, nothing is recorded, and a UID whose COPY never
     * happened is never recorded, since it never got flagged.
     */
    private suspend fun recordIfDeletedOnServer(folder: String, uid: Long, validity: Long, owned: Set<Long>) {
        val flaggedDeleted = runCatching { withSession { it.refreshFlags(folder, listOf(uid)) } }
            .getOrNull()?.get(uid)?.deleted == true
        if (flaggedDeleted) state.setOwnedDeleted(folder, validity, owned + uid)
    }

    private fun updateCached(folder: String, transform: (MailSummary) -> MailSummary) {
        val page = cachedPage(folder) ?: return
        cache.saveFolder(folder, page.copy(messages = page.messages.map(transform)))
    }

    private fun removeCached(folder: String, uid: Long) {
        val page = cachedPage(folder) ?: return
        cache.saveFolder(folder, page.copy(messages = page.messages.filterNot { it.uid == uid }, totalMessages = (page.totalMessages - 1).coerceAtLeast(0)))
    }

    private fun matches(s: MailSummary, q: String) =
        q.isNotEmpty() && (s.subject.contains(q, true) || s.from?.address?.contains(q, true) == true || s.from?.name?.contains(q, true) == true)

    companion object {
        const val PAGE_SIZE = 50
        private val DEMO_FOLDERS = ResolvedFolders(
            SpecialFolder.entries.associateWith { it.decodedName }, emptyList(),
        )
    }
}
