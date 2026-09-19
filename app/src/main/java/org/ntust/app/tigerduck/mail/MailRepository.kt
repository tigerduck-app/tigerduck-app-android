package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
import org.ntust.app.tigerduck.mail.model.MailAttachment
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
import java.time.Instant
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

    /** The folder's last saved page, read from disk on the IO dispatcher. */
    suspend fun cachedPage(folder: String): MailPage?
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

    /** Cache-first like [body], so revisiting a mail's source never re-downloads it. */
    suspend fun rawSource(folder: String, uid: Long): String
    suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream)
    suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?)
    suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?)

    /** Permanently removes a draft after it was sent from the compose screen. */
    suspend fun discardDraft(uid: Long)
    fun selfAddress(): MailAddress
    fun release()

    /** The mail screen became active again: cancels a pending idle close so the connection isn't dropped mid-visit. No-op in demo mode. */
    fun acquire()
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
    private val site: MailSite,
    @ApplicationScope scope: CoroutineScope,
) : SchoolMailRepository {
    private val holder = SessionHolder(sessions, scope)
    @Volatile private var resolved: ResolvedFolders? = null
    private val demoFolders = mutableMapOf<String, MutableList<DemoMail>>()

    /** Next UID handed to a demo message composed in this session (draft save, or a sent copy) --
     *  guarded by [demoFolders]'s monitor alongside every other demo mutation. Well above the
     *  fixture's own `1_000 + index` range so the two never collide. */
    private var demoUidSeq = 9_000L

    init {
        // A signed-out account must never keep an authenticated socket open,
        // and a fresh sign-in must never see the previous account's cached
        // folder resolution or demo mail.
        //
        // drop(1): only a *transition* to signed out is a sign-out. A repository
        // that has just been built holds no session, no resolved folders and no
        // demo mail, so the current value has nothing to clear -- but this
        // collector starts on another dispatcher, so acting on it would let the
        // wipe land late, after a sign-in that happened in the meantime had
        // already put something there.
        scope.launch {
            account.signedIn.drop(1).collect { signedIn ->
                if (!signedIn) {
                    withContext(Dispatchers.IO) { holder.closeNow() }
                    resolved = null
                    synchronized(demoFolders) { demoFolders.clear(); demoUidSeq = 9_000L }
                }
            }
        }
    }

    private fun credentials() = account.credentialsOrNull() ?: throw MailError.Protocol("not signed in")

    /**
     * Spec §7.4: once the server has rejected the stored password, nothing may
     * log in again -- repeated failures can lock the school account and the
     * campus Wi-Fi that share it. Signing in again goes straight to
     * [MailSessionFactory.open] through [MailAccount.signIn], which clears the
     * flag on success, so this never blocks re-authentication.
     */
    private fun refuseWhileAuthFailed() {
        if (state.authFailed) throw MailError.AuthFailed()
    }

    private suspend fun <T> withSession(block: (MailSession) -> T): T = withContext(Dispatchers.IO) {
        if (account.isDemo) throw MailError.DemoMode()
        refuseWhileAuthFailed()
        holder.use(credentials(), block)
    }

    // --- demo -------------------------------------------------------------------

    /** Seeds every folder the fixture names (spec §7.6's reviewer mailbox) from [MailDemoGate],
     *  not just INBOX -- so Drafts and Sent show their demo content instead of starting empty. */
    private fun ensureDemoSeeded() {
        if (demoFolders.isNotEmpty()) return
        val messages = demo.mailbox().messages
        SpecialFolder.entries.forEach { special ->
            val name = DEMO_FOLDERS.nameOf(special) ?: return@forEach
            demoFolders[name] = messages.filter { it.folder == special }.toMutableList()
        }
    }

    /** A synchronized snapshot of [folder]'s demo mail; mutate only via [demoMutate] or the dedicated `synchronized` blocks below. */
    private fun demoList(folder: String): List<DemoMail> = synchronized(demoFolders) {
        ensureDemoSeeded()
        demoFolders.getOrPut(folder) { mutableListOf() }.toList()
    }

    private fun <T> demoMutate(folder: String, action: (MutableList<DemoMail>) -> T): T = synchronized(demoFolders) {
        ensureDemoSeeded()
        action(demoFolders.getOrPut(folder) { mutableListOf() })
    }

    private fun demoPage(folder: String) = demoList(folder).let { list ->
        MailPage(1, list.size, list.map { it.summary }, null)
    }

    private fun demoUpdate(folder: String, uid: Long, transform: (MailSummary) -> MailSummary) {
        demoMutate(folder) { list ->
            val i = list.indexOfFirst { it.summary.uid == uid }
            if (i >= 0) list[i] = list[i].copy(summary = transform(list[i].summary))
        }
    }

    /** Turns what the compose screen is about to save or send into a [DemoMail] -- entirely in
     *  memory, under a fresh [demoUidSeq] UID; never touches the network or the real cache. */
    private fun demoOutgoingMail(mail: OutgoingMail, draft: Boolean): DemoMail {
        val uid = synchronized(demoFolders) { demoUidSeq++ }
        val now = Instant.now()
        val attachments = mail.attachments.mapIndexed { i, a ->
            MailAttachment(partId = "${i + 2}", fileName = a.fileName, contentType = a.contentType, sizeBytes = a.sizeBytes, contentId = null)
        }
        val summary = MailSummary(
            uid = uid, from = mail.from, replyTo = emptyList(), to = mail.to, cc = mail.cc,
            subject = mail.subject, sentAt = now, receivedAt = now,
            flags = MailFlags.NONE.copy(seen = true, draft = draft),
            sizeBytes = mail.body.length.toLong(), hasAttachments = attachments.isNotEmpty(),
            messageId = "<demo-out-$uid@${MailServerConfig.DOMAIN}>", inReplyTo = mail.inReplyTo, references = mail.references,
        )
        val body = MailBody(html = null, plain = mail.body, attachments = attachments, inlineImages = emptyMap())
        return DemoMail(summary, body, if (draft) SpecialFolder.DRAFTS else SpecialFolder.SENT)
    }

    // --- folders & lists -----------------------------------------------------------

    override suspend fun folders(): ResolvedFolders {
        if (account.isDemo) return DEMO_FOLDERS
        resolved?.let { return it }
        return withSession { MailFolders.resolve(it.listFolders()) }.also { resolved = it }
    }

    override suspend fun cachedPage(folder: String): MailPage? = withContext(Dispatchers.IO) {
        if (account.isDemo) return@withContext demoPage(folder)
        val dto = cache.loadFolder(folder) ?: return@withContext null
        MailPage(dto.uidValidity, dto.totalMessages, dto.messages.orEmpty().map { it.toModel() }, dto.nextBeforeSeq.takeIf { it > 0 })
    }

    override suspend fun loadPage(folder: String, beforeSeq: Int?): MailPage {
        if (account.isDemo) return demoPage(folder)
        val page = withSession { it.fetchPage(folder, beforeSeq, PAGE_SIZE) }
        if (beforeSeq == null) withContext(Dispatchers.IO) { cache.saveFolder(folder, page) }
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
        // Cache-first, keyed by the cached page's own validity -- a hit never touches the server.
        val cachedValidity = cachedPage(folder)?.uidValidity
        if (cachedValidity != null) {
            withContext(Dispatchers.IO) { cache.loadBody(folder, uid, cachedValidity) }?.let { return it }
        }
        // A miss (or no cached page at all) means the body is genuinely
        // uncached: fetch it, reading the server's *current* UIDVALIDITY in
        // the same held connection so the save is keyed by that, never by
        // whatever the (possibly stale) cached page believed.
        val (validity, body) = withSession { session ->
            session.status(folder).uidValidity to session.fetchBody(folder, uid)
        }
        withContext(Dispatchers.IO) { cache.saveBody(folder, uid, validity, body) }
        return body
    }

    // --- actions -----------------------------------------------------------------------

    override suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        if (account.isDemo) return demoUpdate(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
        withSession { it.setSeen(folder, listOf(uid), seen) }
        updateCached(folder) { s -> if (s.uid == uid) s.copy(flags = s.flags.copy(seen = seen)) else s }
    }

    override suspend fun move(folder: String, uid: Long, target: String) {
        if (account.isDemo) {
            synchronized(demoFolders) {
                ensureDemoSeeded()
                val list = demoFolders.getOrPut(folder) { mutableListOf() }
                list.firstOrNull { it.summary.uid == uid }?.let {
                    list.remove(it)
                    demoFolders.getOrPut(target) { mutableListOf() }.add(0, it)
                }
            }
            return
        }
        runExpunging(folder, uid) { session, owned -> session.move(folder, uid, target, owned) }
        removeCached(folder, uid)
    }

    /**
     * Whether deleting in [folder] destroys the mail instead of moving it to trash.
     *
     * This is also where a missing trash folder gets created, because the answer decides
     * which confirmation the user is shown, so the CREATE has to have been tried before
     * that choice is made. If the server refuses, the answer is `true` and the user is
     * asked the permanent-delete question — a refused CREATE turns "move to trash" into a
     * hard delete the user confirms, never a silent one.
     */
    override suspend fun deletesPermanently(folder: String): Boolean = trashFor(folder) == null

    /**
     * Where a delete from [folder] moves the mail, or null when deleting there is
     * permanent: either [folder] *is* the trash, or the account has none and the server
     * would not create one.
     *
     * [deletesPermanently] normally runs first and leaves a created folder in [resolved],
     * so the [delete] that follows costs no second CREATE. When the first attempt was
     * refused this does try again, which can only turn a permanent delete into a move to
     * trash — the safer of the two, and never the other way round.
     */
    private suspend fun trashFor(folder: String): String? {
        if (folders().nameOf(SpecialFolder.TRASH) == folder) return null
        return ensureFolder(SpecialFolder.TRASH)
    }

    override suspend fun delete(folder: String, uid: Long) {
        trashFor(folder)?.let { return move(folder, uid, it) }
        if (account.isDemo) {
            demoMutate(folder) { it.removeAll { m -> m.summary.uid == uid } }
            return
        }
        runExpunging(folder, uid) { session, owned -> session.deletePermanently(folder, uid, owned) }
        removeCached(folder, uid)
    }

    override suspend fun search(folder: String, query: String): SearchOutcome {
        val q = query.trim()
        if (account.isDemo) return SearchOutcome.LoadedOnly(demoPage(folder).messages.filter { matches(it, q) })
        return try {
            val uids = withSession { it.search(folder, q) }.take(PAGE_SIZE)
            SearchOutcome.Server(withSession { it.fetchByUids(folder, uids) })
        } catch (e: MailError.SearchUnsupported) {
            val loaded = cachedPage(folder)?.messages.orEmpty()
            SearchOutcome.LoadedOnly(loaded.filter { matches(it, q) })
        }
    }

    /**
     * Cache-first exactly like [body], and saved into the same LRU budget: the source used to be
     * re-downloaded on every visit, which for a large mail meant paying for it again each time.
     * The save is keyed by the UIDVALIDITY read in the same held connection as the fetch, never
     * by whatever the (possibly stale) cached page believed.
     */
    override suspend fun rawSource(folder: String, uid: Long): String {
        if (account.isDemo) {
            val mail = demoList(folder).firstOrNull { it.summary.uid == uid } ?: throw MailError.Protocol("gone")
            return "From: ${mail.summary.from?.address}\r\nSubject: ${mail.summary.subject}\r\n\r\n${mail.body.plain.orEmpty()}"
        }
        val cachedValidity = cachedPage(folder)?.uidValidity
        if (cachedValidity != null) {
            withContext(Dispatchers.IO) { cache.loadSource(folder, uid, cachedValidity) }?.let { return it }
        }
        val out = ByteArrayOutputStream()
        val validity = withSession { session ->
            val uidValidity = session.status(folder).uidValidity
            session.writeRawSource(folder, uid, out)
            uidValidity
        }
        val source = MailCharsets.decode(out.toByteArray(), null)
        withContext(Dispatchers.IO) { cache.saveSource(folder, uid, validity, source) }
        return source
    }

    override suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) {
        if (account.isDemo) {
            withContext(Dispatchers.IO) { out.write("TigerDuck demo attachment".toByteArray()) }
            return
        }
        withSession { it.writeAttachment(folder, uid, partId, out) }
    }

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?) {
        if (account.isDemo) {
            folders().nameOf(SpecialFolder.SENT)?.let { sent ->
                demoMutate(sent) { it.add(0, demoOutgoingMail(mail, draft = false)) }
            }
            answered?.let { (folder, uid) -> demoUpdate(folder, uid) { s -> s.copy(flags = s.flags.copy(answered = true)) } }
            return
        }
        // The SMTP login uses the same rejected password, so §7.4 covers it too --
        // and `folders()` can answer from its cached resolution without a session.
        refuseWhileAuthFailed()
        // A null here -- no sent folder and none could be created -- still sends: filing the
        // copy is a convenience, and [MailSender] simply skips the APPEND. Sending is the point.
        val sent = ensureFolder(SpecialFolder.SENT)
        withContext(Dispatchers.IO) { sender.send(credentials(), mail, sent) }
        answered?.let { (folder, uid) ->
            val marked = try {
                withSession { it.setAnswered(folder, uid) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (marked) updateCached(folder) { s -> if (s.uid == uid) s.copy(flags = s.flags.copy(answered = true)) else s }
        }
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        if (account.isDemo) {
            val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
            val entry = demoOutgoingMail(mail, draft = true)
            // Like the real append-then-delete-old below, saving over a draft is a fresh UID, not
            // an in-place edit -- so this matches what re-fetching from the server would show.
            demoMutate(drafts) { list ->
                if (replacingUid != null) list.removeAll { it.summary.uid == replacingUid }
                list.add(0, entry)
            }
            return
        }
        // Unlike the sent copy, a draft that is not kept is the whole operation failing, so a
        // refused CREATE surfaces as the error the compose screen already reports.
        val drafts = ensureFolder(SpecialFolder.DRAFTS) ?: throw MailError.Protocol("no drafts folder")
        val bytes = withContext(Dispatchers.IO) { builder.build(mail).toBytes() }
        withSession { it.append(drafts, bytes, setOf(AppendFlag.SEEN, AppendFlag.DRAFT)) }
        if (replacingUid != null) {
            runExpunging(drafts, replacingUid) { session, owned -> session.deletePermanently(drafts, replacingUid, owned) }
        }
    }

    override suspend fun discardDraft(uid: Long) {
        if (account.isDemo) {
            val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
            demoMutate(drafts) { it.removeAll { m -> m.summary.uid == uid } }
            return
        }
        // Deliberately resolves without creating, unlike [saveDraft]: no drafts folder means
        // there is no draft to remove either, so a CREATE here would put a folder on the
        // student's real account for an operation that cannot do anything once it has one.
        val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
        runExpunging(drafts, uid) { session, owned -> session.deletePermanently(drafts, uid, owned) }
    }

    override fun selfAddress(): MailAddress = MailAddress(state.displayName, credentials().address)

    override fun release() = holder.releaseLater()

    override fun acquire() {
        if (!account.isDemo) holder.hold()
    }

    // --- helpers -------------------------------------------------------------------------

    /**
     * [role]'s folder on the server, created if the account has none.
     *
     * Called only from inside the operation that is about to write to the folder — never at
     * sign-in, never on a folder refresh, never from `MailFolders.resolve` — so a student who
     * only reads mail never sees folders appear in their account. Only the three roles in
     * [CREATED_ON_DEMAND] may be asked for: INBOX always exists by RFC, and the junk folder
     * belongs to the server's spam classifier, which would not know about one the app made.
     *
     * The name sent is [SpecialFolder.decodedName], the same form `LIST` hands back — see
     * [org.ntust.app.tigerduck.mail.imap.AngusMailSession.createFolder] for why the raw
     * modified-UTF-7 [SpecialFolder.imapName] would be wrong.
     *
     * Whatever the CREATE answers, the folder list is re-read and re-resolved, and *that*
     * decides. It covers both halves of the problem at once: a refusal because the folder
     * already exists — another client, or a racing operation of ours — is a success, and the
     * cached resolution predates the CREATE either way, so without the re-resolve the very
     * operation that just created the folder still could not see it.
     *
     * Returns null when the folder is missing and could not be created, so each caller keeps
     * the behaviour it had before folders were ever created: [send] files no copy, [saveDraft]
     * reports that the draft was not kept, and a delete falls back to the permanent-delete
     * path with its own confirmation. Failing to resolve the folder list at all still throws,
     * exactly as it did before.
     *
     * Nothing is ever created while the debug mail-server override is on. The names are
     * Mail2000's own -- `寄件備份匣`, `草稿匣`, `回收筒` -- and they match nothing on any other
     * server, so the first send or delete against a test mailbox would leave three
     * Chinese-named folders behind in it. That is precisely the "touching my own mailbox"
     * the override exists to avoid, and it is the one side effect of using it that outlives
     * turning it off again. The fallbacks above are what the caller then gets, unchanged;
     * a developer who wants to exercise those paths creates the folders on the test server
     * deliberately, once. The real school path is untouched by this.
     */
    private suspend fun ensureFolder(role: SpecialFolder): String? {
        require(role in CREATED_ON_DEMAND) { "$role is never created on demand" }
        folders().nameOf(role)?.let { return it }
        if (account.isDemo || site.activeOverride() != null) return null
        return try {
            withSession { session ->
                runCatching { session.createFolder(role.decodedName) }
                MailFolders.resolve(session.listFolders())
            }.also { resolved = it }.nameOf(role)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Runs a COPY+STORE\Deleted (or plain STORE\Deleted) followed by a
     * conditional EXPUNGE, sharing the bookkeeping [move], [delete] and
     * [saveDraft]'s replacement step all need:
     *
     * - Reads the folder's live UIDVALIDITY inside the same held connection
     *   before [op] runs. If it doesn't match what the cache believes, the
     *   cache is dropped and [MailError.FolderChanged] is thrown before
     *   anything is touched -- a stale cached UID must never be acted on, or
     *   keyed into the owned-\Deleted set, under a numbering the server has
     *   since replaced.
     * - If [op] throws after possibly already landing its COPY + flag on the
     *   server, the UID is added to the owned-\Deleted set only when a fresh
     *   [MailSession.refreshFlags] confirms it is actually flagged \Deleted
     *   there, and only when it wasn't already flagged before this call (so
     *   another client's earlier \Deleted flag is never attributed to us),
     *   and never for [MailError.AuthFailed]/[MailError.Certificate] (no
     *   second login attempt just to check).
     */
    private suspend fun runExpunging(folder: String, uid: Long, op: (MailSession, Set<Long>) -> Boolean) {
        val cached = cachedPage(folder)
        val cachedValidity = cached?.uidValidity
        val alreadyDeleted = cached?.messages?.firstOrNull { it.uid == uid }?.flags?.deleted == true
        var validity = 0L
        var owned: Set<Long> = emptySet()
        var attempted = false
        try {
            val expunged = withSession { session ->
                val serverValidity = session.status(folder).uidValidity
                validity = serverValidity
                if (cachedValidity != null && cachedValidity != serverValidity) {
                    throw MailError.FolderChanged()
                }
                owned = state.ownedDeleted(folder, serverValidity)
                attempted = true
                op(session, owned)
            }
            state.setOwnedDeleted(folder, validity, if (expunged) emptySet() else owned + uid)
        } catch (e: MailError) {
            if (!attempted && e is MailError.FolderChanged) {
                withContext(Dispatchers.IO) { cache.deleteFolder(folder) }
            } else if (attempted && !alreadyDeleted && e !is MailError.AuthFailed && e !is MailError.Certificate) {
                recordIfDeletedOnServer(folder, uid, validity, owned)
            }
            throw e
        }
    }

    private suspend fun recordIfDeletedOnServer(folder: String, uid: Long, validity: Long, owned: Set<Long>) {
        val flaggedDeleted = try {
            withSession { it.refreshFlags(folder, listOf(uid)) }[uid]?.deleted == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (flaggedDeleted) state.setOwnedDeleted(folder, validity, owned + uid)
    }

    private suspend fun updateCached(folder: String, transform: (MailSummary) -> MailSummary) = withContext(Dispatchers.IO) {
        val page = cachedPage(folder) ?: return@withContext
        cache.saveFolder(folder, page.copy(messages = page.messages.map(transform)))
    }

    private suspend fun removeCached(folder: String, uid: Long) = withContext(Dispatchers.IO) {
        val page = cachedPage(folder) ?: return@withContext
        cache.saveFolder(folder, page.copy(messages = page.messages.filterNot { it.uid == uid }, totalMessages = (page.totalMessages - 1).coerceAtLeast(0)))
    }

    private fun matches(s: MailSummary, q: String) =
        q.isNotEmpty() && (s.subject.contains(q, true) || s.from?.address?.contains(q, true) == true || s.from?.name?.contains(q, true) == true)

    companion object {
        const val PAGE_SIZE = 50

        /** The only roles [ensureFolder] may create; see its doc for why the other two are out. */
        private val CREATED_ON_DEMAND = setOf(SpecialFolder.SENT, SpecialFolder.DRAFTS, SpecialFolder.TRASH)

        private val DEMO_FOLDERS = ResolvedFolders(
            SpecialFolder.entries.associateWith { it.decodedName }, emptyList(),
        )
    }
}
