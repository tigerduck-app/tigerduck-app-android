package org.ntust.app.tigerduck.mail

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.mail.index.MailIndexScheduler
import org.ntust.app.tigerduck.mail.index.MailIndexer
import org.ntust.app.tigerduck.mail.index.MailSearchIndex
import org.ntust.app.tigerduck.mail.index.MailSessionRunner
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SearchOutcome {
    val messages: List<MailSummary>

    /**
     * The newest page of the matches the search index found, as the server has them now, and
     * [remaining]: the UIDs of every match not fetched yet, newest first, for
     * [SchoolMailRepository.searchPage] to fetch as the list scrolls.
     */
    data class Server(
        override val messages: List<MailSummary>,
        val remaining: List<Long> = emptyList(),
        /** The folder's UIDVALIDITY the matches were found under, which [remaining] only means anything in; null where unknown. */
        val uidValidity: Long? = null,
        /**
         * Whether the index held every mail of the folder when it was searched. False while the
         * first download is still bringing the older mail in: the search may have missed some.
         */
        val complete: Boolean = true,
    ) : SearchOutcome
    /**
     * The demo mailbox, which has no index; these are matches among the folder's newest page, with
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
    /**
     * Runs [block] with its requests ahead of every other one's, the background download's batches
     * included: see [SessionHolder.ahead]. For what the user is waiting on, and nothing longer.
     */
    suspend fun <T> ahead(block: suspend () -> T): T = block()

    suspend fun summary(folder: String, uid: Long): MailSummary?
    suspend fun body(folder: String, uid: Long): MailBody
    suspend fun setSeen(folder: String, uid: Long, seen: Boolean)

    /**
     * [stillWanted] as on [loadPage], for the COPY and STORE the move sends: a move confirmed for
     * one student that waited for the session while another signed in over them would otherwise
     * move a mail of theirs with the same UID.
     *
     * [uidValidity], when the caller has it, is the UIDVALIDITY [uid] was read under, checked
     * against the server's as the folder is selected for the write ([MailSession.move]): a folder
     * recreated since ends the call with [MailError.FolderChanged] and nothing sent, even when the
     * cache has been replaced by a page of the new folder meanwhile, which the cache's own check
     * would let through.
     */
    suspend fun move(folder: String, uid: Long, target: String, uidValidity: Long? = null, stillWanted: () -> Boolean = { true })

    /** [stillWanted] as on [move], for the LIST and CREATE a missing trash costs. */
    suspend fun deletesPermanently(folder: String, stillWanted: () -> Boolean = { true }): Boolean

    /** [uidValidity] and [stillWanted] as on [move]. */
    suspend fun delete(folder: String, uid: Long, uidValidity: Long? = null, stillWanted: () -> Boolean = { true })

    /**
     * The mail of [folder] whose sender, subject or text holds [query], found in the search index
     * on this phone: the school's server cannot search in Chinese. Mail come in since the index
     * last caught up is added first, the newest few. [stillWanted] as on [loadPage].
     */
    suspend fun search(folder: String, query: String, stillWanted: () -> Boolean = { true }): SearchOutcome

    /**
     * Downloads into the search index every mail it lacks, in every folder, Inbox and Sent first;
     * nothing within [MailRepository.INDEX_RESYNC_MS] of a download that got through. Long: the
     * first run fetches the text of the whole mailbox. [stillWanted] as on [loadPage].
     */
    suspend fun updateSearchIndex(stillWanted: () -> Boolean = { true })

    /** How far [updateSearchIndex] has come while it downloads; null otherwise. */
    val searchIndexProgress: StateFlow<MailIndexProgress?>

    /** The disk the mail downloaded for search and the list takes on the phone, in bytes. */
    suspend fun searchIndexBytes(): Long

    /**
     * Every mail of the signed-in account on the phone: what [updateSearchIndex] downloaded, in
     * every folder, which belongs to one account. The compose screen suggests recipients from it
     * (`RecipientSuggestions`); none once the account has signed out, and none of an account
     * another student signed in over.
     */
    suspend fun correspondence(): List<MailSummary>

    /**
     * Deletes the mail downloaded for search and the list, stopping any download under way. The
     * mail screen downloads it again when it is next opened.
     */
    suspend fun deleteSearchIndex()

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
    private val searchIndex: MailSearchIndex,
    private val indexScheduler: MailIndexScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) : SchoolMailRepository {
    @VisibleForTesting
    internal val holder = SessionHolder(sessions, scope)

    private val indexer = MailIndexer(
        searchIndex,
        object : MailSessionRunner {
            override suspend fun <T> run(stillWanted: () -> Boolean, block: (MailSession) -> T): T = withSession(stillWanted, block = block)
        },
        owner = ::indexOwnerNow,
        stillOwner = ::stillIndexOwner,
    )
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
            account.signOuts.collect { departing ->
                numberings.clear()
                lastWhole = null
                clearProgressOf(departing)
                withContext(Dispatchers.IO) {
                    // With a search's request, which may be waiting for WorkManager's answer.
                    scheduled { indexScheduler.cancel() }
                    holder.closeNow()
                    clearIndexOf(departing)
                }
                resolved = null
                synchronized(demoFolders) { demoFolders.clear(); demoUidSeq = 9_000L }
            }
        }
        // Another student signing in over this one leaves no mail of this one's to search. The
        // index refuses a write from a run this one started, and starts over for the next.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            account.studentChanges.collect { departing ->
                numberings.clear()
                lastWhole = null
                clearProgressOf(departing)
                withContext(Dispatchers.IO) { clearIndexOf(departing) }
            }
        }
    }

    /**
     * Empties the search index after [departing] left, unless it is by now the mail of whoever
     * signed in since: this runs some time after the account changed, and their download may have
     * opened the index already, which emptied it of the departing account's mail as it did. The
     * departing account's own mail goes whatever its address, even with that student signed in
     * again: a sign-out empties the index.
     */
    private fun clearIndexOf(departing: String?) {
        val left = departing?.lowercase()
        searchIndex.clearUnless { owner -> owner != null && owner != left && owner == indexOwner() }
    }

    /**
     * Tells the list of [change], which the phone's copy of the mail has by then: [setSeen] and
     * [runExpunging] change it in the hold of the connection their write went out in. A removal
     * also moves the sequence numbers after it.
     */
    private suspend fun changed(change: MailChange) {
        if (change is MailChange.Removed) numberings.remove(change.folder)
        _changes.emit(change)
    }

    /**
     * The copy of the account at [generation], read before a write went out, while it is still
     * the one signed in: a write that ended after another student signed in names a mail of
     * theirs by its UID (see [MailChange.generation]), which it must neither mark nor drop. Asked
     * again under the index's lock, which the sign-in's clear takes too.
     *
     * Called in the hold of the connection the write went out in, as a download's batch is filed
     * in the hold that read it: the download that opens a folder recreated since, in a hold of
     * its own, comes wholly before or after, so the write never reaches the new folder's mail by
     * an old UID, nor does an answer read before the write undo it.
     */
    private fun writerOfIndex(generation: Int): MailIndexer.Owner? = indexOwnerNow()?.takeIf { it.generation == generation }

    private fun markSeenInIndex(owner: MailIndexer.Owner, folder: String, uid: Long, seen: Boolean) {
        val validity = searchIndex.uidValidity(owner.address, folder) ?: return
        val summary = searchIndex.summaries(owner.address, folder, validity, listOf(uid))[uid] ?: return
        searchIndex.updateFlags(owner.address, folder, validity, mapOf(uid to summary.flags.copy(seen = seen))) { stillIndexOwner(owner) }
    }

    /**
     * Every UID of a folder, ascending, as it was when its newest page was last fetched, so an
     * older page can be found by sequence number on the phone: a mail's is its place here, from 1.
     */
    private class Numbering(val generation: Int, val uidValidity: Long, val uids: LongArray)

    private val numberings = ConcurrentHashMap<String, Numbering>()

    /**
     * The older page of [folder] before [beforeSeq], from the phone's copy, when every mail in it
     * has been downloaded; null sends it to the server. As [MailSession.fetchPage] answers: newest
     * first, the UIDs it spans, and where the next one starts.
     */
    private suspend fun localOlderPage(folder: String, beforeSeq: Int, limit: Int): MailPage? = withContext(Dispatchers.IO) {
        // The account's address is a read of the encrypted store: on the IO dispatcher too.
        val owner = indexOwner() ?: return@withContext null
        val numbering = numberings[folder]?.takeIf { it.generation == account.accountGeneration } ?: return@withContext null
        val end = beforeSeq - 1
        if (end < 1 || end > numbering.uids.size) return@withContext null
        val start = maxOf(1, end - limit + 1)
        val window = numbering.uids.copyOfRange(start - 1, end).asList()
        val stored = searchIndex.summaries(owner, folder, numbering.uidValidity, window)
        // A mail not downloaded yet, or flagged \Deleted, which the copy leaves out: the server's.
        if (stored.size < window.size) return@withContext null
        MailPage(
            uidValidity = numbering.uidValidity,
            totalMessages = numbering.uids.size,
            messages = stored.values.sortedByDescending { it.uid },
            nextBeforeSeq = if (start > 1) start else null,
            windowUids = window.first()..window.last(),
        )
    }

    /** Whose the search index is: the signed-in account's address, whatever its case. */
    private fun indexOwner(): String? = account.ownAddress?.lowercase()

    /**
     * Moved on by [deleteSearchIndex] before it empties the index. A download it does not reach in
     * time -- the background one, which stops only some time after WorkManager is told to, or a
     * search's catch-up -- asks for the one it started under before each write, and writes no more.
     */
    private val indexEpoch = AtomicInteger()

    /**
     * Whom a write to the search index is for: [indexOwner] with the sign-in it belongs to, read
     * together, and the [indexEpoch]. A read of the encrypted store, so not for the main thread.
     */
    private fun indexOwnerNow(): MailIndexer.Owner? {
        val epoch = indexEpoch.get()
        return account.snapshot()?.let { MailIndexer.Owner(it.credentials.address.lowercase(), it.generation, epoch) }
    }

    /** Whether the index is still [owner]'s: no sign-in, sign-out or deletion since it was read. */
    private fun stillIndexOwner(owner: MailIndexer.Owner): Boolean =
        account.accountGeneration == owner.generation && indexEpoch.get() == owner.epoch

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
    private suspend fun <T> withSession(
        stillWanted: () -> Boolean = { true },
        /** The account to hold the session as, read with its state ([MailAccount.snapshot]); the signed-in one when null. */
        owner: MailAccount.Snapshot? = null,
        block: (MailSession) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (account.isDemo) throw MailError.DemoMode()
        refuseWhileAuthFailed()
        // The account [owner] names, or the one signed in now, read with its sign-in.
        val signedIn = owner ?: account.snapshot() ?: throw MailError.Protocol("not signed in")
        holder.use(
            signedIn.credentials,
            // Asked again under the connection's lock: a request that waited out a login the
            // server rejected must not send one of its own.
            stillWanted = { refuseWhileAuthFailed(); stillWanted() },
            // Told to the account before that lock is let go, for the sign-in these credentials
            // are: the refusal of one that has left since must not stop the next.
            onRefused = { account.onAuthFailure(signedIn.generation) },
            block = block,
        )
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
        // An older page whose mail the phone holds comes from there, without the network.
        if (beforeSeq != null) localOlderPage(folder, beforeSeq, limit)?.let { return it }
        val page = withSession(stillWanted) { session ->
            session.fetchPage(folder, beforeSeq, limit).also { page ->
                // The newest page numbers the folder for the older ones: one SEARCH, which reads
                // no mail.
                if (beforeSeq == null) {
                    val uids = session.allUids(folder).sorted().toLongArray()
                    numberings[folder] = Numbering(generation, page.uidValidity, uids)
                }
            }
        }
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

    override suspend fun <T> ahead(block: suspend () -> T): T = holder.ahead(block)

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
            withSession { session ->
                session.setSeen(folder, listOf(uid), seen)
                writerOfIndex(generation)?.let { markSeenInIndex(it, folder, uid, seen) }
            }
            updateCached(folder) { s -> if (s.uid == uid) s.copy(flags = s.flags.copy(seen = seen)) else s }
        }
        changed(MailChange.Seen(folder, uid, seen, generation))
    }

    override suspend fun move(folder: String, uid: Long, target: String, uidValidity: Long?, stillWanted: () -> Boolean) {
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
            runExpunging(folder, uid, stillWanted) { session, owned -> session.move(folder, uid, target, owned, uidValidity) }
            removeCached(folder, uid, stillWanted)
        }
        changed(MailChange.Removed(folder, uid, generation))
        // The mail's UID in [target] is the server's to give (COPYUID is not read), so a list of
        // the target can only fetch it.
        changed(MailChange.Changed(target, generation))
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
    override suspend fun deletesPermanently(folder: String, stillWanted: () -> Boolean): Boolean = trashFor(folder, stillWanted) == null

    /**
     * Where a delete from [folder] moves the mail, or null when deleting there is
     * permanent: either [folder] *is* the trash, or the account has none and the server
     * would not create one.
     *
     * [deletesPermanently] normally runs first and leaves a created folder in [resolved],
     * so the [delete] that follows costs no second CREATE. When the first attempt was
     * refused this does try again, which can only turn a permanent delete into a move to
     * trash — the safer of the two, and never the other way round.
     *
     * [stillWanted] as on [move], for the LIST and CREATE: a delete readied for a student who
     * left meanwhile must not create a folder in the next one's mailbox.
     */
    private suspend fun trashFor(folder: String, stillWanted: () -> Boolean = { true }): String? {
        if (folders(stillWanted).nameOf(SpecialFolder.TRASH) == folder) return null
        return ensureFolder(SpecialFolder.TRASH, stillWanted)
    }

    override suspend fun delete(folder: String, uid: Long, uidValidity: Long?, stillWanted: () -> Boolean) {
        val generation = account.accountGeneration
        trashFor(folder, stillWanted)?.let { return move(folder, uid, it, uidValidity, stillWanted) }
        if (account.isDemo) {
            demoMutate(folder) { it.removeAll { m -> m.summary.uid == uid } }
        } else {
            runExpunging(folder, uid, stillWanted) { session, owned -> session.deletePermanently(folder, uid, owned, uidValidity) }
            removeCached(folder, uid, stillWanted)
        }
        changed(MailChange.Removed(folder, uid, generation))
    }

    override suspend fun search(folder: String, query: String, stillWanted: () -> Boolean): SearchOutcome {
        val q = query.trim()
        if (account.isDemo) return demoPage(folder).let { page -> SearchOutcome.LoadedOnly(page.messages.filter { matchesLoadedMail(it, q) }, page.uidValidity) }
        val asked = indexEpoch.get()
        val generation = account.accountGeneration
        val whole = indexer.update(folder, limit = SEARCH_CATCH_UP, newOnly = true, stillWanted = stillWanted)
        // The rest of what the index lacks comes in the background, the first run all of it.
        // Asked for once the catch-up is through: after a rejected password, which the list hands
        // the account, nothing may log in again (spec §7.4). And asked for at once: mail come in
        // past what the catch-up fetches sits below the newest it has, where no later catch-up
        // looks, so a whole download within [INDEX_RESYNC_MS] must not hold that run off
        // ([gapsFound]).
        withContext(Dispatchers.IO) { askForTheRest(asked, generation, whole) }
        // With the UIDVALIDITY they were indexed under, which their UIDs only name mail in.
        val (indexedUnder, matches) = withContext(Dispatchers.IO) {
            val owner = indexOwner() ?: return@withContext null to emptyList()
            val validity = searchIndex.uidValidity(owner, folder) ?: return@withContext null to emptyList()
            validity to searchIndex.search(owner, folder, validity, q)
        }
        // Newest first, so the first page is the newest matches and the rest page back. One hold
        // of the connection, so the page and the UIDVALIDITY are the server's at the same time.
        return withSession(stillWanted) { session ->
            val validity = session.uidValidity(folder)
            // The folder recreated since the index answered: the matches' UIDs name other mail,
            // and nothing of the new folder's was searched.
            val current = validity == indexedUnder
            val found = if (current) matches else emptyList()
            val newest = found.take(PAGE_SIZE)
            SearchOutcome.Server(session.fetchByUids(folder, newest), found.drop(newest.size), validity, complete = whole && current)
        }
    }

    private val _searchIndexProgress = MutableStateFlow<MailIndexProgress?>(null)
    override val searchIndexProgress: StateFlow<MailIndexProgress?> = _searchIndexProgress.asStateFlow()

    // Under progressLock: the mailbox, by address and index epoch, whose download the progress
    // tells of. A sign-in of the same student again keeps it, as it keeps the downloaded mail.
    private val progressLock = Any()
    private var progressMailbox: Pair<String, Int>? = null

    private fun MailIndexer.Owner.mailbox() = address to epoch

    // [progress] of [owner]'s download, while the index is still its own.
    private fun showProgress(owner: MailIndexer.Owner, progress: MailIndexProgress) = synchronized(progressLock) {
        if (!stillIndexOwner(owner)) return
        progressMailbox = owner.mailbox()
        _searchIndexProgress.value = progress
    }

    // [owner]'s progress, stopped, left on screen while mail is missing from search (#587):
    // unless the mailbox's progress was cleared since, by a sign-out or the mail's deletion.
    private fun stopProgress(owner: MailIndexer.Owner) = synchronized(progressLock) {
        if (progressMailbox != owner.mailbox()) return
        _searchIndexProgress.value = _searchIndexProgress.value?.copy(running = false)
    }

    // No progress: [owner]'s download got through with no gap found since its last pass planned
    // ([gaps]), asked under the lock a gap is published under; its mailbox left ([departing]); or
    // the downloaded mail went (null, every mailbox). Only that mailbox's: another's signed in
    // since stays.
    private fun clearProgress(owner: MailIndexer.Owner, gaps: Int) =
        clearProgressWhere { it == owner.mailbox() && gapsFound.get() == gaps }

    // A sign-out with no one signed in ([departing] null) has no mailbox to clear.
    private fun clearProgressOf(departing: String?) {
        if (departing != null) clearProgressWhere { it.first == departing.lowercase() }
    }

    private fun clearAllProgress() = clearProgressWhere { true }

    // A search found mail missing: the row says so, stopped, until a download gets through,
    // where it said OK; a download under way keeps its own. Under the lock the last pass's clear
    // asks [gapsFound] under, so it cannot clear past this gap.
    private fun noteGap() = synchronized(progressLock) {
        gapsFound.incrementAndGet()
        if (_searchIndexProgress.value != null) return
        val owner = indexOwnerNow() ?: return
        progressMailbox = owner.mailbox()
        _searchIndexProgress.value = MailIndexProgress.COUNTING.copy(running = false)
    }

    private fun clearProgressWhere(mailbox: (Pair<String, Int>) -> Boolean) = synchronized(progressLock) {
        val shown = progressMailbox ?: return
        if (!mailbox(shown)) return
        progressMailbox = null
        _searchIndexProgress.value = null
    }

    /** Held by the download under way, so the in-app one and the background one never both run. */
    private val indexing = Mutex()

    /** Whether a download runs in the app now, which a test waits out. */
    @VisibleForTesting
    internal val downloadingInApp: Boolean get() = indexing.isLocked

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * A download that got through every folder, for [owner], at [at], planned once a search had
     * found the index lacking mail [gaps] times.
     */
    private class WholeDownload(val owner: MailIndexer.Owner, val at: Long, val gaps: Int)

    /**
     * The last download that got through every folder; null for none since the index last started
     * over. One for an owner the index is no longer, signed in again or deleted since, counts as
     * none, even one that only says so after the deletion.
     */
    @Volatile private var lastWhole: WholeDownload? = null

    /**
     * Moved on by a search whose catch-up left mail out ([search]). A download planned before it
     * moved did not see that mail, so it does not hold the next one off, even one that only says
     * it got through afterwards.
     */
    private val gapsFound = AtomicInteger()

    /**
     * Whether opening the mail screen downloads again: not within [INDEX_RESYNC_MS] of a whole
     * download, which coming back from another page would otherwise repeat every time, every
     * folder listed and every downloaded mail's flags asked for. New mail meanwhile reaches the
     * list by its own poll, and a search catches up on it first.
     */
    private fun indexDue(): Boolean {
        val last = lastWhole ?: return true
        return !stillIndexOwner(last.owner) || last.gaps != gapsFound.get() || clock() - last.at >= INDEX_RESYNC_MS
    }

    /**
     * Asked once it has the lock, as the download in the app asks: the worker a visit asked for
     * waits out the download that visit runs in the app, and would otherwise run every folder
     * again the moment that one got through.
     */
    override suspend fun updateSearchIndex(stillWanted: () -> Boolean) {
        // Read before the wait for the lock, as a visit reads it before its download starts.
        val asked = indexEpoch.get()
        indexing.withLock { if (indexDue()) downloadIndex(asked, stillWanted) }
    }

    /**
     * Nothing once the downloaded mail has been deleted since the download was [asked] for, at
     * that [indexEpoch]: one that waited out the deletion must not download it all again, which
     * only the mail screen opened afterwards does.
     *
     * A password the server rejects stops it, and every login after it, as the list's own
     * requests do (spec §7.4): the background download and the one in the app both come here.
     */
    private suspend fun downloadIndex(asked: Int, stillWanted: () -> Boolean = { true }) {
        if (account.isDemo || state.authFailed) return
        val owner = withContext(Dispatchers.IO) { indexOwnerNow() }?.takeIf { it.epoch == asked } ?: return
        try {
            // The folder list a first run resolves is the account's request too.
            val current = { stillWanted() && stillIndexOwner(owner) }
            // Again while a search found mail missing as this ran: this planned before that mail
            // came, and the run the search asked for was this one -- the background run's request
            // keeps the one already under way, and the download in the app runs one at a time.
            // Only for a gap found since the pass began, which only a search makes, so it ends.
            var gaps: Int
            var through: Boolean
            do {
                gaps = gapsFound.get()
                through = downloadEveryFolder(owner, gaps, current)
            } while (gapsFound.get() != gaps && current())
            // Done only when the last pass got through and no search found a gap since: a pass
            // let go of before it ran again leaves mail missing, which the row keeps saying.
            if (through) clearProgress(owner, gaps)
        } catch (e: MailError.AuthFailed) {
            // Unless the account that was refused has left since: its rejection must not stop the
            // next one, whose password the server has not seen.
            account.onAuthFailure(owner.generation)
            throw e
        }
    }

    /**
     * One pass over every folder, planned once a search had found [gaps] gaps ([gapsFound]); says
     * whether it got through.
     */
    private suspend fun downloadEveryFolder(owner: MailIndexer.Owner, gaps: Int, stillWanted: () -> Boolean): Boolean {
        // Under way from the first folder listed: counting what every folder lacks takes seconds
        // on a large mailbox, and the sync dot used to say "OK" through all of it.
        showProgress(owner, MailIndexProgress.COUNTING)
        var through = false
        try {
            val folders = folders(stillWanted)
            val first = listOfNotNull(folders.nameOf(SpecialFolder.INBOX), folders.nameOf(SpecialFolder.SENT))
            // Every folder's count first, for the progress to have one total from the start.
            val names = first + (folders.special.values + folders.others).filter { it !in first }
            val plans = names.mapNotNull { indexer.plan(it, stillWanted = stillWanted) }
            var whole = plans.size == names.size
            // Mail read, answered or deleted elsewhere since it was downloaded. A folder recreated
            // since its plan is cut short, and the download with it.
            for (plan in plans) {
                val refreshed = indexer.refreshFlags(plan, stillWanted)
                whole = whole && refreshed
            }
            val total = plans.sumOf { it.missing.size }
            if (total == 0) {
                if (whole) lastWhole = WholeDownload(owner, clock(), gaps)
                through = whole
                return through
            }
            var done = 0
            showProgress(owner, MailIndexProgress(done, total))
            for (plan in plans) {
                val filled = indexer.fill(plan, stillWanted = stillWanted) { added ->
                    done += added
                    showProgress(owner, MailIndexProgress(done, total))
                }
                whole = whole && filled
            }
            if (whole) lastWhole = WholeDownload(owner, clock(), gaps)
            through = whole
            return through
        } finally {
            // Stopped, and left: one cut short -- the network, a folder recreated meanwhile --
            // leaves mail out of search, and the row says how far it came rather than OK until
            // one gets through (#587). The caller clears it once one has; see downloadIndex.
            stopProgress(owner)
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
            demoSent?.let { changed(MailChange.Changed(it, generation)) }
            return if (demoSent != null) SentCopy.Filed else SentCopy.NotAttempted
        }
        // The SMTP login uses the same rejected password, so §7.4 covers it too --
        // and `folders()` can answer from its cached resolution without a session.
        refuseWhileAuthFailed()
        // A null here -- no sent folder, none could be created, or the attempt did not complete
        // -- still sends: filing the copy is a convenience, and [MailSender] reports it as
        // [SentCopy.NotAttempted] rather than failing. Sending is the point.
        val sent = ensureFolderOrNull(SpecialFolder.SENT)
        // Asked again: the folder's lookup can have met the rejection, or waited out a login that
        // did, and dropped it with whatever else kept the sent copy from being filed.
        refuseWhileAuthFailed()
        // The sent copy runs on [holder]'s connection, not one of its own: Mail2000 caps
        // concurrent connections, so a second one is most likely to be refused exactly when the
        // mail screen is open and holding the first.
        val result = withContext(Dispatchers.IO) {
            // The account the SMTP login goes out as, read with its sign-in: a refusal of that
            // login is that sign-in's (spec §7.4), whichever one the screen that asked opened under.
            val signedIn = account.snapshot() ?: throw MailError.Protocol("not signed in")
            try {
                sender.send(signedIn.credentials, mail, sent) { block -> withSession(block = block) }
            } catch (e: MailError.AuthFailed) {
                account.onAuthFailure(signedIn.generation)
                throw e
            }
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
            changed(MailChange.Changed(sent, generation))
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
            changed(MailChange.Changed(drafts, generation))
            if (replacingUid != null) changed(MailChange.Removed(drafts, replacingUid, generation))
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
        changed(MailChange.Changed(drafts, generation))
        if (replacingUid != null) {
            runExpunging(drafts, replacingUid) { session, owned -> session.deletePermanently(drafts, replacingUid, owned) }
            changed(MailChange.Removed(drafts, replacingUid, generation))
        }
    }

    override suspend fun discardDraft(uid: Long) {
        val generation = account.accountGeneration
        if (account.isDemo) {
            val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
            demoMutate(drafts) { it.removeAll { m -> m.summary.uid == uid } }
            changed(MailChange.Removed(drafts, uid, generation))
            return
        }
        // Deliberately resolves without creating, unlike [saveDraft]: no drafts folder means
        // there is no draft to remove either, so a CREATE here would put a folder on the
        // student's real account for an operation that cannot do anything once it has one.
        val drafts = folders().nameOf(SpecialFolder.DRAFTS) ?: return
        runExpunging(drafts, uid) { session, owned -> session.deletePermanently(drafts, uid, owned) }
        changed(MailChange.Removed(drafts, uid, generation))
    }

    override fun selfAddress(): MailAddress = MailAddress(state.displayName, credentials().address)

    override fun release() = holder.releaseLater()

    /** The download running in the app, which deleting what it downloads stops. */
    @Volatile private var inAppIndexing: Job? = null

    override suspend fun searchIndexBytes(): Long = withContext(Dispatchers.IO) { searchIndex.sizeBytes() }

    override suspend fun correspondence(): List<MailSummary> = withContext(Dispatchers.IO) {
        if (account.isDemo) {
            return@withContext synchronized(demoFolders) {
                ensureDemoSeeded()
                demoFolders.values.flatten().map { it.summary }
            }
        }
        // The address with the sign-in it belongs to, read together: a sign-in moves the
        // generation before it puts the next student's address in place.
        val owner = indexOwnerNow() ?: return@withContext emptyList()
        val mail = searchIndex.allMail(owner.address).map { it.summary }
        // Read while another student signed in over this one: none of it is theirs.
        if (stillIndexOwner(owner)) mail else emptyList()
    }

    /**
     * Held while a search asks for a run, and while [deleteSearchIndex] or a sign-out cancels them:
     * a run asked for just before the one is cancelled with it, and none after it. Only off the
     * main thread, since a search holds it while WorkManager answers.
     */
    private val scheduling = Any()

    /** Runs right before a step waits for [scheduling]: a test's way of knowing it has got there. Null in the app. */
    @VisibleForTesting
    @Volatile
    internal var onSchedulingWait: (() -> Unit)? = null

    private inline fun <T> scheduled(block: () -> T): T {
        onSchedulingWait?.invoke()
        return synchronized(scheduling, block)
    }

    /**
     * A search's request for the background run, [whole] saying whether its catch-up left nothing
     * out. None for a search begun before the downloaded mail was deleted, at epoch [asked]: the
     * run would download it all again, which only the mail screen opened afterwards does. Nor for
     * one of a sign-in that has left since [generation]. Asked in one step with the deletion's or
     * the sign-out's cancel, so a run asked for just before either is cancelled with it.
     */
    private fun askForTheRest(asked: Int, generation: Int, whole: Boolean) = scheduled {
        if (indexEpoch.get() != asked || account.accountGeneration != generation) return@scheduled
        if (whole) {
            indexScheduler.request()
        } else {
            noteGap()
            // A run that plans after now, if the one under way may be past its last look at
            // [gapsFound], or done and yet to be filed so, when a request would only join it.
            indexScheduler.requestAgain()
        }
    }

    /**
     * Not waiting for a download to stop, which could be out on a slow network for as long as its
     * timeout: the [indexEpoch] it moves on first is what keeps whatever is still under way from
     * writing after the clear. Each write asks for it under the index's lock, which the clear
     * takes too.
     */
    override suspend fun deleteSearchIndex() {
        withContext(Dispatchers.IO) {
            scheduled {
                indexEpoch.incrementAndGet()
                indexScheduler.cancel()
            }
        }
        inAppIndexing?.cancel()
        lastWhole = null
        clearAllProgress()
        numberings.clear()
        withContext(Dispatchers.IO) { searchIndex.clear() }
    }

    override fun acquire() {
        if (account.isDemo) return
        holder.hold()
        // A rejected password downloads nothing until the student signs in again (spec §7.4).
        if (!indexDue() || state.authFailed) return
        // The mail screen is in use: whatever mail the phone lacks comes in now, here, rather than
        // when the system next runs the background download, which some phones hold back for
        // minutes. That one still finishes what this leaves, should the app go.
        indexScheduler.request()
        // Read now, not once the download starts: see [downloadIndex].
        val asked = indexEpoch.get()
        scope.launch {
            if (!indexing.tryLock()) return@launch
            // This one, never a later visit's that found it under way: deleting the downloaded
            // mail stops the download that holds the lock.
            inAppIndexing = coroutineContext[Job]
            try {
                // Asked again: another download can have got through while this one waited.
                if (indexDue()) downloadIndex(asked)
            } catch (e: CancellationException) {
                throw e
            } catch (_: MailError) {
                // Left to the background download, which tries again.
            } finally {
                inAppIndexing = null
                indexing.unlock()
            }
        }
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
    private suspend fun ensureFolder(role: SpecialFolder, stillWanted: () -> Boolean = { true }): String? {
        require(role in CREATED_ON_DEMAND) { "$role is never created on demand" }
        folders(stillWanted).nameOf(role)?.let { return it }
        if (account.isDemo) return null
        val generation = account.accountGeneration
        return withSession(stillWanted) { session ->
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
     * - Asks [stillWanted] with the session held, before anything is sent
     *   ([SchoolMailRepository.loadPage]): a write of a student who left while
     *   it waited for the session, another having signed in over them, ends as
     *   [MailError.Abandoned] instead of acting on that one's mailbox. The
     *   flag check after a failed [op] asks on the set's own state instead
     *   (see the next point and [recordIfDeletedOnServer]).
     * - Reads the folder's live UIDVALIDITY inside the same held connection
     *   before [op] runs. If it doesn't match what the cache believes, the
     *   cache is dropped and [MailError.FolderChanged] is thrown before
     *   anything is touched -- a stale cached UID must never be acted on, or
     *   keyed into the owned-\Deleted set, under a numbering the server has
     *   since replaced. The UIDVALIDITY the caller read its UID under, which a
     *   page of the new folder may have put in the cache as well by then, is
     *   checked by [op]'s session as it selects the folder for the write
     *   ([MailSession.move]). Either way [MailError.FolderChanged] means
     *   nothing was sent, and a list of the folder is told to fetch it again.
     * - If [op] throws after possibly already landing its COPY + flag on the
     *   server, the UID is added to the owned-\Deleted set only when a fresh
     *   [MailSession.refreshFlags] confirms it is actually flagged \Deleted
     *   there, and only when it wasn't already flagged before this call (so
     *   another client's earlier \Deleted flag is never attributed to us),
     *   and never for [MailError.AuthFailed]/[MailError.Certificate] (no
     *   second login attempt just to check).
     * - Takes [MailStateStore.clears] first, and reads and records the set only
     *   while it still stands: a student signing in over this one, or a
     *   sign-out, while the write waits or is on the connection resets the
     *   state for whoever comes next. Their set must neither decide this
     *   write's EXPUNGE nor take its UID, or a later EXPUNGE of theirs in a
     *   folder of the same name and UIDVALIDITY would take a mail another
     *   client flagged as the app's own. The flag check after a failed [op]
     *   records under the same rule.
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
    private suspend fun runExpunging(
        folder: String,
        uid: Long,
        stillWanted: () -> Boolean = { true },
        op: (MailSession, Set<Long>) -> Boolean,
    ) {
        val generation = account.accountGeneration
        // The account the write is made as, read together with its state's clear count: the
        // session below is held as that account, and its set is the one read and recorded into.
        // On the IO dispatcher, as every read of the credentials here is: it decrypts the password.
        val owner = withContext(Dispatchers.IO) { account.snapshot() } ?: throw MailError.Protocol("not signed in")
        val clears = owner.clears
        val cached = cachedPage(folder)
        val cachedValidity = cached?.uidValidity
        val alreadyDeleted = cached?.messages?.firstOrNull { it.uid == uid }?.flags?.deleted == true
        var validity = 0L
        var attempted = false
        var cacheStale = false
        try {
            withSession(stillWanted, owner) { session ->
                val serverValidity = session.status(folder).uidValidity
                validity = serverValidity
                if (cachedValidity != null && cachedValidity != serverValidity) {
                    cacheStale = true
                    throw MailError.FolderChanged()
                }
                if (state.clears != clears) throw MailError.Abandoned()
                val owned = state.ownedDeleted(folder, serverValidity)
                attempted = true
                val expunged = op(session, owned)
                // An EXPUNGE is mailbox-wide and only runs when every \Deleted
                // mail in the folder was ours, so a successful one empties the
                // set rather than removing this one UID from it.
                state.setOwnedDeletedIf(folder, serverValidity, if (expunged) emptySet() else owned + uid, clears)
                // Gone from the phone's copy too, under the UIDVALIDITY the write went out under,
                // for the sign-in it went out as.
                writerOfIndex(owner.generation)?.let { writer ->
                    searchIndex.remove(writer.address, folder, serverValidity, listOf(uid)) { stillIndexOwner(writer) }
                }
            }
        } catch (e: MailError) {
            if (e is MailError.FolderChanged) {
                // Nothing was sent, so there is nothing to record. The cache goes only when it was
                // the stale one: one the caller's UID was older than holds the folder as it is.
                if (cacheStale) withContext(Dispatchers.IO) { cache.deleteFolder(folder) }
                // A list of the folder holds rows of the mailbox the server has replaced.
                changed(MailChange.Changed(folder, generation))
            } else if (attempted && !alreadyDeleted && e !is MailError.AuthFailed && e !is MailError.Certificate) {
                recordIfDeletedOnServer(folder, uid, validity, clears)
            }
            throw e
        }
    }

    /**
     * Re-reads the owned-\Deleted set inside the same [withSession] hold as the write, for the
     * reason [runExpunging] gives: the set this call's [runExpunging] read before [op] ran may
     * already be stale, and adding to a stale copy would drop whatever landed in between.
     *
     * Asked whether it is still wanted on the set's own [MailStateStore.clears], not on the
     * write's `stillWanted`: a list's write asks on the account generation, which the same student
     * signing in again moves while keeping the set, and the flag that landed must still be recorded
     * there or no later EXPUNGE of the folder takes that mail. As whoever is signed in now, read
     * with the count ([MailAccount.snapshot]): while the count stands that is the same student,
     * perhaps under a new password. Another student's sign-in clears the set, and then nothing is
     * checked or recorded.
     */
    private suspend fun recordIfDeletedOnServer(folder: String, uid: Long, validity: Long, clears: Int) {
        try {
            val now = withContext(Dispatchers.IO) { account.snapshot() }?.takeIf { it.clears == clears } ?: return
            withSession({ state.clears == clears }, now) { session ->
                if (session.refreshFlags(folder, listOf(uid))[uid]?.deleted == true) {
                    state.setOwnedDeletedIf(folder, validity, state.ownedDeleted(folder, validity) + uid, clears)
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

    /**
     * [stillWanted] as the write's own: the cache of a student who signed in after the write went
     * out is not theirs to lose a row from, whatever UID the write named.
     */
    private suspend fun removeCached(folder: String, uid: Long, stillWanted: () -> Boolean = { true }) {
        withContext(Dispatchers.IO) {
            cachedPage(folder)?.let { page ->
                val rest = page.copy(messages = page.messages.filterNot { it.uid == uid }, totalMessages = (page.totalMessages - 1).coerceAtLeast(0))
                cache.saveFolderIf(folder, rest, stillWanted)
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 50

        /**
         * The newest mail a search adds to the index before it looks, of what came in since the
         * index last caught up: the background run fetches the rest.
         */
        const val SEARCH_CATCH_UP = 10

        /** How soon after a whole download coming back to the mail screen downloads again. */
        const val INDEX_RESYNC_MS = 15 * 60_000L

        /** The only roles [ensureFolder] may create; see its doc for why the other two are out. */
        private val CREATED_ON_DEMAND = setOf(SpecialFolder.SENT, SpecialFolder.DRAFTS, SpecialFolder.TRASH)

        private val DEMO_FOLDERS = ResolvedFolders(
            SpecialFolder.entries.associateWith { it.decodedName }, emptyList(),
        )
    }
}
