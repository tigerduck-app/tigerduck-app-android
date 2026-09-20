package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailSite
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.SearchOutcome
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailRow
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.sync.MailChecker
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@HiltViewModel
class SchoolMailListViewModel @Inject constructor(
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val checker: MailChecker,
    private val site: MailSite,
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
         * Only ever describes a *load*: [load], [fetchFirstPage], [paginate], [runSearch]. A
         * failed one-off action reports through [actionError] instead, so it can neither paint
         * the header dot red for the rest of the session nor replace a list that loaded perfectly
         * well with the "couldn't load" empty state.
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
        val isSearching: Boolean = false,
    ) {
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
    val authFailed: StateFlow<Boolean> = account.authFailed

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        // Signing out must not leave the previous account's mail on screen for
        // whoever signs in next (spec §7.5).
        viewModelScope.launch {
            account.signedIn.collect { signedIn ->
                if (!signedIn) {
                    prefetchJob?.cancel()
                    prefetchJob = null
                    _state.value = UiState()
                }
            }
        }
    }

    fun load() {
        if (!account.signedIn.value) return
        viewModelScope.launch {
            try {
                val folders = repository.folders()
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
            } catch (e: MailError) {
                fail(e)
                return@launch
            }
            showCached()
            fetchFirstPage()
            if (_state.value.loadState is LoadState.Loaded) startPrefetch()
        }
    }

    fun refresh() {
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
        _state.update {
            it.copy(selected = selection, messages = emptyList(), cursors = emptyMap(), searchResults = null, searchText = "", searchLocalOnly = false)
        }
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

    fun setSearchText(text: String) = _state.update {
        if (text.isBlank()) it.copy(searchText = text, searchResults = null, searchLocalOnly = false) else it.copy(searchText = text)
    }

    fun submitSearch() {
        viewModelScope.launch { runSearch() }
    }

    fun loadMoreIfNeeded(last: MailRow) {
        val s = _state.value
        if (s.searchResults != null || s.isPaginating || s.cursors.isEmpty() || s.messages.lastOrNull()?.key != last.key) return
        viewModelScope.launch { paginate() }
    }

    /**
     * Acts on [row]'s own folder, never on [UiState.selected]: in All mail the selected chip is
     * not a folder at all, and the row next to this one may well live somewhere else.
     */
    fun toggleRead(row: MailRow) {
        val seen = !row.summary.flags.seen
        viewModelScope.launch {
            try {
                repository.setSeen(row.folder, row.uid, seen)
                updateMessage(row.key) { it.copy(flags = it.flags.copy(seen = seen)) }
            } catch (e: MailError) {
                // One swipe that didn't stick is not the page failing: the list on screen is
                // exactly as good as it was a moment ago.
                actionFail(e)
            }
        }
    }

    /**
     * Spec §8.5: while the page is visible, look at the inbox every minute over
     * the held connection. Spec §7.4: a rejected password stops the poll -- it
     * would otherwise re-send the same rejected LOGIN once a minute for as long
     * as the page is open. Signing in again clears the flag, and the screen
     * restarts polling from that.
     */
    fun startPolling() {
        if (account.authFailed.value) return
        repository.acquire()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && !account.authFailed.value) {
                delay(POLL_MS)
                if (account.authFailed.value) break
                poll()
            }
        }
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
        val pages = _state.value.targets.mapNotNull { folder -> repository.cachedPage(folder)?.let { folder to it } }
        if (pages.isEmpty() || _state.value.selected != selection) return
        _state.update { it.copy(messages = rowsOf(selection, pages), cursors = cursorsOf(pages)) }
    }

    /**
     * Exactly one page per real folder the selection covers: one round trip for a normal folder,
     * two for All mail, however far the merged list has already been scrolled. That bound is the
     * reason the merged view refreshes only the newest page per folder -- Mail2000 caps
     * connections and starts answering "server busy" under load.
     */
    private suspend fun fetchFirstPage() {
        val selection = _state.value.selected
        val targets = _state.value.targets
        if (targets.isEmpty()) return
        _state.update { it.copy(loadState = LoadState.Loading) }
        try {
            val pages = targets.map { folder -> folder to repository.loadPage(folder, null) }
            if (_state.value.selected != selection) return
            _state.update { it.copy(messages = rowsOf(selection, pages), cursors = cursorsOf(pages), loadState = LoadState.Loaded) }
            // Was "only for Inbox itself"; All mail shows the inbox's own mail too, so it must
            // move the notification seen marker exactly as viewing Inbox always did.
            if (_state.value.inboxInView) {
                runCatching { checker.noteSeenByPage(repository.inboxStatus()) }
            }
        } catch (e: MailError) {
            fail(e)
        }
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
     * better, because the in-flight fetch is blocking I/O that must not be abandoned mid-response.
     *
     * Fetched with `background = true`, so a warm can seed an empty folder cache but never
     * shorten one the user has already paged further than [PREFETCH_LIMIT].
     */
    private fun startPrefetch() {
        prefetchJob?.cancel()
        val done = _state.value.targets.toMutableSet()
        val queue = _state.value.chips
            .mapNotNull { (it.selection as? FolderSelection.Real)?.name }
            .filter { done.add(it) }
        if (queue.isEmpty()) return
        prefetchJob = viewModelScope.launch {
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
        }
    }

    /**
     * Per-folder cursors: every folder the selection covers that still has older mail is paged
     * one page further and the result re-merged. So the merged list runs out only once *both*
     * folders genuinely have ([UiState.cursors] empty), never merely because the sparser of the
     * two did -- it never stops short while the server still has mail to give.
     */
    private suspend fun paginate() {
        val selection = _state.value.selected
        val cursors = _state.value.cursors
        if (cursors.isEmpty()) return
        _state.update { it.copy(isPaginating = true) }
        try {
            val pages = cursors.entries.sortedBy { it.key }.map { (folder, before) -> folder to repository.loadPage(folder, before) }
            if (_state.value.selected != selection) return
            _state.update { st ->
                val known = st.messages.mapTo(mutableSetOf()) { it.key }
                val added = pages.flatMap { (folder, page) -> page.messages.map { MailRow(folder, it) } }
                    .filter { it.key !in known }
                st.copy(
                    messages = ordered(selection, st.messages + added),
                    cursors = st.cursors - cursors.keys + cursorsOf(pages),
                )
            }
        } catch (e: MailError) {
            fail(e)
        } finally {
            _state.update { it.copy(isPaginating = false) }
        }
    }

    private suspend fun runSearch() {
        val selection = _state.value.selected
        val query = _state.value.searchText.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = null, searchLocalOnly = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        try {
            // One SEARCH per real folder, merged the same way the list itself is. A folder whose
            // server refused the search still contributes its locally-matched mail, and the
            // "loaded only" note appears as soon as any one of them fell back.
            val outcomes = _state.value.targets.map { folder -> folder to repository.search(folder, query) }
            if (_state.value.selected != selection) return
            val rows = outcomes.flatMap { (folder, outcome) -> outcome.messages.map { MailRow(folder, it) } }
            _state.update {
                it.copy(
                    searchResults = ordered(selection, rows),
                    searchLocalOnly = outcomes.any { (_, outcome) -> outcome is SearchOutcome.LoadedOnly },
                )
            }
        } catch (e: MailError) {
            fail(e)
        } finally {
            _state.update { it.copy(isSearching = false) }
        }
    }

    /**
     * Runs whenever the inbox's own mail is in view -- Inbox itself, or All mail merging it in
     * (see [UiState.inboxInView]) -- every other real selection refreshes on pull-to-refresh
     * instead. [newest] is read from the inbox's own rows specifically, not [UiState.messages] as
     * a whole: in All mail, messages also holds Sent rows, whose UIDs live in a completely
     * unrelated namespace and would otherwise skew the "did the inbox actually grow" check.
     */
    private suspend fun poll() {
        val s = _state.value
        if (!s.inboxInView || s.searchResults != null) return
        try {
            val status = repository.inboxStatus()
            val newest = s.messages.filter { it.folder == s.inboxFolder }.maxOfOrNull { it.uid } ?: 0L
            if (status.uidNext > newest + 1) {
                fetchFirstPage()
                prefetchArrivedBodies(newest)
            } else {
                checker.noteSeenByPage(status)
            }
        } catch (e: MailError) {
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
            if (e is MailError.Certificate) fail(e) else actionFail(e)
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

    /** Keyed by (folder, uid), never by UID alone -- the same UID in two folders is two mails. */
    private fun updateMessage(key: String, transform: (MailSummary) -> MailSummary) = _state.update { st ->
        st.copy(
            messages = st.messages.map { if (it.key == key) it.copy(summary = transform(it.summary)) else it },
            searchResults = st.searchResults?.map { if (it.key == key) it.copy(summary = transform(it.summary)) else it },
        )
    }

    private fun rowsOf(selection: FolderSelection, pages: List<Pair<String, MailPage>>): List<MailRow> =
        ordered(selection, pages.flatMap { (folder, page) -> page.messages.map { MailRow(folder, it) } })

    /**
     * A merged view is ordered by date, because the folders' UID spaces say nothing about each
     * other. A single folder is left exactly as the server returned it, so nothing about the
     * existing lists changes just because All mail exists.
     */
    private fun ordered(selection: FolderSelection, rows: List<MailRow>): List<MailRow> =
        if (selection is FolderSelection.AllMail) rows.sortedWith(NEWEST_FIRST) else rows

    private fun cursorsOf(pages: List<Pair<String, MailPage>>): Map<String, Int> =
        pages.mapNotNull { (folder, page) -> page.nextBeforeSeq?.let { folder to it } }.toMap()

    /** A load failed: the page itself has nothing to show, so [LoadState.Failed] is the truth. */
    private fun fail(error: MailError) {
        if (error is MailError.AuthFailed) account.onAuthFailure()
        _state.update { it.copy(loadState = LoadState.Failed(error)) }
    }

    /**
     * A one-off action failed. The account hop is identical to [fail]'s -- spec §7.4's lockout
     * protection must not depend on which call happened to hit the rejected password -- but
     * [UiState.loadState] is deliberately left alone.
     */
    private fun actionFail(error: MailError) {
        if (error is MailError.AuthFailed) account.onAuthFailure()
        _state.update { it.copy(actionError = error) }
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    companion object {
        const val POLL_MS = 60_000L

        /** Enough to fill a screen; the real load that follows a chip tap replaces it. */
        internal const val PREFETCH_LIMIT = 20

        /** Newest arrivals whose body one poll will warm. The same bound [MailChecker] uses. */
        internal const val BODY_PREFETCH_LIMIT = 5

        /** The date the card itself shows, then folder and UID so the merge order is stable. */
        private val NEWEST_FIRST =
            compareByDescending<MailRow> { it.summary.sentAt ?: it.summary.receivedAt ?: Instant.MIN }
                .thenBy { it.folder }
                .thenByDescending { it.uid }
    }
}
