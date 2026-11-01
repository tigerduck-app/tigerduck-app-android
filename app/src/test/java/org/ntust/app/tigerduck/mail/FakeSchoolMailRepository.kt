package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import java.io.OutputStream

/** Synchronous, in-memory [SchoolMailRepository] for ViewModel tests. `beforeSeq` is an offset token here. */
class FakeSchoolMailRepository : SchoolMailRepository {
    private val _changes = MutableSharedFlow<MailChange>(extraBufferCapacity = 64)
    override val changes: SharedFlow<MailChange> = _changes.asSharedFlow()

    /** The account a write is told under, read as the real repository does: before the write, through [accountGeneration]. */
    private fun generation(): Int = accountGeneration?.invoke() ?: 0

    /** Set, [setSeen] waits on it after reading the account: the write held on the connection while the test acts. */
    var seenGate: CompletableDeferred<Unit>? = null

    /**
     * Set, [move] and [delete] wait on it once sent, as [seenGate] holds [setSeen]: the command out
     * on the connection, its answer held while the test acts.
     */
    var writeGate: CompletableDeferred<Unit>? = null

    /**
     * Set, [move] and [delete] wait on it before their `stillWanted` is asked: the write queued
     * behind the session another request holds, not sent yet.
     */
    var writeQueueGate: CompletableDeferred<Unit>? = null

    /** Set, [deletesPermanently] waits on it first: the CREATE and LIST a missing trash costs, held out while the test acts. */
    var trashGate: CompletableDeferred<Unit>? = null

    /** How many times [deletesPermanently] went to the server; one refused by its `stillWanted` is not counted. */
    var trashQuestionsSent = 0

    /**
     * Set, [loadPage] waits on it once its page is built, after [onLoaded]: the answer held on its
     * way up, as the real one is while its cache write runs, while the test acts -- and what it
     * does then is not in the page.
     */
    var answerGate: CompletableDeferred<Unit>? = null

    var resolved = ResolvedFolders(SpecialFolder.entries.associateWith { it.decodedName }, listOf("Moodle 課程討論區"))
    val mail = mutableMapOf<String, MutableList<MailSummary>>()
    var pageSize = 50
    var status = FolderStatus(1, 1, 0, 0)

    /** Thrown by [inboxStatus] -- the first call the page poll makes. */
    var statusError: MailError? = null
    var statusCalls = 0

    /** Set, every [inboxStatus] waits on it first: a poll's check held out while the test acts. */
    var statusGate: CompletableDeferred<Unit>? = null

    /** Set, every [status] waits on it first: a STATUS queued behind the session another request holds. */
    var folderStatusGate: CompletableDeferred<Unit>? = null

    /** Set, every foreground [loadPage] waits on it first: a load held out while the test acts. */
    var loadGate: CompletableDeferred<Unit>? = null

    /**
     * The account a page is fetched for, when a test wires it to [MailAccount.accountGeneration]:
     * as the real repository ([org.ntust.app.tigerduck.mail.store.MailCache.saveFolderIf]), a first
     * page whose account moved while it was out is not cached. Unwired, every first page is.
     */
    var accountGeneration: (() -> Int)? = null

    /** Set, every [folders] waits on it first: the folder list's LIST held out while the test acts. */
    var foldersGate: CompletableDeferred<Unit>? = null

    /** How many times the folder list went out; one refused by its `stillWanted` is not counted. */
    var foldersSent = 0

    /**
     * The gates above stand for the wait behind the session; a request whose `stillWanted` says
     * no once its gate opens is not sent, as the real repository's is not, and ends the same way.
     */
    private fun unlessAbandoned(stillWanted: () -> Boolean) {
        if (!stillWanted()) throw MailError.Abandoned()
    }

    /** Every page request that went out, in order; one refused by its `stillWanted` is not here. */
    val loadsSent = mutableListOf<String>()

    /** Every search that went out, in order; one refused by its `stillWanted` is not here. */
    val searchesSent = mutableListOf<String>()

    /** Fires with the folder just after its page was produced and before it is returned: a test acts between two requests of one batch. */
    var onLoaded: ((String) -> Unit)? = null

    /** As [onLoaded], for a search. */
    var onSearched: ((String) -> Unit)? = null

    /** Thrown by [loadPage] for that folder alone, where [loadError] fails every folder. */
    val folderLoadErrors = mutableMapOf<String, MailError>()

    /** Thrown by [status] for that folder -- the poll's check of a folder on screen other than the inbox. */
    val folderStatusErrors = mutableMapOf<String, MailError>()
    val folderStatusCalls = mutableListOf<String>()
    var loadError: MailError? = null
    /** When set, [loadError] is thrown for this folder alone: one folder's page lands and the next one's does not. */
    var loadErrorFolder: String? = null
    /** The UIDVALIDITY [loadPage] reports for its pages, and so caches them under; raised when the mailbox is "recreated". */
    var pageValidity = 1L
    var searchUnsupported = false
    val bodies = mutableMapOf<Long, MailBody>()
    var bodyError: MailError? = null
    /**
     * Every `(folder, uid)` handed to [body], in order -- recorded before [bodyError] fires, so a
     * test can tell "the fetch was refused" apart from "the fetch was never attempted".
     */
    val bodyCalls = mutableListOf<Pair<String, Long>>()
    /** What was asked for inside an [ahead] block, in order: "summary" and "body". */
    val askedAhead = mutableListOf<String>()
    private var aheadDepth = 0
    var moveError: MailError? = null
    var deleteError: MailError? = null
    /** Thrown by [setSeen] before it records anything, so a refused mark-read leaves no trace. */
    var seenError: MailError? = null
    val seenCalls = mutableListOf<Triple<String, Long, Boolean>>()
    val moved = mutableListOf<Triple<String, Long, String>>()

    /** The UID [move] files the next mail under in its target, well above any a test adds. */
    private var movedUids = 90_000L
    val deleted = mutableListOf<Pair<String, Long>>()

    /**
     * Every folder name handed to any folder-taking call, in order. A merged view has no folder
     * of its own, so nothing here may ever be a name the server would not recognise -- this is
     * what a test asserts against to prove no synthetic name reaches an IMAP command.
     */
    val foldersTouched = mutableListOf<String>()
    /** Mirrors the real repository's cache: [loadPage]'s first page populates it, [dropCache] simulates a [MailError.FolderChanged] eviction. */
    private val cachedPages = mutableMapOf<String, MailPage>()
    /** Every `(folder, limit)` handed to [loadPage], in order -- what a prefetch test checks against. */
    val pageLimits = mutableListOf<Pair<String, Int>>()
    /**
     * Thrown only by a [loadPage] call marked `background` -- i.e. a
     * background prefetch, never the visible folder's own load.
     */
    var prefetchError: MailError? = null
    /**
     * While true, a background [loadPage] call suspends
     * instead of returning, so a test can assert what a cancel does to it mid-flight. Cancellable:
     * it is a plain suspending await, not a busy loop, so cancelling the caller's job ends it.
     */
    var blockPrefetch: Boolean = false
        set(value) {
            field = value
            if (!value) prefetchGate?.complete(Unit)
        }
    private var prefetchGate: CompletableDeferred<Unit>? = null
    /** How many [loadPage] calls are open right now -- incremented on entry, decremented in `finally`. */
    private var openLoads = 0
    /**
     * The largest [openLoads] ever observed. A sequential prefetch never lets a second
     * [loadPage] start before the one ahead of it returns, so this must stay 1 across a whole
     * prefetch run; a fan-out (e.g. `async` per folder) would let two calls sit inside
     * [loadPage] at once -- which, combined with [blockPrefetch] parking the first one open,
     * is exactly what this catches.
     */
    var maxConcurrentLoads = 0
        private set
    val sent = mutableListOf<Pair<OutgoingMail, Pair<String, Long>?>>()
    val drafts = mutableListOf<Pair<OutgoingMail, Long?>>()
    val discardedDrafts = mutableListOf<Long>()
    val sentAttachments = mutableListOf<List<Pair<String, String>>>()
    var sendError: MailError? = null
    /** What [send] reports about the sent copy; the default is the happy path. */
    var sentCopy: SentCopy = SentCopy.Filed
    /** Any [Throwable], not just [MailError] -- lets a test inject a [kotlinx.coroutines.CancellationException] too. */
    var discardDraftError: Throwable? = null
    var self = MailAddress("測試", "b10000001@mail.ntust.edu.tw")
    var raw = "Subject: x\r\n\r\nraw"
    var writeAttachmentError: MailError? = null
    /**
     * Written through [writeAttachment]'s own stream just before [writeAttachmentError] is thrown:
     * a download that fails part-way, having already put bytes where the caller pointed it -- what
     * an attachment refused at [org.ntust.app.tigerduck.mail.MailLimits.ATTACHMENT_BYTES] does.
     */
    var writeAttachmentPartial: String? = null
    /** Fires synchronously at the start of every [writeAttachment] call, before any error/write -- for tests that need to observe state mid-download. */
    var onWriteAttachment: (() -> Unit)? = null
    /** Fires synchronously at the start of every [body] call, before any error/return -- for tests that need to observe state while the body is still loading. */
    var onBody: (() -> Unit)? = null
    var released = 0
    var acquired = 0

    /**
     * What an older page ([loadPage] with a `beforeSeq`) returns instead of the offset slice, when
     * set -- for a test that needs the server to answer a window the list already holds part of,
     * the way an IMAP sequence range does once mail below it has been expunged.
     */
    var olderPage: ((folder: String, beforeSeq: Int) -> MailPage?)? = null

    /** While set, an older page ([loadPage] with a `beforeSeq`) suspends on it: a page still on its way. */
    var olderPageGate: CompletableDeferred<Unit>? = null

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

    /** Thrown by [folders] -- the first call a list load makes -- once the list went out. */
    var foldersError: MailError? = null

    /** How many times the folder list was asked for, counted before the wait on [foldersGate]; [foldersSent] is what went out. */
    var foldersCalls = 0

    /** Fires once the folder list went out and before it is returned: a test acts while the LIST is out. */
    var onFolders: (() -> Unit)? = null

    /** Set, a folder list that went out waits on it before it answers: a LIST still out, for as long as the test needs. */
    var foldersAnswerGate: CompletableDeferred<Unit>? = null

    override suspend fun folders(stillWanted: () -> Boolean): ResolvedFolders {
        foldersCalls++
        foldersGate?.await()
        unlessAbandoned(stillWanted)
        foldersSent++
        onFolders?.invoke()
        foldersAnswerGate?.await()
        foldersError?.let { throw it }
        return resolved
    }
    /** Set, [cachedPage] waits on it with the page already read: the disk read held while the test acts, as a write that lands meanwhile is not in what it read. */
    var cachedGate: CompletableDeferred<Unit>? = null

    override suspend fun cachedPage(folder: String): MailPage? {
        val page = touching(folder) { cachedPages[folder] }
        cachedGate?.await()
        return page
    }

    /** Simulates the repository dropping a folder's cache once [MailError.FolderChanged] fires for it, and saying so. */
    fun dropCache(folder: String) {
        cachedPages.remove(folder)
        _changes.tryEmit(MailChange.Changed(folder, generation()))
    }

    /** Every `(folder, obsolete validity)` handed to [dropCachedPage], in order. */
    val droppedCaches = mutableListOf<Pair<String, Long>>()

    /** Fires inside [dropCachedPage], before the page goes: the eviction is a disk write a test acts during. */
    var onDropCachedPage: ((String) -> Unit)? = null

    override suspend fun dropCachedPage(folder: String, obsoleteValidity: Long) {
        onDropCachedPage?.invoke(folder)
        foldersTouched += folder
        droppedCaches += folder to obsoleteValidity
        // Mirrors MailCache.deleteFolderUnder: a page of any other generation stays.
        // The list's own eviction, which the real repository tells nobody of (unlike [dropCache]).
        if (cachedPages[folder]?.uidValidity == obsoleteValidity) cachedPages.remove(folder)
    }

    override suspend fun loadPage(folder: String, beforeSeq: Int?, limit: Int, background: Boolean, stillWanted: () -> Boolean): MailPage {
        openLoads++
        maxConcurrentLoads = maxOf(maxConcurrentLoads, openLoads)
        try {
            if (!background) loadGate?.await()
            unlessAbandoned(stillWanted)
            val generation = accountGeneration?.invoke()
            foldersTouched += folder
            pageLimits += folder to limit
            loadsSent += folder
            if (background) {
                prefetchError?.let { throw it }
                if (blockPrefetch) {
                    val gate = CompletableDeferred<Unit>()
                    prefetchGate = gate
                    gate.await()
                }
            }
            loadError?.takeIf { loadErrorFolder == null || loadErrorFolder == folder }?.let { throw it }
            folderLoadErrors[folder]?.let { throw it }
            if (beforeSeq != null) olderPageGate?.await()
            if (beforeSeq != null) olderPage?.invoke(folder, beforeSeq)?.let { return it }
            val all = sorted(folder)
            val from = beforeSeq ?: 0
            // `limit` is honoured, not just recorded: the real repository's rule about a warm
            // never shortening a cache only means anything if a warm can return a shorter page.
            val chunk = all.drop(from).take(minOf(pageSize, limit))
            val page = MailPage(pageValidity, all.size, chunk, (from + chunk.size).takeIf { it < all.size })
            // The page is back; a test acts here as it would while the real fetch was still out.
            onLoaded?.invoke(folder)
            answerGate?.await()
            // Mirrors MailRepository.loadPage: a first page replaces the cache, except for a
            // background warm that would leave less of the same mailbox behind than is there --
            // and except for an account that moved while the page was out ([accountGeneration]).
            if (beforeSeq == null && generation == accountGeneration?.invoke()) {
                val cached = cachedPages[folder]
                val shortens = background && cached != null &&
                    cached.uidValidity == page.uidValidity && cached.messages.size > page.messages.size
                if (!shortens) cachedPages[folder] = page
            }
            return page
        } finally {
            openLoads--
        }
    }

    override suspend fun inboxStatus(stillWanted: () -> Boolean): FolderStatus {
        statusGate?.await()
        unlessAbandoned(stillWanted)
        statusCalls++
        statusError?.let { throw it }
        return status
    }

    override suspend fun status(folder: String, stillWanted: () -> Boolean): FolderStatus {
        folderStatusGate?.await()
        unlessAbandoned(stillWanted)
        foldersTouched += folder
        folderStatusCalls += folder
        folderStatusErrors[folder]?.let { throw it }
        val newest = sorted(folder).firstOrNull()?.uid ?: 0L
        return FolderStatus(status.uidValidity, newest + 1, mail[folder]?.size ?: 0, 0)
    }
    override suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags> =
        touching(folder) { sorted(folder).filter { it.uid in uids }.associate { it.uid to it.flags } }
    override suspend fun <T> ahead(block: suspend () -> T): T {
        aheadDepth++
        try {
            return block()
        } finally {
            aheadDepth--
        }
    }

    override suspend fun summary(folder: String, uid: Long) = touching(folder) {
        if (aheadDepth > 0) askedAhead += "summary"
        sorted(folder).firstOrNull { it.uid == uid }
    }
    override suspend fun body(folder: String, uid: Long): MailBody {
        foldersTouched += folder
        bodyCalls += folder to uid
        if (aheadDepth > 0) askedAhead += "body"
        onBody?.invoke()
        bodyError?.let { throw it }
        return bodies[uid] ?: throw MailError.Protocol("gone")
    }

    override suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        val generation = generation()
        seenGate?.await()
        seenError?.let { throw it }
        foldersTouched += folder
        seenCalls += Triple(folder, uid, seen)
        update(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
        _changes.emit(MailChange.Seen(folder, uid, seen, generation))
    }

    override suspend fun move(folder: String, uid: Long, target: String, uidValidity: Long?, stillWanted: () -> Boolean) {
        val generation = generation()
        writeQueueGate?.await()
        unlessAbandoned(stillWanted)
        refuseUnder(uidValidity)
        writeGate?.await()
        foldersTouched += folder
        foldersTouched += target
        moveError?.let { throw it }
        moved += Triple(folder, uid, target)
        val moving = mail[folder]?.firstOrNull { it.uid == uid }
        mail[folder]?.removeAll { it.uid == uid }
        // Filed in the target under a UID of the target's own, as the server does: what a list of
        // the target can only fetch ([MailChange.Changed] below).
        moving?.let { mail.getOrPut(target) { mutableListOf() } += it.copy(uid = movedUids++) }
        _changes.emit(MailChange.Removed(folder, uid, generation))
        _changes.emit(MailChange.Changed(target, generation))
    }

    /**
     * As the server answers a write's SELECT: a UID read under another UIDVALIDITY than the
     * folders have now ([pageValidity]) names another mail, and nothing is sent.
     */
    private fun refuseUnder(uidValidity: Long?) {
        if (uidValidity != null && uidValidity != pageValidity) {
            writesRefused++
            throw MailError.FolderChanged()
        }
    }

    /** Fires inside [delete], before [deleteError]: a test acts while the delete is out. */
    var onDelete: (() -> Unit)? = null

    /** How many writes [refuseUnder] turned away: the server, not the caller, deciding a UID is stale. */
    var writesRefused = 0

    /** Mirrors the real repository: permanent inside the trash, and permanent everywhere when the account has no trash folder and none could be created. */
    override suspend fun deletesPermanently(folder: String, stillWanted: () -> Boolean): Boolean {
        trashGate?.await()
        unlessAbandoned(stillWanted)
        trashQuestionsSent++
        return touching(folder) { resolved.nameOf(SpecialFolder.TRASH).let { it == null || it == folder } }
    }

    override suspend fun delete(folder: String, uid: Long, uidValidity: Long?, stillWanted: () -> Boolean) {
        val generation = generation()
        writeQueueGate?.await()
        unlessAbandoned(stillWanted)
        refuseUnder(uidValidity)
        writeGate?.await()
        foldersTouched += folder
        onDelete?.invoke()
        deleteError?.let { throw it }
        deleted += folder to uid
        mail[folder]?.removeAll { it.uid == uid }
        _changes.emit(MailChange.Removed(folder, uid, generation))
    }

    /** Thrown by [search] for a folder, after it is recorded as touched and sent. */
    var searchError: MailError? = null

    /**
     * When set, [searchError] is thrown for this folder alone: one folder answers the search and
     * the next cannot be reached, the way a connection lost midway through All mail looks.
     */
    var searchErrorFolder: String? = null

    /** The UIDVALIDITY a server search reports its matches under; null for a server that does not say. */
    var searchValidity: Long? = 1

    /** How many times [search] went out, failed or not; [searchesSent] names them in order. */
    var searchCalls = 0

    /** While set, [search] suspends on it once the SEARCH went out, before it answers or fails: a search still out while the test acts. */
    /** Whether [search] says the index held every mail of the folder, as its outcome's [SearchOutcome.Server.complete]. */
    var searchComplete = true

    var searchGate: CompletableDeferred<Unit>? = null

    override suspend fun search(folder: String, query: String, stillWanted: () -> Boolean): SearchOutcome {
        unlessAbandoned(stillWanted)
        foldersTouched += folder
        searchCalls++
        searchesSent += folder
        searchGate?.await()
        searchError?.takeIf { searchErrorFolder == null || searchErrorFolder == folder }?.let { throw it }
        val hits = sorted(folder).filter { query in it.subject }
        onSearched?.invoke(folder)
        // The server could not search: as the real repository, only the folder's cached newest
        // page is searched here; the list page substitutes the rows it has loaded.
        if (searchUnsupported) {
            return cachedPages[folder].let { cached -> SearchOutcome.LoadedOnly(cached?.messages.orEmpty().filter { query in it.subject }, cached?.uidValidity) }
        }
        // Paged like the real repository: the newest [pageSize] matches, the rest left to fetch.
        return SearchOutcome.Server(hits.take(pageSize), hits.drop(pageSize).map { it.uid }, uidValidity = searchValidity, complete = searchComplete)
    }

    /** Every `(folder, uids)` handed to [searchPage], in order -- asked for, whether or not it then went out. */
    val searchPages = mutableListOf<Pair<String, List<Long>>>()

    /** Thrown by [searchPage], after the call is recorded in [searchPages]. */
    var searchPageError: MailError? = null

    /**
     * While set, [searchPage] suspends on it after recording the call, so a test can change
     * things -- clear the search, sign out -- while a page of results is still on its way.
     */
    var searchPageGate: CompletableDeferred<Unit>? = null

    /** The UIDVALIDITY [searchPage] reports; the search itself reports [searchValidity]. */
    var searchPageValidity = 1L

    /** How many times the search index was asked to catch up with the server. */
    var searchIndexUpdates = 0

    override suspend fun updateSearchIndex(stillWanted: () -> Boolean) {
        searchIndexUpdates++
    }

    override val searchIndexProgress = MutableStateFlow<MailIndexProgress?>(null)

    /** What the mail downloaded for search takes, as the settings page reads it. */
    var searchIndexBytes = 0L

    override suspend fun searchIndexBytes(): Long = searchIndexBytes

    /** What [correspondence] answers. */
    var correspondence: List<MailSummary> = emptyList()

    override suspend fun correspondence(): List<MailSummary> = correspondence

    var searchIndexDeletes = 0

    override suspend fun deleteSearchIndex() {
        searchIndexDeletes++
        searchIndexBytes = 0L
    }

    override suspend fun searchPage(folder: String, uids: List<Long>, stillWanted: () -> Boolean): SearchPage {
        foldersTouched += folder
        searchPages += folder to uids
        searchPageGate?.await()
        unlessAbandoned(stillWanted)
        searchPageError?.let { throw it }
        return SearchPage(searchPageValidity, sorted(folder).filter { it.uid in uids })
    }

    var rawSourceError: MailError? = null
    var rawSourceCalls = 0

    /** Fires inside [rawSource], before [rawSourceError]: a test acts while the fetch is out. */
    var onRawSource: (() -> Unit)? = null

    override suspend fun rawSource(folder: String, uid: Long): String {
        foldersTouched += folder
        rawSourceCalls++
        onRawSource?.invoke()
        rawSourceError?.let { throw it }
        return raw
    }

    override suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) {
        foldersTouched += folder
        onWriteAttachment?.invoke()
        writeAttachmentError?.let { error ->
            writeAttachmentPartial?.let { out.write(it.toByteArray()) }
            throw error
        }
        out.write("attachment $partId".toByteArray())
    }

    /** Runs at the start of every [send], before [sendError] -- for a test that changes things while a mail goes out. */
    var onSend: (() -> Unit)? = null

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?): SentCopy {
        onSend?.invoke()
        sendError?.let { throw it }
        sentAttachments += mail.attachments.map { it.fileName to it.open().use { s -> s.readBytes().decodeToString() } }
        sent += mail to answered
        if (sentCopy == SentCopy.Filed) resolved.nameOf(SpecialFolder.SENT)?.let { _changes.emit(MailChange.Changed(it, generation())) }
        return sentCopy
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        drafts += mail to replacingUid
        resolved.nameOf(SpecialFolder.DRAFTS)?.let { folder ->
            _changes.emit(MailChange.Changed(folder, generation()))
            if (replacingUid != null) _changes.emit(MailChange.Removed(folder, replacingUid, generation()))
        }
    }

    override suspend fun discardDraft(uid: Long) {
        discardDraftError?.let { throw it }
        discardedDrafts += uid
        resolved.nameOf(SpecialFolder.DRAFTS)?.let { _changes.emit(MailChange.Removed(it, uid, generation())) }
    }

    override fun selfAddress() = self
    override fun release() {
        released++
    }

    override fun acquire() {
        acquired++
    }

    /** How many times [downloadNow] was asked for. */
    var downloadsAsked = 0

    override fun downloadNow() {
        downloadsAsked++
    }
}
