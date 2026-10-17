package org.ntust.app.tigerduck.mail

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import org.ntust.app.tigerduck.mail.smtp.SentCopy
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

    /**
     * The newest page of the server's matches, and [remaining]: the UIDs of every match not
     * fetched yet, newest first, for [SchoolMailRepository.searchPage] to fetch as the list
     * scrolls.
     */
    data class Server(
        override val messages: List<MailSummary>,
        val remaining: List<Long> = emptyList(),
        /** The folder's UIDVALIDITY the matches were found under, which [remaining] only means anything in; null where unknown. */
        val uidValidity: Long? = null,
    ) : SearchOutcome
    /**
     * The server couldn't search; these are matches among the folder's cached newest page, with
     * [uidValidity] the page was read under (null where unknown). The list page substitutes the
     * rows it has loaded, pages back included, where they were read under the same UIDVALIDITY.
     */
    data class LoadedOnly(override val messages: List<MailSummary>, val uidValidity: Long? = null) : SearchOutcome
}

/**
 * A page of the matches a search left to fetch ([SearchOutcome.Server.remaining]), with the
 * folder's UIDVALIDITY it was read under: once that differs from the search's own, the folder was
 * recreated and the UIDs name other mail. Null where unknown.
 */
data class SearchPage(val uidValidity: Long?, val messages: List<MailSummary>)

/**
 * What a write through the repository did to a folder, for a screen that keeps a list of that
 * folder while the write is made on another: the mail list stays as it was on the way back from
 * a mail rather than fetching its page again, so this is how a mail read, moved or deleted there
 * reaches it (see [SchoolMailRepository.changes]).
 */
sealed interface MailChange {
    val folder: String

    /**
     * The [MailAccount.accountGeneration] the write was made under, read before it went out: a
     * write can end after another student signed in over the one who made it, and its folder and
     * UID then name a mail of theirs. A list takes only the changes of the account it loaded for.
     */
    val generation: Int

    /** The mail's read flag is now [seen]. */
    data class Seen(override val folder: String, val uid: Long, val seen: Boolean, override val generation: Int) : MailChange

    /** The mail left [folder]: moved elsewhere, deleted, or a draft discarded. */
    data class Removed(override val folder: String, val uid: Long, override val generation: Int) : MailChange

    /**
     * [folder] holds mail the repository cannot describe without a fetch -- one moved in, a sent
     * copy filed, a draft saved -- or was found recreated on the server and its cache dropped.
     * A list of it has to show the server's page again.
     */
    data class Changed(override val folder: String, override val generation: Int) : MailChange
}

/**
 * How a search reads mail that is already loaded, where the server's own SEARCH is not available:
 * the subject and the sender's name and address, case-insensitively.
 */
internal fun matchesLoadedMail(s: MailSummary, q: String) =
    q.isNotEmpty() && (s.subject.contains(q, true) || s.from?.address?.contains(q, true) == true || s.from?.name?.contains(q, true) == true)

interface SchoolMailRepository {
    /** The server's folders, resolved once per sign-in; [stillWanted] as on [loadPage], for the LIST a first call sends. */
    suspend fun folders(stillWanted: () -> Boolean = { true }): ResolvedFolders

    /** The folder's last saved page, read from disk on the IO dispatcher. */
    suspend fun cachedPage(folder: String): MailPage?

    /**
     * Forgets [folder]'s cached page while it is still the one of the mailbox generation
     * [obsoleteValidity]: the folder was found recreated on the server -- an older page, or a page
     * of search results, came back under another UIDVALIDITY than the list's -- so that page names
     * mail that no longer exists and must not be painted again before a first page replaces it. A
     * page already cached under the new generation, by a refresh that then failed on another
     * folder, is left alone: it is the only offline copy of the new mailbox. Nothing to forget for
     * the demo mailbox.
     */
    suspend fun dropCachedPage(folder: String, obsoleteValidity: Long)
    /**
     * @param background true for a warm nobody asked for. Such a call never shortens a cached
     *   page it did not improve on -- see [MailRepository.loadPage].
     * @param stillWanted asked with the session held, right before the command goes out, and
     *   again nowhere else: a request can wait behind another on the one connection, and the
     *   account or the view that asked may be gone by the time it is its turn. Answering false
     *   ends the call with [MailError.Abandoned] and nothing sent -- so a request of a batch that
     *   outlived its account never runs under the next one's credentials, and an answer that
     *   comes back was asked for by whoever is signed in when it was sent.
     */
    suspend fun loadPage(
        folder: String,
        beforeSeq: Int?,
        limit: Int = MailRepository.PAGE_SIZE,
        background: Boolean = false,
        stillWanted: () -> Boolean = { true },
    ): MailPage

    /** The inbox's STATUS; [stillWanted] as on [loadPage]. */
    suspend fun inboxStatus(stillWanted: () -> Boolean = { true }): FolderStatus

    /**
     * [folder]'s STATUS: the cheapest way to ask the server whether a folder is as the page last
     * saw it. [stillWanted] as on [loadPage].
     */
    suspend fun status(folder: String, stillWanted: () -> Boolean = { true }): FolderStatus
    suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags>
    suspend fun summary(folder: String, uid: Long): MailSummary?
    suspend fun body(folder: String, uid: Long): MailBody
    suspend fun setSeen(folder: String, uid: Long, seen: Boolean)
    suspend fun move(folder: String, uid: Long, target: String)
    suspend fun deletesPermanently(folder: String): Boolean
    suspend fun delete(folder: String, uid: Long)

    /** [stillWanted] as on [loadPage]. */
    suspend fun search(folder: String, query: String, stillWanted: () -> Boolean = { true }): SearchOutcome

    /**
     * The summaries of [uids], matches [search] left in [SearchOutcome.Server.remaining]; mail gone
     * since is left out. [stillWanted] as on [loadPage].
     */
    suspend fun searchPage(folder: String, uids: List<Long>, stillWanted: () -> Boolean = { true }): SearchPage

    /** Cache-first like [body], so revisiting a mail's source never re-downloads it. */
    suspend fun rawSource(folder: String, uid: Long): String
    suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream)
    /**
     * Sends [mail]. Throws only when the mail did not go out; what became of the student's own
     * copy of it comes back as [SentCopy] instead, because by then the mail is already delivered
     * and there is nothing to fail.
     */
    suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?): SentCopy
    suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?)

    /** Permanently removes a draft after it was sent from the compose screen. */
    suspend fun discardDraft(uid: Long)
    fun selfAddress(): MailAddress
    fun release()

    /** The mail screen became active again: cancels a pending idle close so the connection isn't dropped mid-visit. No-op in demo mode. */
    fun acquire()

    /**
     * Every write made through the repository, as it lands: what the message and compose screens
     * do to a mail reaches the list that stays behind them, the way the iPhone's list is told
     * (`markSeenLocally` / `removeLocally` there). Only for a write that succeeded, and only to
     * whoever is collecting at the time -- nothing is replayed to a list that loads afresh anyway.
     */
    val changes: SharedFlow<MailChange>
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
    @ApplicationScope scope: CoroutineScope,
) : SchoolMailRepository {
    @VisibleForTesting
    internal val holder = SessionHolder(sessions, scope)
    /** The server's folders as resolved for one account: [generation] is the [MailAccount.accountGeneration] that asked, and the list is nobody else's. */
    private class Resolved(val generation: Int, val folders: ResolvedFolders)

    @Volatile private var resolved: Resolved? = null
    private val demoFolders = mutableMapOf<String, MutableList<DemoMail>>()

    // Buffered so a write never waits on the list applying what it did. The list applies each
    // change in one step on the main thread, so the buffer stays all but empty and nothing is
    // ever dropped to make room.
    private val _changes = MutableSharedFlow<MailChange>(extraBufferCapacity = 64)
    override val changes: SharedFlow<MailChange> = _changes.asSharedFlow()

    /** Next UID handed to a demo message composed in this session (draft save, or a sent copy) --
     *  guarded by [demoFolders]'s monitor alongside every other demo mutation. Well above the
     *  fixture's own `1_000 + index` range so the two never collide. */
    private var demoUidSeq = 9_000L

    init {
        // A signed-out account must never keep an authenticated socket open,
        // and a fresh sign-in must never see the previous account's cached
        // folder resolution or demo mail.
        //
        // Sign-out events, not signedIn going false. Watching the StateFlow
        // lost sign-outs two ways: a collector that first ran after one took
        // `false` as its initial value, and one that last saw `false` read a
        // sign-in and sign-out landing before it next ran as no change. Either
        // way the held socket stayed authenticated. UNDISPATCHED subscribes
        // before this constructor returns, so no sign-out can precede the
        // subscriber, and a fresh repository has nothing of its own to clear.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            account.signOuts.collect {
                withContext(Dispatchers.IO) { holder.closeNow() }
                resolved = null
                synchronized(demoFolders) { demoFolders.clear(); demoUidSeq = 9_000L }
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

    /**
     * [stillWanted] is asked with the session held, right before [block] sends anything (see
     * [SchoolMailRepository.loadPage]). The credentials were read just before the wait for the
     * session; the answer being yes says no account came or went since the caller read what it
     * asks about, so they are that account's too.
     */
    private suspend fun <T> withSession(stillWanted: () -> Boolean = { true }, block: (MailSession) -> T): T = withContext(Dispatchers.IO) {
        if (account.isDemo) throw MailError.DemoMode()
        refuseWhileAuthFailed()
        holder.use(credentials(), stillWanted, block)
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

    override suspend fun folders(stillWanted: () -> Boolean): ResolvedFolders {
        if (account.isDemo) return DEMO_FOLDERS
        val generation = account.accountGeneration
        resolved?.takeIf { it.generation == generation }?.let { return it.folders }
        val folders = withSession(stillWanted) { MailFolders.resolve(it.listFolders()) }
        // Kept for the account that asked alone. Another student can sign in over this one with
        // no sign-out for the collector above to see; a LIST already out then comes back under
        // their generation, and must not be what their own first request finds resolved.
        if (account.accountGeneration == generation) resolved = Resolved(generation, folders)
        return folders
    }

    override suspend fun cachedPage(folder: String): MailPage? = withContext(Dispatchers.IO) {
        if (account.isDemo) return@withContext demoPage(folder)
        val dto = cache.loadFolder(folder) ?: return@withContext null
        MailPage(dto.uidValidity, dto.totalMessages, dto.messages.orEmpty().map { it.toModel() }, dto.nextBeforeSeq.takeIf { it > 0 })
    }

    /** A disk delete under the cache's own lock, as [runExpunging] drops the page it found stale. */
    override suspend fun dropCachedPage(folder: String, obsoleteValidity: Long) {
        if (account.isDemo) return
        withContext(Dispatchers.IO) { cache.deleteFolderUnder(folder, obsoleteValidity) }
    }

    /**
     * A first page ([beforeSeq] null) replaces the folder's cache, because it is the newest truth
     * about that mailbox and the old page may name mail that is gone.
     *
     * Except for a [background] warm, which fetches fewer rows than a foreground load does and so
     * must never be the thing that shortens an offline mailbox: cache the user has already paged
     * to fifty rows, warm it with twenty, and a restart or a failed first fetch would leave them
     * with the twenty. A warm still seeds an empty cache -- the case it exists for -- and still
     * replaces one from an older mailbox generation, where the cached page is worthless anyway.
     */
    override suspend fun loadPage(folder: String, beforeSeq: Int?, limit: Int, background: Boolean, stillWanted: () -> Boolean): MailPage {
        if (account.isDemo) return demoPage(folder)
        // The account the page is fetched for. A fetch is blocking I/O that a sign-out cannot
        // cancel, so a page that lands after one is kept out of the cache the sign-out emptied,
        // or whoever signs in next would open on it; asked under the cache's lock, which that
        // clear takes too.
        val generation = account.accountGeneration
        val page = withSession(stillWanted) { it.fetchPage(folder, beforeSeq, limit) }
        if (beforeSeq == null && !(background && wouldShortenCache(folder, page))) {
            withContext(Dispatchers.IO) { cache.saveFolderIf(folder, page) { account.accountGeneration == generation } }
        }
        return page
    }

    /** True when [page] holds less of the same mailbox than the cache already does. */
    private suspend fun wouldShortenCache(folder: String, page: MailPage): Boolean {
        val cached = cachedPage(folder) ?: return false
        return cached.uidValidity == page.uidValidity && cached.messages.size > page.messages.size
    }

    override suspend fun inboxStatus(stillWanted: () -> Boolean): FolderStatus {
        if (account.isDemo) return FolderStatus(1, 2_000, demoList("INBOX").size, 0)
        // The folder list is a request of this call's too, when nothing has resolved it yet.
        val inbox = folders(stillWanted).nameOf(SpecialFolder.INBOX) ?: "INBOX"
        return withSession(stillWanted) { it.status(inbox) }
    }

    override suspend fun status(folder: String, stillWanted: () -> Boolean): FolderStatus {
        if (account.isDemo) return FolderStatus(1, 2_000, demoList(folder).size, 0)
        return withSession(stillWanted) { it.status(folder) }
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
        // Read before the write goes out, as every write here reads it: see [MailChange.generation].
        val generation = account.accountGeneration
        if (account.isDemo) {
            demoUpdate(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
        } else {
            withSession { it.setSeen(folder, listOf(uid), seen) }
            updateCached(folder) { s -> if (s.uid == uid) s.copy(flags = s.flags.copy(seen = seen)) else s }
        }
        _changes.emit(MailChange.Seen(folder, uid, seen, generation))
    }

    override suspend fun move(folder: String, uid: Long, target: String) {
        val generation = account.accountGeneration
        if (account.isDemo) {
            synchronized(demoFolders) {
                ensureDemoSeeded()
                val list = demoFolders.getOrPut(folder) { mutableListOf() }
                list.firstOrNull { it.summary.uid == uid }?.let {
                    list.remove(it)
                    demoFolders.getOrPut(target) { mutableListOf() }.add(0, it)
                }
            }
        } else {
            runExpunging(folder, uid) { session, owned -> session.move(folder, uid, target, owned) }
            removeCached(folder, uid)
        }
        _changes.emit(MailChange.Removed(folder, uid, generation))
        // The mail's UID in [target] is the server's to give (COPYUID is not read), so a list of
        // the target can only fetch it.
        _changes.emit(MailChange.Changed(target, generation))
    }

    /**
     * Whether deleting in [folder] destroys the mail instead of moving it to trash.
     *
     * This is also where a missing trash folder gets created, because the answer decides
     * which confirmation the user is shown, so the CREATE has to have been tried before
     * that choice is made. If the server refuses, the answer is `true` and the user is
     * asked the permanent-delete question — a refused CREATE turns "move to trash" into a
     * hard delete the user confirms, never a silent one.
     *
     * Only an answer from the server may decide that. A failure that never reached one —
     * the connection dropped, the server was busy — throws out of here instead, so the
     * caller reports an error rather than putting the destructive question in front of a
     * user whose mail could have gone to trash perfectly well a second later.
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
        val generation = account.accountGeneration
        trashFor(folder)?.let { return move(folder, uid, it) }
        if (account.isDemo) {
            demoMutate(folder) { it.removeAll { m -> m.summary.uid == uid } }
        } else {
            runExpunging(folder, uid) { session, owned -> session.deletePermanently(folder, uid, owned) }
            removeCached(folder, uid)
        }
        _changes.emit(MailChange.Removed(folder, uid, generation))
    }

    override suspend fun search(folder: String, query: String, stillWanted: () -> Boolean): SearchOutcome {
        val q = query.trim()
        if (account.isDemo) return demoPage(folder).let { page -> SearchOutcome.LoadedOnly(page.messages.filter { matchesLoadedMail(it, q) }, page.uidValidity) }
        return try {
            // Newest first, so the first page is the newest matches and the rest page back. One
            // hold of the connection, so the page and the UIDVALIDITY are those the search saw.
            withSession(stillWanted) { session ->
                val matches = session.search(folder, q)
                val validity = session.uidValidity(folder)
                val newest = matches.take(PAGE_SIZE)
                SearchOutcome.Server(session.fetchByUids(folder, newest), matches.drop(newest.size), validity)
            }
        } catch (e: MailError.SearchUnsupported) {
            val cached = cachedPage(folder)
            SearchOutcome.LoadedOnly(cached?.messages.orEmpty().filter { matchesLoadedMail(it, q) }, cached?.uidValidity)
        }
    }

    override suspend fun searchPage(folder: String, uids: List<Long>, stillWanted: () -> Boolean): SearchPage {
        // The demo search answers from the loaded mail, which leaves nothing to fetch later.
        if (account.isDemo) return SearchPage(null, emptyList())
        return withSession(stillWanted) { session -> SearchPage(session.uidValidity(folder), session.fetchByUids(folder, uids)) }
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
        // Checked before a byte is fetched. The source is buffered whole and then decoded into a
        // String, so a mail at the server's own 50 MB SMTP SIZE limit would cost ~150 MB resident
        // before MailCache's 10 MB per-entry ceiling could decline to store it -- and an
        // OutOfMemoryError is an Error, which neither the session's `io` nor the view model's
        // `act` catches, so it would take the process with it. The size is the server's own
        // RFC822.SIZE, the same number the list shows.
        val size = summary(folder, uid)?.sizeBytes ?: 0L
        if (size > MailLimits.SOURCE_BYTES) throw MailError.TooLarge(size, MailLimits.SOURCE_BYTES)
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

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?): SentCopy {
        val generation = account.accountGeneration
        if (account.isDemo) {
            val demoSent = folders().nameOf(SpecialFolder.SENT)
            demoSent?.let { sent -> demoMutate(sent) { it.add(0, demoOutgoingMail(mail, draft = false)) } }
            answered?.let { (folder, uid) -> demoUpdate(folder, uid) { s -> s.copy(flags = s.flags.copy(answered = true)) } }
            demoSent?.let { _changes.emit(MailChange.Changed(it, generation)) }
            return if (demoSent != null) SentCopy.Filed else SentCopy.NotAttempted
        }
        // The SMTP login uses the same rejected password, so §7.4 covers it too --
        // and `folders()` can answer from its cached resolution without a session.
        refuseWhileAuthFailed()
        // A null here -- no sent folder, none could be created, or the attempt did not complete
        // -- still sends: filing the copy is a convenience, and [MailSender] reports it as
        // [SentCopy.NotAttempted] rather than failing. Sending is the point.
        val sent = ensureFolderOrNull(SpecialFolder.SENT)
        // The sent copy runs on [holder]'s connection, not one of its own: Mail2000 caps
        // concurrent connections, so a second one is most likely to be refused exactly when the
        // mail screen is open and holding the first.
        val result = withContext(Dispatchers.IO) {
            sender.send(credentials(), mail, sent) { block -> withSession(block = block) }
        }
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
        // A copy in the sent folder, filed by the app or found there already, is mail a list of
        // that folder has not got.
        if (sent != null && (result.sentCopy == SentCopy.Filed || result.sentCopy == SentCopy.ServerFiledItself)) {
            _changes.emit(MailChange.Changed(sent, generation))
        }
        return result.sentCopy
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        val generation = account.accountGeneration
        if (account.isDemo) {
            val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
            val entry = demoOutgoingMail(mail, draft = true)
            // Like the real append-then-delete-old below, saving over a draft is a fresh UID, not
            // an in-place edit -- so this matches what re-fetching from the server would show.
            demoMutate(drafts) { list ->
                if (replacingUid != null) list.removeAll { it.summary.uid == replacingUid }
                list.add(0, entry)
            }
            _changes.emit(MailChange.Changed(drafts, generation))
            if (replacingUid != null) _changes.emit(MailChange.Removed(drafts, replacingUid, generation))
            return
        }
        // Unlike the sent copy, a draft that is not kept is the whole operation failing, so a
        // refused CREATE surfaces as the error the compose screen already reports -- and a
        // failure that never reached the server surfaces as itself, which reads better still.
        val drafts = ensureFolder(SpecialFolder.DRAFTS) ?: throw MailError.Protocol("no drafts folder")
        val bytes = withContext(Dispatchers.IO) { builder.build(mail).toBytes() }
        withSession { it.append(drafts, bytes, setOf(AppendFlag.SEEN, AppendFlag.DRAFT)) }
        // Told now rather than once the old draft is gone: the new one is on the server from here
        // on, whatever becomes of the delete below.
        _changes.emit(MailChange.Changed(drafts, generation))
        if (replacingUid != null) {
            runExpunging(drafts, replacingUid) { session, owned -> session.deletePermanently(drafts, replacingUid, owned) }
            _changes.emit(MailChange.Removed(drafts, replacingUid, generation))
        }
    }

    override suspend fun discardDraft(uid: Long) {
        val generation = account.accountGeneration
        if (account.isDemo) {
            val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
            demoMutate(drafts) { it.removeAll { m -> m.summary.uid == uid } }
            _changes.emit(MailChange.Removed(drafts, uid, generation))
            return
        }
        // Deliberately resolves without creating, unlike [saveDraft]: no drafts folder means
        // there is no draft to remove either, so a CREATE here would put a folder on the
        // student's real account for an operation that cannot do anything once it has one.
        val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
        runExpunging(drafts, uid) { session, owned -> session.deletePermanently(drafts, uid, owned) }
        _changes.emit(MailChange.Removed(drafts, uid, generation))
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
     * Returns null only when the server answered and the folder still is not there — a genuine
     * refusal — so each caller keeps the behaviour it had before folders were ever created:
     * [send] files no copy, [saveDraft] reports that the draft was not kept, and a delete falls
     * back to the permanent-delete path with its own confirmation.
     *
     * A failure that never reached an answer propagates instead of being reported as "the
     * account has no such folder". The two are not interchangeable: for the trash folder that
     * answer is what picks between the move-to-trash confirmation and the permanent-delete one,
     * and a network blip must never be what turns a student's "move to trash" into an
     * irreversible delete. [ensureFolderOrNull] opts back out of that for the sent copy alone.
     *
     * Created on any server, the debug mail-server override's included. The names are
     * Mail2000's own -- `寄件備份匣`, `草稿匣`, `回收筒` -- and the app deliberately owns one
     * unambiguous set of them everywhere rather than adopting whatever a mailbox happens
     * to hold: a long-lived account collects `Sent`, `Sent Items` and `Sent Messages` left
     * by different clients, and nothing distinguishes the real one reliably. Two
     * consequences are accepted knowingly -- mail TigerDuck files is not in another
     * client's own sent folder, and a test mailbox keeps these folders after the override
     * is turned off again.
     */
    private suspend fun ensureFolder(role: SpecialFolder): String? {
        require(role in CREATED_ON_DEMAND) { "$role is never created on demand" }
        folders().nameOf(role)?.let { return it }
        if (account.isDemo) return null
        val generation = account.accountGeneration
        return withSession { session ->
            // The CREATE's own answer is deliberately ignored -- a refusal because the folder
            // already exists is a success -- so it is the LIST below, and only the LIST, that
            // decides. A LIST that never completes is not an answer and is not swallowed here.
            runCatching { session.createFolder(role.decodedName) }
            MailFolders.resolve(session.listFolders())
        }.also { if (account.accountGeneration == generation) resolved = Resolved(generation, it) }.nameOf(role)
    }

    /**
     * [ensureFolder] with a failure that never reached the server treated as "no folder", the
     * way every caller used to treat one.
     *
     * Only the sent copy may do that: filing it is a convenience and sending is the point, so a
     * mailbox the app briefly could not reach must not fail a send whose SMTP half would have
     * gone through. Nothing destructive is decided by this answer.
     */
    private suspend fun ensureFolderOrNull(role: SpecialFolder): String? = try {
        ensureFolder(role)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
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
     * - Reads the owned-\Deleted set, acts, and writes it back **without
     *   leaving [withSession]**, so the whole read-modify-write runs under
     *   one hold of [SessionHolder]'s mutex. Two deletes overlapping in the
     *   same folder would otherwise both read the same set, both fail to
     *   expunge (each sees the other's freshly flagged \Deleted mail in the
     *   server-side `UID SEARCH DELETED`) and then both write -- last writer
     *   wins and the first UID is dropped from the set for good. `ours` would
     *   stay a strict subset of the server's \Deleted set from then on, so
     *   `expungeIfOnlyOurs` could never expunge that folder again for the
     *   life of the account: every later delete would leave both the trash
     *   copy and the original behind, invisible in the app but still on the
     *   student's quota, and nothing in the app could undo it.
     */
    private suspend fun runExpunging(folder: String, uid: Long, op: (MailSession, Set<Long>) -> Boolean) {
        val generation = account.accountGeneration
        val cached = cachedPage(folder)
        val cachedValidity = cached?.uidValidity
        val alreadyDeleted = cached?.messages?.firstOrNull { it.uid == uid }?.flags?.deleted == true
        var validity = 0L
        var attempted = false
        try {
            withSession { session ->
                val serverValidity = session.status(folder).uidValidity
                validity = serverValidity
                if (cachedValidity != null && cachedValidity != serverValidity) {
                    throw MailError.FolderChanged()
                }
                val owned = state.ownedDeleted(folder, serverValidity)
                attempted = true
                val expunged = op(session, owned)
                // An EXPUNGE is mailbox-wide and only runs when every \Deleted
                // mail in the folder was ours, so a successful one empties the
                // set rather than removing this one UID from it.
                state.setOwnedDeleted(folder, serverValidity, if (expunged) emptySet() else owned + uid)
            }
        } catch (e: MailError) {
            if (!attempted && e is MailError.FolderChanged) {
                withContext(Dispatchers.IO) { cache.deleteFolder(folder) }
                // A list of the folder holds rows of the mailbox the server has replaced.
                _changes.emit(MailChange.Changed(folder, generation))
            } else if (attempted && !alreadyDeleted && e !is MailError.AuthFailed && e !is MailError.Certificate) {
                recordIfDeletedOnServer(folder, uid, validity)
            }
            throw e
        }
    }

    /**
     * Re-reads the owned-\Deleted set inside the same [withSession] hold as the write, for the
     * reason [runExpunging] gives: the set this call's [runExpunging] read before [op] ran may
     * already be stale, and adding to a stale copy would drop whatever landed in between.
     */
    private suspend fun recordIfDeletedOnServer(folder: String, uid: Long, validity: Long) {
        try {
            withSession { session ->
                if (session.refreshFlags(folder, listOf(uid))[uid]?.deleted == true) {
                    state.setOwnedDeleted(folder, validity, state.ownedDeleted(folder, validity) + uid)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: not being able to check the flag just means the UID isn't recorded.
        }
    }

    private suspend fun updateCached(folder: String, transform: (MailSummary) -> MailSummary) = withContext(Dispatchers.IO) {
        val page = cachedPage(folder) ?: return@withContext
        cache.saveFolder(folder, page.copy(messages = page.messages.map(transform)))
    }

    private suspend fun removeCached(folder: String, uid: Long) = withContext(Dispatchers.IO) {
        val page = cachedPage(folder) ?: return@withContext
        cache.saveFolder(folder, page.copy(messages = page.messages.filterNot { it.uid == uid }, totalMessages = (page.totalMessages - 1).coerceAtLeast(0)))
    }

    companion object {
        const val PAGE_SIZE = 50

        /** The only roles [ensureFolder] may create; see its doc for why the other two are out. */
        private val CREATED_ON_DEMAND = setOf(SpecialFolder.SENT, SpecialFolder.DRAFTS, SpecialFolder.TRASH)

        private val DEMO_FOLDERS = ResolvedFolders(
            SpecialFolder.entries.associateWith { it.decodedName }, emptyList(),
        )
    }
}
