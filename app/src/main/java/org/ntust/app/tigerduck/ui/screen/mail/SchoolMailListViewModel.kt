package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailChange
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.MailRepository
import org.ntust.app.tigerduck.mail.MailSite
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.SearchOutcome
import org.ntust.app.tigerduck.mail.SentCopyNotice
import org.ntust.app.tigerduck.mail.SentCopyNotices
import org.ntust.app.tigerduck.mail.SentCopyOrigin
import org.ntust.app.tigerduck.mail.matchesLoadedMail
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailRow
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.sync.MailClock
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@HiltViewModel
class SchoolMailListViewModel @Inject constructor(
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val checker: MailChecker,
    private val site: MailSite,
    private val clock: MailClock,
    private val sentCopyNotices: SentCopyNotices,
) : ViewModel() {

    /** The account's own domain: what the list's External badge measures a sender against. */
    val mailDomain: String get() = site.domain()

    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val error: MailError) : LoadState
    }

    /** [kind] is null for All mail, which is no server folder and so has no [SpecialFolder]. */
    data class FolderChip(val selection: FolderSelection, val kind: SpecialFolder?) {
        val key: String get() = kind?.name ?: "all"
    }

    /**
     * [row]'s delete, asked before it is made: [permanently] when it destroys the mail rather than
     * moving it to the trash ([SchoolMailRepository.deletesPermanently]), which asks the harder
     * question. [generation] is the [MailAccount.accountGeneration] it was asked under; see [write].
     */
    data class PendingDelete(val row: MailRow, val permanently: Boolean, val generation: Int)

    /** A folder a mail can be moved to; [kind] when it is a special one, which is shown by its own name. */
    data class MoveTarget(val folder: String, val kind: SpecialFolder?)

    /** [row]'s move, waiting on which of [targets] it goes to; [generation] as on [PendingDelete]. */
    data class PendingMove(val row: MailRow, val targets: List<MoveTarget>, val generation: Int)

    data class UiState(
        val chips: List<FolderChip> = emptyList(),
        val others: List<String> = emptyList(),
        /**
         * Defaults to All mail: it is not a real folder name, so this can never spuriously match
         * a chip [load] resolves and get "kept" by accident (see [load]'s `keep`) -- it only ever
         * survives past the first [load] when the merge actually exists. When it doesn't, `keep`
         * falls through to the real Inbox folder, same as before All mail existed.
         */
        val selected: FolderSelection = FolderSelection.AllMail,
        /** The server names of the folders All mail merges. Empty until [load] has resolved them. */
        val mergedFolders: List<String> = emptyList(),
        val messages: List<MailRow> = emptyList(),
        /**
         * Only ever describes a *load*: [load], [fetchFirstPage], [paginate]. A failed search, like a
         * failed one-off action, reports through [actionError] instead, so it can neither paint
         * the header dot red for the rest of the session nor replace a list that loaded perfectly
         * well with the "couldn't load" empty state. A folder a search finds recreated is read
         * again, though ([reloadRecreated]): that is a load of the list, and its failure is one.
         */
        val loadState: LoadState = LoadState.Idle,
        /**
         * A transient failure of something other than a load -- a mark-read that did not stick,
         * one flaky minute of the poll -- shown once as a toast and then cleared, the way the
         * message screen has always handled its own actions.
         */
        val actionError: MailError? = null,
        /**
         * Per real folder, the sequence number to page back from; a folder drops out of the map
         * once the server says it has nothing older. Each folder has its own UID space, so "the
         * next 50" has no cross-folder meaning and one shared cursor would be a fiction.
         */
        val cursors: Map<String, Int> = emptyMap(),
        val isPaginating: Boolean = false,
        val unreadOnly: Boolean = false,
        val searchText: String = "",
        val searchResults: List<MailRow>? = null,
        val searchLocalOnly: Boolean = false,
        /**
         * The latest search ran before every mail of a folder it searched was downloaded, and may
         * have missed older mail ([SearchOutcome.Server.complete]): so until the search runs again.
         */
        val searchIndexPartial: Boolean = false,
        /** A search under way: one [runSearch] that has not ended, and is still the latest. */
        val isSearching: Boolean = false,
        /** A further page of the latest search's matches on its way ([loadMoreSearchResults]). */
        val isPagingSearch: Boolean = false,
        /**
         * When the folders on screen last had their newest page fetched -- for All mail the older
         * of its two -- or 0 while one of them never has. For the sync dot's "Last synced" row;
         * see [MailChecker.fetchedAt].
         */
        val fetchedAt: Long = 0L,
        /** A delete waiting on its confirmation; [confirmDelete] answers it. */
        val pendingDelete: PendingDelete? = null,
        /** A mail waiting on the folder it is moved to; [moveTo] picks it, [dismissMove] drops it. */
        val pendingMove: PendingMove? = null,
        /**
         * The rows, by [MailRow.key], whose delete or move is still out. Such a row stays on
         * screen until the server is done -- a STATUS, COPY, STORE and EXPUNGE away -- and
         * another swipe at it meanwhile would queue a second write behind the first: a second
         * COPY that files the mail twice, or a "gone" error for a mail already deleted. So
         * nothing more is asked of it until its write ends ([write]), and the card says so.
         */
        val writing: Set<String> = emptySet(),
    ) {
        /** A search under way, or a further page of its matches on its way: the search field spins. */
        val searchBusy: Boolean get() = isSearching || isPagingSearch

        val displayed: List<MailRow>
            get() = (searchResults ?: messages).let { list -> if (unreadOnly) list.filter { !it.summary.flags.seen } else list }

        val selectedKind: SpecialFolder? get() = chips.firstOrNull { it.selection == selected }?.kind

        /** The kind of one row's *own* folder -- what decides where tapping it goes, in every view. */
        fun kindOf(folder: String): SpecialFolder? =
            chips.firstOrNull { (it.selection as? FolderSelection.Real)?.name == folder }?.kind

        /** The resolved server name behind the Inbox chip, or null before [load] has resolved it. */
        val inboxFolder: String?
            get() = (chips.firstOrNull { it.kind == SpecialFolder.INBOX }?.selection as? FolderSelection.Real)?.name

        /**
         * True whenever the inbox's own mail is genuinely on screen: viewing Inbox directly, or
         * viewing All mail, which always merges Inbox in whenever it exists as a chip at all (see
         * [FolderSelection.AllMail.MERGED] and the `merged.size > 1` guard in [load]). Everything
         * that used to read "only do this for the inbox" -- new-mail polling, moving the
         * notification seen marker -- reads this instead, so defaulting to All mail costs the user
         * none of that.
         */
        val inboxInView: Boolean get() = selectedKind == SpecialFolder.INBOX || selected == FolderSelection.AllMail

        /**
         * The real folders the current selection reads. Every repository call goes through this,
         * so no synthetic name can reach the server: All mail resolves to the folders it merges,
         * and a selection that resolves to nothing simply reads nothing.
         */
        val targets: List<String>
            get() = when (val selection = selected) {
                is FolderSelection.Real -> listOf(selection.name)
                FolderSelection.AllMail -> mergedFolders
            }
    }

    val signedIn: StateFlow<Boolean> = account.signedIn

    /** How far the download of every mail into the search index has come; null when none is under way. */
    val searchIndexProgress: StateFlow<MailIndexProgress?> = repository.searchIndexProgress
    val authFailed: StateFlow<Boolean> = account.authFailed

    /** The user closed the sign-in-failed banner; cleared each time [authFailed] changes. */
    private val authBannerClosed = MutableStateFlow(false)

    /**
     * Whether the sign-in-failed banner shows: while the server rejects the password, until it is
     * closed. It comes back with the next change of [authFailed] -- a new sign-in that fails
     * again, or the next rejection -- as on the iPhone. Closing it changes nothing about the account.
     */
    val showAuthFailedBanner: StateFlow<Boolean> =
        combine(account.authFailed, authBannerClosed) { failed, closed -> failed && !closed }
            .stateIn(viewModelScope, SharingStarted.Eagerly, account.authFailed.value)

    fun dismissAuthFailedBanner() {
        authBannerClosed.value = true
    }

    /**
     * A new mail or a draft sent from compose went out without its copy reaching the sent folder
     * (a reply's notice is the replied mail's to show, not this list's). Kept out of
     * [UiState] on purpose: no load clears it, only [dismissSentCopyNotice] (or signing out), so
     * the fetch that can follow the compose screen closing cannot wipe it before it is seen.
     */
    val sentCopyNotice: StateFlow<SentCopyNotice?> = sentCopyNotices.noticeFor(SentCopyOrigin.List, viewModelScope)

    fun dismissSentCopyNotice() = sentCopyNotices.dismiss()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var prefetchJob: Job? = null

    /** The [load] in progress, so a second call -- Retry tapped twice, a pull -- waits on it instead of starting another. */
    private var loadJob: Job? = null

    /** The [MailAccount.accountGeneration] that [loadJob] loads for; null until the first [load]. */
    private var loadGeneration: Int? = null

    /**
     * Per folder, [changeSeq] as of the last change that left this list's rows of it in doubt: a
     * write made on another screen that only a fetch can show ([MailChange.Changed]), or any
     * change that arrived while a page of the folder was out ([refetchIfChangedSince]). Such a
     * folder is fetched again -- at once when it is on screen as a page lands, otherwise the next
     * time the page appears ([loadIfNeeded]); a chip tap fetches anyway. The first page that
     * lands for it takes it out, unless it was marked again while that page was out.
     */
    private val staleFolders = mutableMapOf<String, Int>()

    /** Numbers every change [apply] takes, in order, so a page that was out while one arrived can tell. */
    private var changeSeq = 0

    /** Per folder, [changeSeq] as of the last change [apply] took for it, of any kind. */
    private val changedAt = mutableMapOf<String, Int>()

    /**
     * Where [paginate] collects the changes that arrive while its older page is out; null when
     * none is. A first page is fetched again after such a change ([refetchIfChangedSince]); an
     * older page is not, since that fetch replaces the whole list and the user's place in it,
     * so the change is applied to the page's rows instead ([pageWith]) -- only the changes from
     * when that folder's own request started until the batch lands, so there is nothing to cap.
     */
    private var olderPageChanges: MutableList<MailChange>? = null

    /**
     * True while a load asked for by another account than [loadJob]'s waits for that one to end:
     * a student who signed in over the one whose folder list was still out -- through the sign-in
     * sheet a rejected password shows -- and whose screen asked for its load at once. The single
     * flight refuses that call, the old load then comes back for nobody, and nothing else would
     * start theirs: the screen's load effect does not run again, and polling resolves no folders.
     * So it starts as soon as the old one has ended.
     */
    private var loadPending = false

    /**
     * True once a warm queue has run to the end, so a resume knows there is nothing left to warm.
     * Reset whenever a new queue is built, and whenever the account goes away.
     */
    private var prefetchDone = false

    /**
     * The server matches the last search has not fetched yet, per folder, newest first: what
     * scrolling to the end of the results fetches next ([loadMoreSearchResults]).
     */
    private var unfetchedMatches: Map<String, UnfetchedMatches> = emptyMap()

    /** The UIDVALIDITY the outcome's matches were read under, or null where nothing said. */
    private val SearchOutcome.version: Long?
        get() = when (this) {
            is SearchOutcome.Server -> uidValidity
            is SearchOutcome.LoadedOnly -> uidValidity
        }

    /** A folder's matches still to fetch, and the UIDVALIDITY they were found under ([SearchOutcome.Server.uidValidity]). */
    private data class UnfetchedMatches(val uidValidity: Long?, val uids: List<Long>)

    /**
     * Bumped whenever [UiState.messages] and [UiState.cursors] are replaced wholesale -- a first
     * page, the cached page, another folder, a sign-out -- so an older page asked for before is
     * dropped rather than merged: its cursor belonged to the list that was replaced.
     */
    private var listGeneration = 0

    /**
     * Each real folder's UIDVALIDITY as its last first page gave it -- the server's, or the cached
     * one painted while the server is asked ([showCached]). Every cursor in [UiState.cursors] was
     * taken under the validity recorded here for its folder, so [paginate] can tell an older page
     * from a recreated folder apart from one of the mailbox the list is showing.
     */
    private var folderValidity: Map<String, Long> = emptyMap()

    /**
     * Bumped by every search submitted or cleared, so a page of results still on its way for an
     * earlier query is dropped rather than added to this one's.
     */
    private var searchGeneration = 0

    // Whether the user has scrolled the search on screen past its first pages; a silent run of
    // it again would take those rows away. Main-thread-only, as searchGeneration.
    private var searchPaged = false

    // The query of the search on screen, as the user submitted it; main-thread-only.
    private var submittedQuery: String? = null

    // The [searchGeneration] of the quiet run of the search still out, if any, and the last row the
    // screen reached meanwhile. That run has put the matches still to fetch aside, and the screen
    // asks again only once its last row changes, so the page is served when the run lands.
    private var quietSearch: Int? = null
    private var pageAskedWhileQuiet: MailRow? = null

    /**
     * The question [delete] or [requestMove] is still finding out from the server: which of the
     * two delete questions to ask, or which folders a mail can go to. One question at a time
     * ([mayAsk]): a second swipe while this one is out would otherwise replace its question, and
     * the first delete be dropped without a word.
     */
    private var asking: Job? = null

    /**
     * Bumped by [forgetAccount], which empties [UiState.writing] with the rest of the state. A
     * write that outlives that leaves [UiState.writing] alone at its end: the row of the same key
     * there now, if any, is held by a write of whoever signed in since. Any other move of the
     * account, the same student signing in again, resets nothing, and the write gives its row
     * back as ever.
     */
    private var accountsForgotten = 0

    /**
     * When the folders on screen were last looked at, by [clock]: a first page that landed, or a
     * poll that ran to the end. What a return's own look at the inbox is decided against
     * ([pollsOnResume]).
     */
    private var lookedAt: Long? = null

    /**
     * Per folder whose rows on screen are still the ones [showCached] painted, the date the folder
     * had then. A load that failed leaves those rows up, and they are a previous visit's, flags
     * and deletions included, so only a first page from the server that replaces them dates the
     * folder again ([fetchFirstPage]): not the poll, whose STATUS tells only whether mail arrived,
     * and not a [MailChecker] check that moves the inbox's stored date meanwhile, which the row
     * does not show over them. As on the iPhone.
     */
    private val cachePainted = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch { account.authFailed.collect { authBannerClosed.value = false } }
        viewModelScope.launch { repository.changes.collect(::apply) }
        // While mail downloads for search, a search on screen runs again as it comes in, at most
        // every [SEARCH_REGROW_MS], so its results grow rather than wait for a new search; as the
        // iPhone's (tigerduck-app#894). Not over the user's own search, but after it; nor over a
        // page of it still out.
        viewModelScope.launch {
            // Each batch downloaded, and the end of every download, for one last run over the last
            // batch: a StateFlow may hand over only its last value, so a download whose batches
            // were never seen here still ends in a run.
            var seen: MailIndexProgress? = null
            repository.searchIndexProgress
                .transform { progress ->
                    val before = seen
                    seen = progress
                    val grew = progress != null && progress.total != null && progress.done > 0
                    val ended = progress == null && before != null
                    if (grew || ended) emit(Unit)
                }
                .conflate()
                .collect {
                    // Mail that came while the user's own search was out may have come after it
                    // read the index, so it runs once more when that search ends. Not when another
                    // search began meanwhile: that one read the index after the mail came.
                    val search = searchGeneration
                    _state.first { !it.isSearching }
                    val s = _state.value
                    // Nor over pages of it the user has scrolled to, or the search paged by
                    // itself: run again, it would show only the first of each folder, taking away
                    // the rows being read. Nor while the user edits the query: the search on
                    // screen is the one they submitted.
                    if (search == searchGeneration && s.searchResults != null && !s.isPagingSearch && !searchPaged &&
                        s.searchText.trim() == submittedQuery
                    ) {
                        runSearch(keepShown = true)
                    }
                    delay(SEARCH_REGROW_MS)
                }
        }
        // Signing out must not leave the previous account's mail on screen for
        // whoever signs in next (spec §7.5).
        viewModelScope.launch {
            account.signedIn.collect { signedIn -> if (!signedIn) forgetAccount() }
        }
        // Nor may another student signing in over a rejected account, which never signs out:
        // their taps and swipes would act on their own mailbox with this one's UIDs.
        viewModelScope.launch { account.studentChanges.collect { forgetAccount() } }
    }

    private fun forgetAccount() {
        loadJob?.cancel()
        loadJob = null
        asking?.cancel()
        asking = null
        accountsForgotten++
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchDone = false
        forgetSearch()
        lookedAt = null
        staleFolders.clear()
        cachePainted.clear()
        changedAt.clear()
        listGeneration++
        folderValidity = emptyMap()
        _state.value = UiState()
    }

    /**
     * What the screen asks for as it appears. The list screen is composed again on the way back
     * from every mail, and used to load each time: one IMAP round trip per mail opened, against a
     * server that caps connections and answers "server busy" under load. The list it left behind
     * is still here, as on the iPhone, so this loads only when nothing has loaded for this
     * account -- first entry, a sign-in since, a sign-out that reset the screen -- tries again a
     * load that failed with nothing to show, which Retry would be the next tap for anyway, and
     * fetches the page again where a write on another screen changed a folder on screen in a way
     * only a fetch can show ([staleFolders]). Otherwise the list stays exactly as it was: what was
     * done to a mail meanwhile has been applied ([apply]), the poll keeps the inbox fresh, and a
     * pull is the user asking.
     */
    fun loadIfNeeded() {
        if (loadGeneration != account.accountGeneration) {
            load()
            return
        }
        val s = _state.value
        if (s.loadState is LoadState.Failed && s.messages.isEmpty()) {
            refresh()
        } else if (s.targets.any { it in staleFolders }) {
            refetch()
        }
    }

    /**
     * The newest page of the folders on screen again, and the search up over them run again, so
     * what a change reached is not left behind in the results. The same stop and restart of the
     * warm as [refresh], for the same reason.
     */
    private fun refetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        viewModelScope.launch {
            fetchFirstPage()
            if (_state.value.searchResults != null) runSearch()
            if (_state.value.loadState is LoadState.Loaded) startPrefetch()
        }
    }

    /**
     * A write made on another screen, applied to the list it comes back to, as the iPhone's list
     * is told (`markSeenLocally` / `removeLocally` there): the mail opened from this list read,
     * moved or deleted, a draft saved, a mail sent. What can be applied is applied here, at once
     * and without a fetch; a folder with mail only a fetch can describe is noted for the next
     * time it is on screen ([staleFolders]).
     *
     * Only a change of the account this list loaded for, and still the signed-in one. A write can
     * end after another student signed in over the one who made it -- held on the connection
     * behind other requests -- and by then its folder and UID name a mail of theirs, which this
     * list may well be showing. The write itself is not this list's business; its change is
     * nobody's.
     */
    private fun apply(change: MailChange) {
        if (change.generation != account.accountGeneration || change.generation != loadGeneration) return
        changeSeq++
        changedAt[change.folder] = changeSeq
        if (change !is MailChange.Changed) olderPageChanges?.add(change)
        when (change) {
            is MailChange.Seen -> updateMessage(MailRow.key(change.folder, change.uid)) { it.copy(flags = it.flags.copy(seen = change.seen)) }
            is MailChange.Removed -> removeMessage(MailRow.key(change.folder, change.uid))
            is MailChange.Changed -> staleFolders[change.folder] = changeSeq
        }
    }

    /** The row [key] names is gone from the list and from the search results alike; see [apply]. */
    private fun removeMessage(key: String) = _state.update { st ->
        st.copy(messages = st.messages.filterNot { it.key == key }, searchResults = st.searchResults?.filterNot { it.key == key })
    }

    /**
     * Rows of [folders] were just installed from a request made while [changeSeq] stood at
     * [since]. A folder a change arrived for after that was read by the server before the write
     * -- or after another client's, which no change tells of -- so the rows just shown may have
     * put back what the change undid: a read flag, a mail moved away. Rather than second-guess
     * them, such a folder is marked stale and, when it is on screen, the folders on screen are
     * fetched again at once. Once: that fetch's own [since] is after the change, so it comes back
     * clean unless yet another arrives, and a burst of changes during one request costs one
     * fetch, not one per change. Only a mark made here starts that fetch -- an older one is the
     * next appearance's to answer ([loadIfNeeded]) -- so a server that keeps failing the page is
     * asked once per change, not again and again.
     */
    private fun refetchIfChangedSince(folders: Collection<String>, since: Int) {
        if (markChangedSince(folders, since)) refetch()
    }

    /** Marks stale each of [folders] a change arrived for after [since]; true when one of them is on screen. */
    private fun markChangedSince(folders: Collection<String>, since: Int): Boolean {
        val targets = _state.value.targets
        var onScreen = false
        for (folder in folders) {
            val changed = changedAt[folder] ?: continue
            if (changed <= since) continue
            staleFolders[folder] = changed
            if (folder in targets) onScreen = true
        }
        return onScreen
    }

    fun load() {
        if (!account.signedIn.value) return
        loadJob?.takeIf { it.isActive }?.let { running ->
            // Not discarded when it is another account's than the running load's; see [loadPending].
            if (!loadPending && loadGeneration != account.accountGeneration) {
                loadPending = true
                viewModelScope.launch {
                    running.join()
                    loadPending = false
                    load()
                }
            }
            return
        }
        showFetchedAt()
        val generation = account.accountGeneration
        loadGeneration = generation
        // At once, not when the first page is asked for: the folder list comes first, and a failed
        // state left up meanwhile is a Retry button that starts a second load.
        _state.update { it.copy(loadState = LoadState.Loading) }
        loadJob = viewModelScope.launch {
            try {
                // The first request of the load, and asked the same question as the rest (see
                // fetchFirstPage): a folder list that waited for the session across a sign-out
                // must not be fetched as the next account.
                val folders = repository.folders { account.accountGeneration == generation }
                // A LIST already out when another student signed in over this one comes back
                // under their generation. No sign-out reset the screen, so it is refused here;
                // their own load resolves their folders.
                if (account.accountGeneration != generation) {
                    loadDropped()
                    return@launch
                }
                val merged = FolderSelection.AllMail.MERGED.mapNotNull { folders.nameOf(it) }
                val real = SpecialFolder.entries.mapNotNull { kind ->
                    folders.nameOf(kind)?.let { FolderChip(FolderSelection.Real(it), kind) }
                }
                // All mail only earns a chip when there are at least two folders to merge;
                // otherwise it would just be a second name for the one that resolved. It leads
                // the row -- it is the default view, not an extra one tacked on at the end.
                val chips = if (merged.size > 1) listOf(FolderChip(FolderSelection.AllMail, null)) + real else real
                // Fallback once All mail isn't available (or the previous selection no longer
                // resolves): the real Inbox folder, same as the default before All mail existed.
                val inbox = FolderSelection.Real(folders.nameOf(SpecialFolder.INBOX) ?: "INBOX")
                val current = _state.value.selected
                val keep = when {
                    chips.any { it.selection == current } -> current
                    current is FolderSelection.Real && current.name in folders.others -> current
                    else -> inbox
                }
                _state.update { it.copy(chips = chips, others = folders.others, selected = keep, mergedFolders = merged) }
                // The folders on screen are known now, and so is their date.
                showFetchedAt()
            } catch (e: MailError) {
                if (dropped(e, generation)) loadDropped() else fail(e, generation)
                return@launch
            }
            showCached()
            fetchFirstPage()
            if (_state.value.loadState is LoadState.Loaded) startPrefetch()
        }
    }

    fun refresh() {
        // Mail for search too, at once: a pull is the user asking for the mail as it is now, and
        // the download would otherwise wait out the quarter hour after the last one, or the
        // background run's retry; as the iPhone's (tigerduck-app#894). A pull after the folder
        // list failed too.
        repository.downloadNow()
        // A load that failed at the folder list resolved nothing: there is no page to fetch yet,
        // so Retry and a pull start over from the folder list.
        if (_state.value.chips.isEmpty()) {
            load()
            return
        }
        // Stops the warm queue before it takes the connection again. SessionHolder serialises
        // every command behind one mutex, so without this a refresh could wait out several
        // folders' pages rather than at most one.
        //
        // At most one, not none: a fetch already in flight is blocking Angus Mail I/O inside that
        // mutex, and cancellation is not observed until it returns. It deliberately is not
        // interrupted -- abandoning a half-read IMAP response would leave the shared connection
        // out of step with the server for whoever used it next. The mutex is fair, so the refresh
        // is first in the queue behind that one page.
        prefetchJob?.cancel()
        prefetchJob = null
        viewModelScope.launch {
            if (_state.value.searchResults != null) runSearch() else fetchFirstPage()
            // Restarted once the user's own request has landed, exactly as [selectFolder] does --
            // cancelling without restarting would end the warming for this view model's whole
            // life the first time anybody pulled to refresh.
            if (_state.value.loadState is LoadState.Loaded) startPrefetch()
        }
    }

    fun selectFolder(selection: FolderSelection) {
        if (selection == _state.value.selected) return
        prefetchJob?.cancel()
        prefetchJob = null
        forgetSearch()
        listGeneration++
        _state.update {
            it.copy(
                selected = selection, messages = emptyList(), cursors = emptyMap(), searchResults = null, searchText = "",
                searchLocalOnly = false, searchIndexPartial = false,
            )
        }
        // The new selection's own date, kept from its last fetch, until this one's lands.
        showFetchedAt()
        viewModelScope.launch {
            showCached()
            fetchFirstPage()
            // Restarted, not merely cancelled. [startPrefetch] used to run from [load] alone, so
            // the first chip tap ended the warming for this view model's whole life -- a user who
            // tapped early left Drafts, Junk and Trash cold from then on and the feature simply
            // stopped working. The new queue is the one the *new* selection is not showing.
            if (_state.value.loadState is LoadState.Loaded) startPrefetch()
        }
    }

    fun setUnreadOnly(value: Boolean) = _state.update { it.copy(unreadOnly = value) }

    fun setSearchText(text: String) {
        if (text.isBlank()) forgetSearch()
        _state.update {
            if (text.isBlank()) {
                it.copy(searchText = text, searchResults = null, searchLocalOnly = false, searchIndexPartial = false)
            } else {
                it.copy(searchText = text)
            }
        }
    }

    private fun forgetSearch() {
        searchGeneration++
        unfetchedMatches = emptyMap()
        // A search still out is nobody's now, so its end must not be waited on to stop the spinner.
        _state.update { it.copy(isSearching = false, isPagingSearch = false) }
    }

    fun submitSearch() {
        viewModelScope.launch { runSearch() }
    }

    /**
     * [last] is the last row on screen, so it is matched against [UiState.displayed]: under unread
     * only, the list's own last row can be a read one the screen never shows.
     */
    fun loadMoreIfNeeded(last: MailRow) {
        val s = _state.value
        if (s.isPaginating || s.displayed.lastOrNull()?.key != last.key) return
        if (s.searchResults != null) {
            if (quietSearch == searchGeneration) {
                pageAskedWhileQuiet = last
                return
            }
            if (unfetchedMatches.values.any { it.uids.isNotEmpty() }) {
                searchPaged = true
                viewModelScope.launch { loadMoreSearchResults() }
            }
            return
        }
        if (s.cursors.isEmpty()) return
        viewModelScope.launch { paginate() }
    }

    /**
     * Acts on [row]'s own folder, never on [UiState.selected]: in All mail the selected chip is
     * not a folder at all, and the row next to this one may well live somewhere else.
     *
     * The row changes through the repository's feed ([apply]), as a mark-read on the mail's own
     * screen does, and not here as well: a swipe whose write ends after another student signed in
     * over this one would otherwise change a row of theirs, which the feed knows not to.
     *
     * Not for a row whose delete or move is still out ([UiState.writing]): the mail is on its way
     * out of the folder, and a STORE behind that write would find it gone.
     */
    fun toggleRead(row: MailRow) {
        if (row.key in _state.value.writing) return
        val seen = !row.summary.flags.seen
        val generation = account.accountGeneration
        viewModelScope.launch {
            try {
                repository.setSeen(row.folder, row.uid, seen)
            } catch (e: MailError) {
                // One swipe that didn't stick is not the page failing: the list on screen is
                // exactly as good as it was a moment ago. Nor is it the next student's to see,
                // once one has signed in over this one ([dropped]).
                if (!dropped(e, generation)) actionFail(e, generation)
            }
        }
    }

    /**
     * Asks before [row] is deleted, as the mail's own screen does: which question depends on
     * whether the delete moves it to the trash or destroys it, and only the server can say (a
     * missing trash is created on the way; see [SchoolMailRepository.deletesPermanently]).
     *
     * That answer can take a CREATE and a LIST, and another student can sign in meanwhile. The
     * question is then nobody's: put to them, its Delete would act on a mail of theirs; and the
     * LIST and CREATE are not sent at all once the account has moved, which would make a trash in
     * the next student's mailbox.
     */
    fun delete(row: MailRow) {
        if (!mayAsk(row) || !knowsVersionOf(row)) return
        val generation = account.accountGeneration
        asking = viewModelScope.launch {
            try {
                val permanently = repository.deletesPermanently(row.folder) { account.accountGeneration == generation }
                if (account.accountGeneration != generation) return@launch
                _state.update { it.copy(pendingDelete = PendingDelete(row, permanently, generation)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = MailErrors.classify(e)
                if (!dropped(error, generation)) actionFail(error, generation)
            }
        }
    }

    /** The answer to [UiState.pendingDelete]: either question, since both make the same [SchoolMailRepository.delete]. */
    fun confirmDelete(confirm: Boolean) {
        val pending = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        if (confirm) write(pending.row, pending.generation) { row, stillWanted -> repository.delete(row.folder, row.uid, row.uidValidity, stillWanted) }
    }

    /**
     * Asks where [row] goes, offering every folder but its own as the repository knows them now
     * rather than as [load] found them: a trash the first delete created
     * ([SchoolMailRepository.deletesPermanently]), or a drafts folder the first draft did, is a
     * place to move mail to from then on, as on the iPhone's move sheet. The repository keeps what
     * it created, so this asks the server nothing it has not been asked already. The account is
     * held as [delete] holds it.
     */
    fun requestMove(row: MailRow) {
        if (!mayAsk(row) || !knowsVersionOf(row)) return
        val generation = account.accountGeneration
        asking = viewModelScope.launch {
            try {
                val folders = repository.folders { account.accountGeneration == generation }
                if (account.accountGeneration != generation) return@launch
                val targets = SpecialFolder.entries.mapNotNull { kind -> folders.nameOf(kind)?.let { MoveTarget(it, kind) } } +
                    folders.others.map { MoveTarget(it, null) }
                _state.update { it.copy(pendingMove = PendingMove(row, targets.filter { t -> t.folder != row.folder }, generation)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = MailErrors.classify(e)
                if (!dropped(error, generation)) actionFail(error, generation)
            }
        }
    }

    fun dismissMove() {
        _state.update { it.copy(pendingMove = null) }
    }

    /**
     * Moves [UiState.pendingMove]'s mail to [target], the folder of one of its targets.
     *
     * The mail leaves its row's folder through the feed ([apply]), and arrives in [target] under a
     * UID only the server knows, which the feed can only mark for a fetch. A move from the mail's
     * own screen is fetched as the list appears again ([loadIfNeeded]); this list is on screen
     * already, so a [target] it shows -- the other of All mail's two folders -- is fetched now, or
     * the mail would be gone from a list it still belongs on until the next pull.
     */
    fun moveTo(target: String) {
        val pending = _state.value.pendingMove ?: return
        _state.update { it.copy(pendingMove = null) }
        write(pending.row, pending.generation) { row, stillWanted ->
            repository.move(row.folder, row.uid, target, row.uidValidity, stillWanted)
            if (stillWanted() && target in _state.value.targets) refetch()
        }
    }

    /**
     * Whether the UIDVALIDITY [row] was read under is known, without which a delete or move of it
     * could not be checked against the folder it reaches and is refused, as one of a recreated
     * folder is, before anything is asked. Every page and server search says; only a search the
     * server answered without one leaves a row without it.
     */
    private fun knowsVersionOf(row: MailRow): Boolean {
        if (row.uidValidity != null) return true
        actionFail(MailError.FolderChanged(), account.accountGeneration)
        return false
    }

    /**
     * Whether a delete or move of [row] may be asked now: no question is up or on its way, of this
     * row or another, and no write of this one is still out ([UiState.writing]). A question
     * refused is simply not asked; the row it was for is still on the list to ask it again.
     */
    private fun mayAsk(row: MailRow): Boolean {
        val s = _state.value
        return asking?.isActive != true && s.pendingDelete == null && s.pendingMove == null && row.key !in s.writing
    }

    /**
     * Sends a delete or move of [row] asked under [generation], unless the account has moved
     * since: its folder and UID would then name another student's mail. The repository asks the
     * same of [send]'s `stillWanted` with the session held, since the write can wait for it
     * behind other requests while another student signs in. Nor while another write of [row] is
     * still out ([UiState.writing]), which holds the row from here to its end.
     *
     * The write carries the row's own UIDVALIDITY ([MailRow.uidValidity]), the one its page or
     * search read it under, and the server is the judge of it: the folder as the write selects it
     * must have it ([SchoolMailRepository.move]), or the write is refused there with nothing sent.
     * That covers a folder recreated while the question was up, a poll's page of it landing
     * meanwhile or none at all. The list does not refuse a row for the version it read the folder
     * under last, which a fresh search can be newer than. A row without one is never sent.
     *
     * The row leaves the list through the repository's feed ([apply]), as a swipe's mark-read
     * changes it ([toggleRead]), and not here as well: a write held on the connection can end
     * after another student signed in over this one, whose list the feed knows to leave alone.
     * Nor is such a write's failure theirs to see ([dropped]), and least of all a rejected
     * password, which would mark theirs rejected.
     */
    private fun write(row: MailRow, generation: Int, send: suspend (row: MailRow, stillWanted: () -> Boolean) -> Unit) {
        if (account.accountGeneration != generation || row.key in _state.value.writing) return
        if (row.uidValidity == null) {
            actionFail(MailError.FolderChanged(), account.accountGeneration)
            return
        }
        _state.update { it.copy(writing = it.writing + row.key) }
        val forgotten = accountsForgotten
        viewModelScope.launch {
            try {
                send(row) { account.accountGeneration == generation }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = MailErrors.classify(e)
                if (!dropped(error, generation)) actionFail(error, generation)
            } finally {
                if (forgotten == accountsForgotten) _state.update { it.copy(writing = it.writing - row.key) }
            }
        }
    }

    /**
     * Spec §8.5: while the page is visible, look at the inbox every minute over
     * the held connection, and at the other folders on screen with it (see [poll]).
     * Spec §7.4: a rejected password stops the poll -- it
     * would otherwise re-send the same rejected LOGIN once a minute for as long
     * as the page is open. Signing in again clears the flag, and the screen
     * restarts polling from that.
     *
     * The first look is at once, not a minute in, when the app comes back to the foreground on
     * this page ([fromBackground]): mail that arrived meanwhile -- its notification perhaps
     * already in the shade -- should be on the list when it does (#251). Not as the page merely
     * appears: first entry, or the way back from a mail, which composes it again. The list is
     * then the one the user left moments ago ([loadIfNeeded]), and a look on every return would
     * be the round trip per mail opened that keeping it is there to save (#336) -- unless the
     * poll missed a tick while the page was away, when the look it owes is taken now. Never
     * while a load is still running, or has not run yet, nor within [RESUME_POLL_FRESH_MS] of a
     * first page landing, which already is the answer; nor while it has failed, which Retry is
     * there for. Decided [RESUME_POLL_GRACE_MS] in rather than here, since the fetch the page
     * makes for itself as it appears, when it has one to make, lands just after this call.
     */
    fun startPolling(fromBackground: Boolean = false) {
        // A background check may have reached the inbox while the page was away.
        showFetchedAt()
        if (account.authFailed.value) return
        repository.acquire()
        // Pausing cancels the warm along with the poll, and nothing used to start it again: only
        // a load, a refresh or a folder tap did. [PREFETCH_START_DELAY_MS] made that easy to hit
        // -- leave the screen within half a second of it appearing, which is exactly what
        // following a notification and coming straight back does, and every other mailbox stayed
        // cold for the rest of the visit. [prefetchDone] is what keeps a resume with nothing left
        // to warm from going back to the server to find that out.
        if (!prefetchDone && _state.value.loadState is LoadState.Loaded) startPrefetch()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            launch {
                delay(RESUME_POLL_GRACE_MS)
                if (pollsOnResume(fromBackground) && !account.authFailed.value) poll()
            }
            while (isActive && !account.authFailed.value) {
                delay(POLL_MS)
                if (account.authFailed.value) break
                poll()
            }
        }
    }

    private fun pollsOnResume(fromBackground: Boolean): Boolean {
        val s = _state.value
        // A failed list says so and offers Retry; a look of its own on every return would only
        // repeat the failure as a toast over it.
        if (s.loadState !is LoadState.Loaded) return false
        val looked = lookedAt ?: return true
        // Back from the background, any look older than the page's own just-landed one is stale;
        // merely appearing, only the tick the poll missed is owed (see [startPolling]).
        return clock.now() - looked >= if (fromBackground) RESUME_POLL_FRESH_MS else POLL_MS
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        repository.release()
    }

    /** The cached page is a disk read, so it runs off the main thread (the repository hops to IO). */
    private suspend fun showCached() {
        val selection = _state.value.selected
        val generation = account.accountGeneration
        // The disk read can hand back a row from before a write that lands meanwhile -- the
        // repository writes the cache once the server has answered the write -- so such a folder
        // is marked stale: the server page the caller fetches next, asked for after the change,
        // takes the mark off when it lands, and should it fail the mark keeps the next appearance
        // fetching the folder again. No fetch from here; the caller's is already on its way.
        val since = changeSeq
        val targets = _state.value.targets
        val pages = targets.mapNotNull { folder -> repository.cachedPage(folder)?.let { folder to it } }
        if (!stillCurrent(selection, generation)) return
        // A folder with no cached page has no rows painted for it, so nothing for its date to keep.
        cachePainted -= targets.toSet()
        if (pages.isEmpty()) return
        pages.forEach { (folder, _) -> cachePainted[folder] = checker.fetchedAt(folder) }
        listGeneration++
        // The validity the cached page was read under goes with its cursor: should the first page
        // fail -- offline at startup -- this is the list that gets paged, and without it an older
        // page from a folder recreated since would be merged in unchecked.
        folderValidity = folderValidity + pages.associate { (folder, page) -> folder to page.uidValidity }
        _state.update { it.copy(messages = rowsOf(selection, pages), cursors = cursorsOf(pages)) }
        markChangedSince(pages.map { it.first }, since)
    }

    /**
     * Exactly one page per real folder the selection covers: one round trip for a normal folder,
     * two for All mail, however far the merged list has already been scrolled. That bound is the
     * reason the merged view refreshes only the newest page per folder -- Mail2000 caps
     * connections and starts answering "server busy" under load.
     *
     * True once the server's first page is on screen; false when the fetch failed, or when the
     * selection changed before it landed and the page was dropped.
     */
    private suspend fun fetchFirstPage(): Boolean {
        val selection = _state.value.selected
        val targets = _state.value.targets
        if (targets.isEmpty()) {
            // Nothing to read, so nothing still loading either.
            _state.update { if (it.loadState is LoadState.Loading) it.copy(loadState = LoadState.Loaded) else it }
            return false
        }
        val generation = account.accountGeneration
        // What the pages are read against: a folder a change lands for while they are out is
        // fetched again once they have ([refetchIfChangedSince]).
        val since = changeSeq
        _state.update { it.copy(loadState = LoadState.Loading) }
        try {
            // Each folder is dated the moment its own page lands, not once the whole load has: a
            // later folder's failure, a chip tapped meanwhile or a sign-out must not lose a fetch
            // that did reach the server. And no request of the batch goes out once the account
            // or the view that asked has gone -- asked with the session held, so a request that
            // waited behind another is not sent under the next account's credentials either.
            val pages = targets.map { folder ->
                val page = repository.loadPage(folder, null) { stillCurrent(selection, generation) }
                dated(folder, generation)
                folder to page
            }
            if (!stillCurrent(selection, generation)) return false
            listGeneration++
            folderValidity = folderValidity + pages.associate { (folder, page) -> folder to page.uidValidity }
            _state.update { it.copy(messages = rowsOf(selection, pages), cursors = cursorsOf(pages), loadState = LoadState.Loaded) }
            lookedAt = clock.now()
            // The server's rows are on screen now, so the folders show their own dates again.
            cachePainted -= targets.toSet()
            showFetchedAt()
            // A mark set before these pages were asked for is answered by them; one set since is not.
            targets.forEach { folder -> if ((staleFolders[folder] ?: 0) <= since) staleFolders.remove(folder) }
            refetchIfChangedSince(targets, since)
            // Was "only for Inbox itself"; All mail shows the inbox's own mail too, so it must
            // move the notification seen marker exactly as viewing Inbox always did.
            if (_state.value.inboxInView) {
                runCatching { checker.noteSeenByPage(repository.inboxStatus { account.accountGeneration == generation }, generation) }
            }
            return true
        } catch (e: MailError) {
            if (dropped(e, generation)) return false
            fail(e, generation)
            return false
        }
    }

    /**
     * [folders], each with the UIDVALIDITY its rows were read under, were found recreated: the
     * server no longer has that generation, so the rows and cursors the list holds for them name
     * other mail now and the list starts again from the top. Should that fail, they go rather than
     * stay -- nothing may read them again, not the next scroll and not a search falling back to the
     * loaded mail -- and the folder shows nothing until a first page lands, which Retry, a pull or
     * the poll's next look brings. True once the list has reloaded.
     *
     * The folder's cached page goes first, for the same reason: it is the old mailbox's too, and
     * [showCached] would otherwise paint it again, rows and cursor, on the next visit to the folder
     * while the server keeps failing. Only a page of that same old generation, though: a refresh
     * that failed on another folder may already have cached this one under the new generation, and
     * that page is the only offline copy of the new mailbox. A reload that lands writes its own.
     *
     * [fetch] false when the server was just found out of reach: the old mailbox's rows and page
     * go as they would after a failed reload, without asking the server again at once.
     */
    private suspend fun reloadRecreated(folders: Map<String, Long>, fetch: Boolean = true): Boolean {
        val selection = _state.value.selected
        val list = listGeneration
        val generation = account.accountGeneration
        folders.forEach { (folder, obsolete) -> repository.dropCachedPage(folder, obsolete) }
        // The eviction is a disk write the user can tap through, or sign out during. A chip or an
        // account that moved meanwhile has its own load, and this request -- the one that found
        // the folder recreated -- starts none for it: [fetchFirstPage] would otherwise take the new
        // selection and account for its own and send, cache and date a load on their behalf.
        if (!stillCurrent(selection, generation) || list != listGeneration) return false
        if (fetch && fetchFirstPage()) return true
        // Only while this is still the list that was found stale: a newer one owns its rows.
        if (stillCurrent(selection, generation) && list == listGeneration) {
            _state.update { st -> st.copy(messages = st.messages.filterNot { it.folder in folders }, cursors = st.cursors - folders.keys) }
        }
        return false
    }

    /**
     * Warms the folders the user is *not* looking at, so switching chips paints from cache instead
     * of waiting on a round trip.
     *
     * Sequential, and on the connection the page already holds: Mail2000 caps connections and
     * answers "server busy" under load, so a fan-out here would be paid for by the screen the user
     * is actually reading. Twenty rows rather than a full page for the same reason -- it is enough
     * to fill a screen, and the real load that follows a chip tap replaces it.
     *
     * Silent by construction. It never touches loadState and never sets actionError: this is work
     * the user did not ask for, and a failure means only that a later chip tap is as slow as it
     * used to be.
     *
     * Cancelled, and then restarted once the request has landed, by every entry point that goes
     * to the server for the *whole* list: [load], [selectFolder] and [refresh]. Restarting is the
     * half that matters -- cancelling alone would end the warming for this view model's whole life
     * on the first chip tap -- and the queue is rebuilt each time because the folders worth
     * warming are whichever ones the current view is not showing.
     *
     * Not cancelled by [loadMoreIfNeeded], [submitSearch] or [toggleRead]. Those are one command
     * each, and the holder's mutex is fair, so the most any of them waits is the single folder
     * page already in flight -- cheaper than throwing away a queue they would only have to see
     * rebuilt. Cancelling buys the same one-page bound for the paths that do it; no caller can do
     * better once a fetch is in flight, because that is blocking I/O which must not be abandoned
     * mid-response. What [PREFETCH_START_DELAY_MS] adds is that the window in which there *is*
     * something in flight opens late enough to miss the collision that actually happens -- a
     * refresh in the first moments after the screen paints -- which then waits for nothing.
     *
     * Fetched with `background = true`, so a warm can seed an empty folder cache but never
     * shorten one the user has already paged further than [PREFETCH_LIMIT].
     */
    private fun startPrefetch() {
        prefetchJob?.cancel()
        prefetchDone = false
        val done = _state.value.targets.toMutableSet()
        val queue = _state.value.chips
            .mapNotNull { (it.selection as? FolderSelection.Real)?.name }
            .filter { done.add(it) }
        if (queue.isEmpty()) {
            prefetchDone = true
            return
        }
        prefetchJob = viewModelScope.launch {
            // Nothing is touched for a moment after the screen's own load lands. Cancelling the
            // queue cannot abort a fetch already inside the session mutex -- that is blocking
            // Angus Mail I/O, and abandoning a half-read IMAP response would leave the shared
            // connection out of step with the server -- so the only way to spare a foreground
            // request that wait is for the warm not to have started yet. This grace covers the
            // case that actually happens: someone who opens the page, sees stale rows, and pulls
            // to refresh straight away. Their cancel then lands before the connection is ever
            // taken, and they wait for nothing at all.
            delay(PREFETCH_START_DELAY_MS)
            for (folder in queue) {
                if (!isActive) return@launch
                try {
                    repository.loadPage(folder, null, PREFETCH_LIMIT, background = true)
                } catch (e: CancellationException) {
                    // A cancelled prefetch must actually stop, not just skip to the next
                    // iteration's isActive check -- runCatching would otherwise swallow this
                    // too and let the loop carry on regardless of who cancelled it or why.
                    throw e
                } catch (e: Throwable) {
                    // Silent by design (see the doc above): a background warm's failure means
                    // only that a later chip tap is as slow as it used to be.
                }
            }
            // Reached only by running the queue out. A cancelled job leaves this false, which is
            // what tells the next resume there is still warming to do.
            prefetchDone = true
        }
    }

    /**
     * Per-folder cursors: every folder the selection covers that still has older mail is paged
     * one page further and the result re-merged. So the merged list runs out only once *both*
     * folders genuinely have ([UiState.cursors] empty), never merely because the sparser of the
     * two did -- it never stops short while the server still has mail to give.
     *
     * Each page is merged the way [mergeOlderPage] says, not appended: a row the list already
     * holds takes the server's flags, and one inside the page's range the server no longer lists
     * goes. Only the newest page is ever written to the cache ([MailRepository.loadPage]), so
     * what is kept on disk stays one page per folder however far the list is scrolled.
     */
    private suspend fun paginate() {
        val selection = _state.value.selected
        if (_state.value.cursors.isEmpty()) return
        val list = listGeneration
        val generation = account.accountGeneration
        _state.update { it.copy(isPaginating = true) }
        // What arrives while each older page is out goes onto that page's rows (see [olderPageChanges]).
        val arrived = mutableListOf<MailChange>()
        olderPageChanges = arrived
        try {
            var walkedBack = 0
            while (true) {
                val cursors = _state.value.cursors
                if (cursors.isEmpty()) return
                val end = _state.value.displayed.lastOrNull()?.key
                arrived.clear()
                // No request of the batch goes out once the account, the view or the list that
                // asked has gone (see [fetchFirstPage]): an older page of a list a refresh has
                // replaced since would only be dropped on return, and under the next account's
                // credentials it must not be sent at all.
                //
                // Each folder's page is read during its own request, so the changes that count for
                // it are those from when that request started: one for it from before went into the
                // page, which may since have had a newer flag from another client that no change
                // tells of, and would only be written over (see [pageWith]).
                val windows = mutableMapOf<String, Int>()
                val pages = cursors.entries.sortedBy { it.key }.map { (folder, before) ->
                    windows[folder] = arrived.size
                    folder to repository.loadPage(folder, before) { stillCurrent(selection, generation) && list == listGeneration }
                }
                if (!stillCurrent(selection, generation) || list != listGeneration) return
                // A folder recreated since its first page: the older page's UIDs name other mail
                // than the list's, so it is not merged, and the list starts again from the top.
                val recreated = pages.mapNotNull { (folder, page) ->
                    folderValidity[folder]?.takeIf { it != page.uidValidity }?.let { folder to it }
                }.toMap()
                if (recreated.isNotEmpty()) {
                    reloadRecreated(recreated)
                    return
                }
                _state.update { st ->
                    val merged = pages.fold(st.messages) { rows, (folder, page) ->
                        mergeOlderPage(rows, folder, pageWith(folder, page, arrived.drop(windows.getValue(folder))))
                    }
                    st.copy(
                        messages = if (selection is FolderSelection.AllMail) merged.sortedWith(NEWEST_FIRST) else merged.sortedByDescending { it.uid },
                        cursors = st.cursors - cursors.keys + cursorsOf(pages),
                    )
                }
                // A page that adds nothing on screen leaves no new last row to ask for the next one,
                // so a few more are fetched straight away ([EMPTY_PAGE_WALKBACK]).
                if (_state.value.displayed.lastOrNull()?.key != end || walkedBack == EMPTY_PAGE_WALKBACK) return
                walkedBack++
            }
        } catch (e: MailError) {
            if (dropped(e, generation)) return
            fail(e, generation)
        } finally {
            olderPageChanges = null
            _state.update { it.copy(isPaginating = false) }
        }
    }

    /**
     * [page], an older page of [folder], with those of [arrived] that are the folder's applied to
     * its rows: a read flag as a write set it, or a row a move or delete took away. [arrived] is
     * what came from the folder's own request on -- read by the server no earlier than the page,
     * and after it where the batch went on to other folders -- and the rest of the list had them
     * in place as they came ([apply]). Nothing from before the request: the page has that, and
     * perhaps a newer flag besides.
     */
    private fun pageWith(folder: String, page: MailPage, arrived: List<MailChange>): MailPage {
        var messages = page.messages
        for (change in arrived) {
            if (change.folder != folder) continue
            messages = when (change) {
                is MailChange.Seen -> messages.map { if (it.uid == change.uid) it.copy(flags = it.flags.copy(seen = change.seen)) else it }
                is MailChange.Removed -> messages.filterNot { it.uid == change.uid }
                is MailChange.Changed -> messages
            }
        }
        return if (messages === page.messages) page else page.copy(messages = messages)
    }

    /**
     * [keepShown]: the search on screen run again as mail for it downloads, whose results stay
     * until the new ones replace them, with no spinner and no toast for a failure that need not
     * stick: the user did not ask for it.
     */
    private suspend fun runSearch(keepShown: Boolean = false) {
        val selection = _state.value.selected
        val query = _state.value.searchText.trim()
        // The rest of the matches on screen, for a quiet run that leaves them as they were.
        val shownUnfetched = unfetchedMatches
        forgetSearch()
        val search = searchGeneration
        if (!keepShown) {
            searchPaged = false
            submittedQuery = query
        }
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = null, searchLocalOnly = false, searchIndexPartial = false) }
            return
        }
        val generation = account.accountGeneration
        val since = changeSeq
        // Nothing on screen until its matches come: the mail shown before is no answer to it.
        if (keepShown) {
            quietSearch = search
        } else {
            _state.update { it.copy(isSearching = true, searchResults = emptyList(), searchLocalOnly = false, searchIndexPartial = false) }
        }
        try {
            // One search per real folder, in the search index on this phone, merged the same way
            // the list itself is; the server fetches the matches it finds. A folder that could not
            // be reached, or the demo mailbox, which has no index, still contributes its matches
            // among the mail already loaded, and the "loaded only" note appears as soon as any one
            // of them fell back.
            //
            // A failure is the search's, never the list's: the list it would replace is as good as
            // it was a moment ago, so it reaches [UiState.actionError] and not [UiState.loadState]
            // -- except a certificate failure, which stays on screen as the poll's does.
            //
            // A rejected password, a connection that could not be made or a certificate that
            // could not be trusted ends the server's part of the search: every folder after it
            // searches its loaded mail instead. Searching on would open the connection again for
            // each folder, and for a rejected password that is the same LOGIN sent again (spec
            // §7.4), so the rejection reaches the account at once, before anything else runs.
            //
            // And no SEARCH of the batch goes out once the account, the view or the search that
            // asked has gone (see [fetchFirstPage]). One let go unsent, or one that failed for an
            // account that has left, ends the whole search without a word: it is nobody's to fall
            // back on, and the loaded mail it would search is not this account's.
            var failure: MailError? = null
            var serverUnreachable = false
            // The version the list had of each folder as its outcome was asked for: see `recreated` below.
            val listedBefore = mutableMapOf<String, Long?>()
            val outcomes = _state.value.targets.map { folder ->
                listedBefore[folder] = folderValidity[folder]
                // Under the version the list read the folder's rows under, which they then keep.
                val loadedMatches = {
                    SearchOutcome.LoadedOnly(
                        _state.value.messages.filter { it.folder == folder && matchesLoadedMail(it.summary, query) }.map { it.summary },
                        folderValidity[folder],
                    )
                }
                folder to if (serverUnreachable) {
                    loadedMatches()
                } else {
                    try {
                        val outcome = repository.search(folder, query) { stillCurrent(selection, generation) && search == searchGeneration }
                        // The demo mailbox: the repository's answer knows only the folder's
                        // newest page, while the list may have paged further, so
                        // the matches are read from the rows it has loaded, as a failed folder's
                        // are -- but only rows read under the UIDVALIDITY that page has. A refresh
                        // can have cached a recreated folder's new page and then failed on another
                        // folder, leaving the old mailbox's rows on screen; those must not stand in
                        // for the new mailbox, whose matches the repository found.
                        if (outcome is SearchOutcome.LoadedOnly && (outcome.uidValidity == null || outcome.uidValidity == folderValidity[folder])) {
                            loadedMatches()
                        } else {
                            outcome
                        }
                    } catch (e: MailError) {
                        if (dropped(e, generation)) return
                        if (failure == null) failure = e
                        if (e is MailError.AuthFailed) account.onAuthFailure(generation)
                        if (endsServerSearch(e)) serverUnreachable = true
                        loadedMatches()
                    }
                }
            }
            if (!stillCurrent(selection, generation) || search != searchGeneration) {
                // The results are nobody's now, but a failure that must stick is still this
                // account's: a certificate that could not be trusted is the interception case the
                // page stays failed for, and a rejected password reaches the account and the
                // screen as it would have. Anything else -- a busy server behind a search the
                // user has moved on from -- is not worth a toast.
                failure?.takeIf { it is MailError.Certificate || it is MailError.AuthFailed }?.let { searchFail(it, generation) }
                return
            }
            // Run again silently, a folder the server could not search would answer with its
            // matches among the loaded mail alone, taking away the older and body-only ones the
            // server found and the rest of them still to fetch: the results on screen stay as they
            // were, and run again as more mail comes. A failure that must stick is still shown.
            if (keepShown && failure != null) {
                unfetchedMatches = shownUnfetched
                failure?.takeIf { it is MailError.Certificate || it is MailError.AuthFailed }?.let { searchFail(it, generation) }
                return
            }
            // Each row keeps the version of the folder its matches were read under.
            val rows = outcomes.flatMap { (folder, outcome) -> outcome.messages.map { MailRow(folder, it, outcome.version) } }
            val unfetched = outcomes.mapNotNull { (folder, outcome) ->
                (outcome as? SearchOutcome.Server)?.takeIf { it.remaining.isNotEmpty() }
                    ?.let { folder to UnfetchedMatches(it.uidValidity, it.remaining) }
            }.toMap()
            val found = _state.value.copy(
                searchResults = ordered(selection, rows),
                searchLocalOnly = outcomes.any { (_, outcome) -> outcome is SearchOutcome.LoadedOnly },
                searchIndexPartial = outcomes.any { (_, outcome) -> (outcome as? SearchOutcome.Server)?.complete == false },
            )
            // Run again silently, it fetches no pages of its own: one whose first pages show
            // nothing here, as under unread only, leaves the results on screen as they were, and
            // the rest of them still to fetch.
            if (keepShown && found.displayed.isEmpty() && _state.value.displayed.isNotEmpty()) {
                unfetchedMatches = shownUnfetched
                return
            }
            unfetchedMatches = unfetched
            _state.update {
                it.copy(
                    searchResults = found.searchResults,
                    searchLocalOnly = found.searchLocalOnly,
                    searchIndexPartial = found.searchIndexPartial,
                )
            }
            // A folder the search read under another UIDVALIDITY than the list's rows of it: it was
            // recreated, and those rows name other mail now. The list reads it again, as it does for
            // a page of results that comes back recreated ([loadMoreSearchResults]); the results
            // just found are of the folder as it is, and stay. Only while the list still holds the
            // version it had as the outcome was asked for: a page that landed meanwhile is newer
            // than an outcome read before it, whichever version each has, and is not to be dropped.
            // Not fetched at once from a server the search just found out of reach, either.
            val recreated = outcomes.mapNotNull { (folder, outcome) ->
                val listed = listedBefore[folder] ?: return@mapNotNull null
                val searched = outcome.version ?: return@mapNotNull null
                if (searched != listed && folderValidity[folder] == listed) folder to listed else null
            }.toMap()
            if (recreated.isNotEmpty()) {
                reloadRecreated(recreated, fetch = !serverUnreachable)
                if (!stillCurrent(selection, generation) || search != searchGeneration) {
                    // As above: a failure that must stick is still this account's, and is shown.
                    if (account.accountGeneration == generation) {
                        failure?.takeIf { it is MailError.Certificate || it is MailError.AuthFailed }?.let { searchFail(it, generation) }
                    }
                    return
                }
            }
            refetchIfChangedSince(outcomes.map { it.first }, since)
            failure?.takeIf { !keepShown || it is MailError.Certificate || it is MailError.AuthFailed }?.let { searchFail(it, generation) }
            // Nothing on screen means no last row to scroll to, however many matches are left.
            // Unless the server's part of the search ended early: in All mail the inbox can have
            // answered, with matches left to fetch, before Sent found the connection gone or the
            // password rejected, and a page of them now would open the connection again -- for a
            // rejected password, the LOGIN that was just decided against (spec §7.4).
            if (!keepShown && !serverUnreachable && _state.value.displayed.isEmpty()) {
                // Pages it fetches by itself are rows a silent run again would take away; with
                // none left to fetch, it stays empty and runs again as mail comes.
                if (unfetchedMatches.values.any { it.uids.isNotEmpty() }) searchPaged = true
                loadMoreSearchResults()
            }
        } finally {
            // Only the latest search's end stops the spinner: one it overtook may end while it runs.
            if (search == searchGeneration) _state.update { it.copy(isSearching = false) }
            if (keepShown) {
                if (quietSearch == search) quietSearch = null
                val asked = pageAskedWhileQuiet
                pageAskedWhileQuiet = null
                if (asked != null && search == searchGeneration) loadMoreIfNeeded(asked)
            }
        }
    }

    /**
     * The next page of each folder's [unfetchedMatches], added to the results. A page can add
     * nothing that shows -- every match since deleted, or read under unread only -- and then no
     * new last row appears to ask for the next one, so up to [EMPTY_PAGE_WALKBACK] more follow
     * until the end of the list moves or the matches run out. A failure stops it and says so; the
     * page it was fetching stays unfetched for the next scroll to try again.
     *
     * A page read under another UIDVALIDITY than the search's means the folder was recreated since:
     * its matches, listed and unfetched alike, name other mail now. They go, the list reloads
     * ([reloadRecreated]), and the query runs again over the recreated folder: the results are what
     * the screen shows while searching, so a reloaded list alone would leave a search of that one
     * folder empty. Not after a reload that failed, though -- that search would fail the same way
     * and fall back to the loaded mail, which is exactly the old rows the reload was to replace.
     */
    private suspend fun loadMoreSearchResults() {
        val selection = _state.value.selected
        val search = searchGeneration
        val generation = account.accountGeneration
        _state.update { it.copy(isPaginating = true, isPagingSearch = true) }
        try {
            var walkedBack = 0
            while (true) {
                val end = _state.value.displayed.lastOrNull()?.key
                val since = changeSeq
                val pending = unfetchedMatches.filterValues { it.uids.isNotEmpty() }
                if (pending.isEmpty()) return
                for ((folder, matches) in pending) {
                    val batch = matches.uids.take(MailRepository.PAGE_SIZE)
                    // Not sent once the account, the view or the search that asked has gone (see
                    // [fetchFirstPage]); a page let go unsent, or one that failed for an account
                    // that has left, is nobody's to report.
                    val page = try {
                        repository.searchPage(folder, batch) { stillCurrent(selection, generation) && search == searchGeneration }
                    } catch (e: MailError) {
                        if (!dropped(e, generation) && search == searchGeneration) searchFail(e, generation)
                        return
                    }
                    if (!stillCurrent(selection, generation) || search != searchGeneration) return
                    val searched = matches.uidValidity
                    if (searched != null && page.uidValidity != null && page.uidValidity != searched) {
                        unfetchedMatches = unfetchedMatches - folder
                        _state.update { st -> st.copy(searchResults = st.searchResults?.filterNot { it.folder == folder }) }
                        val reloaded = reloadRecreated(mapOf(folder to searched))
                        // Not if the search was cleared or replaced meanwhile: that one is the
                        // user's, and this would be a second round trip for the same query. Nor
                        // after a reload that failed: the server was just found unreachable, or
                        // rejected the password, and a search now asks it again (spec §7.4).
                        if (!reloaded || !stillCurrent(selection, generation) || search != searchGeneration) return
                        runSearch()
                        return
                    }
                    unfetchedMatches = unfetchedMatches + (folder to matches.copy(uids = matches.uids.drop(batch.size)))
                    _state.update { st ->
                        val results = st.searchResults ?: return@update st
                        val listed = results.mapTo(HashSet()) { it.key }
                        val added = page.messages.map { MailRow(folder, it, page.uidValidity) }.filter { it.key !in listed }
                        st.copy(searchResults = ordered(selection, results + added))
                    }
                    refetchIfChangedSince(listOf(folder), since)
                }
                if (_state.value.displayed.lastOrNull()?.key != end || walkedBack == EMPTY_PAGE_WALKBACK) return
                walkedBack++
            }
        } finally {
            // A page of a search since replaced leaves the spinner to the search that replaced it.
            _state.update { it.copy(isPaginating = false, isPagingSearch = it.isPagingSearch && search != searchGeneration) }
        }
    }

    /**
     * Runs every minute while the page shows a list and no search. Whenever the inbox's own mail
     * is in view -- Inbox itself, or All mail merging it in (see [UiState.inboxInView]) -- the
     * inbox gets the new-mail check, and a grown inbox refetches the list. [newest] is read from
     * the inbox's own rows specifically, not [UiState.messages] as a whole: in All mail, messages
     * also holds Sent rows, whose UIDs live in a completely unrelated namespace and would
     * otherwise skew the "did the inbox actually grow" check.
     *
     * Every other folder on screen -- Sent in All mail, or a folder viewed on its own -- is asked
     * for its STATUS as well, the cheapest round trip there is, and dated when it answers. A
     * check that finds nothing new must still move the "Last synced" row, or a time that stands
     * still reads as a page that stopped syncing; All mail is dated by the older of its two
     * folders, so both have to be verified for its row to move. A folder whose check failed keeps
     * its date, and so does one whose rows a failed load left from the cache ([cachePainted]). The
     * background [MailChecker] stays the inbox's alone: it is for notifications.
     */
    private suspend fun poll() {
        val s = _state.value
        if (s.targets.isEmpty() || s.searchResults != null) return
        val selection = s.selected
        val generation = account.accountGeneration
        try {
            if (s.inboxInView) {
                val status = repository.inboxStatus { stillCurrent(selection, generation) }
                // The STATUS reached the inbox, whatever the refetch a grown one starts makes of it;
                // rows a failed load left from the cache are not dated by it ([cachePainted]).
                s.inboxFolder?.let { if (it !in cachePainted) dated(it, generation) }
                val newest = s.messages.filter { it.folder == s.inboxFolder }.maxOfOrNull { it.uid } ?: 0L
                if (status.uidNext > newest + 1) {
                    // A chip tapped while the STATUS was out has its own load running; the view
                    // that left is not refetched for it.
                    if (!stillCurrent(selection, generation)) return
                    fetchFirstPage()
                    prefetchArrivedBodies(newest)
                    return
                }
                checker.noteSeenByPage(status, generation)
            }
            for (folder in s.targets) {
                if (folder == s.inboxFolder) continue
                // Asked only while still on screen -- with the session held, right before the
                // STATUS goes out, since it can wait there behind another request while the chip
                // moves on. A folder that left the screen is left alone; the next tick polls
                // whatever is on screen then.
                repository.status(folder) { stillCurrent(selection, generation) }
                if (folder !in cachePainted) dated(folder, generation)
            }
            // Every folder on screen answered: a look as good as a page, for the next return.
            lookedAt = clock.now()
        } catch (e: MailError) {
            if (dropped(e, generation)) return
            // A rejected password still has to reach the account (spec §7.4) -- both paths below
            // make that hop. Everything else is a transient action failure rather than the page
            // failing: Mail2000 caps connections and answers "server busy" under load, so one
            // unlucky minute used to be enough to leave the header dot red for the rest of the
            // session. The refresh this poll can trigger reports its own failures itself.
            //
            // A certificate failure is the exception and stays sticky. It means the connection
            // to the mail server could not be trusted, which on a campus network is exactly the
            // interception case worth interrupting for, and a toast that fades after a few
            // seconds every sixtieth second is something a user can miss indefinitely (§12.3).
            if (e is MailError.Certificate) fail(e, generation) else actionFail(e, generation)
        }
    }

    /**
     * Spec §5's body prefetch, on the foreground poll's path.
     *
     * [MailChecker] pulls a new mail's body down on the connection its check already holds, so
     * tapping the notification opens a mail that is already there rather than a spinner. The poll
     * never goes through [MailChecker.check]: it calls [fetchFirstPage] itself. So in the one case
     * where the mail is certain to be tapped within seconds -- the app open on this very list --
     * the row appeared within the minute and opening it still spun.
     *
     * Same constraints as the checker's version. Bounded to [BODY_PREFETCH_LIMIT] and newest
     * first, because a burst of mail must not turn one poll into a long one. Sequential, on the
     * connection the page already holds: Mail2000 caps connections and answers "server busy"
     * under load. Silent -- it touches neither [UiState.loadState] nor [UiState.actionError], so
     * nothing here can change what the poll reports; a body that will not come down means only
     * that opening that mail is as slow as it used to be.
     *
     * No second connection and no change to the read path a tap takes:
     * [SchoolMailRepository.body] is already cache-first and already writes what it fetches back
     * to the cache, which is exactly the pair of calls the checker makes by hand.
     *
     * [previousNewest] is the highest inbox UID the list held *before* this poll's fetch, so the
     * arrivals are precisely the rows that fetch brought in -- never the page the user has been
     * looking at all along.
     */
    private suspend fun prefetchArrivedBodies(previousNewest: Long) {
        val s = _state.value
        if (s.loadState !is LoadState.Loaded) return
        val inbox = s.inboxFolder ?: return
        val arrivals = s.messages
            .filter { it.folder == inbox && it.uid > previousNewest }
            .sortedByDescending { it.uid }
            .take(BODY_PREFETCH_LIMIT)
        for (row in arrivals) {
            if (!coroutineContext.isActive) return
            try {
                repository.body(inbox, row.uid)
            } catch (e: CancellationException) {
                // A cancelled prefetch must actually stop, for the same reason as [startPrefetch]:
                // the page has gone away, or the user has asked for something else.
                throw e
            } catch (e: Throwable) {
                // Silent by design (see the doc above).
            }
        }
    }

    /**
     * [folder] answered a request made for the account at [generation]: its date moves, and the
     * "Last synced" row with it when the folder is on screen. Called the moment each request
     * lands, whatever happens to the rest of the load, so a page that did reach the server is
     * never left undated; [MailChecker.noteFetchedByPage] drops it if the account has left.
     */
    private fun dated(folder: String, generation: Int) {
        checker.noteFetchedByPage(folder, generation)
        showFetchedAt()
    }

    /**
     * Whether an answer may still go on screen: [selection] is what the page shows, and the
     * account is the one that asked, by its [MailAccount.accountGeneration] read before the
     * request. A sign-out resets the state while a load is out, and the load is not cancelled
     * (it is blocking IMAP I/O), so whoever signs in next must not get the departing account's
     * rows (spec §7.5); nor must a chip tapped meanwhile get the rows of the view that left.
     */
    private fun stillCurrent(selection: FolderSelection, generation: Int): Boolean =
        _state.value.selected == selection && account.accountGeneration == generation

    /**
     * Whether a failure is nobody's to see: a request the repository let go unsent because
     * [stillCurrent] no longer held ([MailError.Abandoned]), or one of an account that has left
     * since [generation] -- whose failure is not the next account's, and whose rejected password
     * must not lock the next account out (as the checker's own check already holds).
     */
    private fun dropped(e: MailError, generation: Int): Boolean =
        e is MailError.Abandoned || account.accountGeneration != generation

    /**
     * A [load] dropped for an account that has left announced [LoadState.Loading] up front; that
     * is over, and whoever is signed in now starts their own. A sign-out has reset the state
     * already; another student signing in over this one has not, and must not inherit a spinner.
     */
    private fun loadDropped() = _state.update { if (it.loadState is LoadState.Loading) it.copy(loadState = LoadState.Idle) else it }

    /**
     * The date of the folders on screen: the older of All mail's two, and 0 while one of them has
     * none, as on the iPhone -- a merged list is only as fresh as its staler half. A folder whose
     * rows are still the cache's shows the date it had when they were painted ([cachePainted]).
     * Nothing is on screen to date before [load] has resolved the folders.
     */
    private fun showFetchedAt() = _state.update { s ->
        s.copy(fetchedAt = s.targets.minOfOrNull { cachePainted[it] ?: checker.fetchedAt(it) } ?: 0L)
    }

    /** Keyed by (folder, uid), never by UID alone -- the same UID in two folders is two mails. */
    private fun updateMessage(key: String, transform: (MailSummary) -> MailSummary) = _state.update { st ->
        st.copy(
            messages = st.messages.map { if (it.key == key) it.copy(summary = transform(it.summary)) else it },
            searchResults = st.searchResults?.map { if (it.key == key) it.copy(summary = transform(it.summary)) else it },
        )
    }

    private fun rowsOf(selection: FolderSelection, pages: List<Pair<String, MailPage>>): List<MailRow> =
        ordered(selection, pages.flatMap { (folder, page) -> page.messages.map { MailRow(folder, it, page.uidValidity) } })

    /**
     * A merged view is ordered by date, because the folders' UID spaces say nothing about each
     * other. A single folder is left exactly as the server returned it, so nothing about the
     * existing lists changes just because All mail exists.
     */
    private fun ordered(selection: FolderSelection, rows: List<MailRow>): List<MailRow> =
        if (selection is FolderSelection.AllMail) rows.sortedWith(NEWEST_FIRST) else rows

    /**
     * [page], an older page of [folder], merged into [rows]: every row it lists wins, and a row of
     * [folder] inside the page's UID range that it does not list was read, moved or deleted
     * elsewhere and goes. Rows outside that range, and every other folder's, stay.
     *
     * The range overlaps rows already listed whenever mail below the cursor was expunged, as the
     * sequence numbers it was taken from have shifted down since. A single folder's order is the
     * server's, UID descending, which re-sorting keeps.
     */
    private fun mergeOlderPage(rows: List<MailRow>, folder: String, page: MailPage): List<MailRow> {
        val window = page.windowUids
            ?: page.messages.takeIf { it.isNotEmpty() }?.let { m -> m.minOf { it.uid }..m.maxOf { it.uid } }
        val kept = if (window == null) rows else rows.filterNot { it.folder == folder && it.uid in window }
        val fresh = page.messages.map { MailRow(folder, it, page.uidValidity) }
        val freshKeys = fresh.mapTo(HashSet()) { it.key }
        return kept.filterNot { it.key in freshKeys } + fresh
    }

    private fun cursorsOf(pages: List<Pair<String, MailPage>>): Map<String, Int> =
        pages.mapNotNull { (folder, page) -> page.nextBeforeSeq?.let { folder to it } }.toMap()

    /**
     * A load failed: the page itself has nothing to show, so [LoadState.Failed] is the truth. A
     * rejected password reaches the account for the sign-in at [generation], the one the request
     * started under, in one step with any sign-in since (see [MailAccount.onAuthFailure]).
     */
    private fun fail(error: MailError, generation: Int) {
        if (error is MailError.AuthFailed) account.onAuthFailure(generation)
        _state.update { it.copy(loadState = LoadState.Failed(error)) }
    }

    /**
     * A one-off action failed. The account hop is identical to [fail]'s -- spec §7.4's lockout
     * protection must not depend on which call happened to hit the rejected password -- but
     * [UiState.loadState] is deliberately left alone.
     */
    private fun actionFail(error: MailError, generation: Int) {
        if (error is MailError.AuthFailed) account.onAuthFailure(generation)
        _state.update { it.copy(actionError = error) }
    }

    /**
     * A search, or a page of its results, failed. Reported once as a toast like any other action,
     * but a certificate failure stays on screen the way the poll leaves it ([poll]): it is the
     * interception case, and a toast is easy to miss.
     */
    private fun searchFail(error: MailError, generation: Int) {
        if (error is MailError.Certificate) fail(error, generation) else actionFail(error, generation)
    }

    /** A failure after which a search stops asking the server for the folders still to go. */
    private fun endsServerSearch(error: MailError): Boolean =
        error is MailError.AuthFailed || error is MailError.Network || error is MailError.Certificate

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    companion object {
        const val POLL_MS = 60_000L

        /** How often at most a search on screen runs again while mail downloads for search. */
        internal const val SEARCH_REGROW_MS = 3_000L

        /** How long after a first page lands a resume skips its look at the inbox. */
        internal const val RESUME_POLL_FRESH_MS = 5_000L

        /** How long a resume waits before deciding its look at the inbox; see [startPolling]. */
        internal const val RESUME_POLL_GRACE_MS = 500L

        /**
         * How many more pages a scroll to the end fetches, of the list or of search results, when
         * the one it asked for added nothing on screen: all deleted, already listed, or read under
         * unread only. Bounded, so one scroll can never page through a whole mailbox.
         */
        internal const val EMPTY_PAGE_WALKBACK = 3

        /** Enough to fill a screen; the real load that follows a chip tap replaces it. */
        internal const val PREFETCH_LIMIT = 20

        /**
         * How long a warm holds off before it takes the connection, so that a refresh in the
         * first moments after a screen paints cancels it rather than queues behind it.
         */
        internal const val PREFETCH_START_DELAY_MS = 500L

        /** Newest arrivals whose body one poll will warm. The same bound [MailChecker] uses. */
        internal const val BODY_PREFETCH_LIMIT = 5

        /** The date the card itself shows, then folder and UID so the merge order is stable. */
        private val NEWEST_FIRST =
            compareByDescending<MailRow> { it.summary.sentAt ?: it.summary.receivedAt ?: Instant.MIN }
                .thenBy { it.folder }
                .thenByDescending { it.uid }
    }
}
