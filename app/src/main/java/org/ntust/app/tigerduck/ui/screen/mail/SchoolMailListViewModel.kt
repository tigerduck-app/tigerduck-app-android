package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
class SchoolMailListViewModel @Inject constructor(
    private val repository: SchoolMailRepository,
    private val account: MailAccount,
    private val checker: MailChecker,
) : ViewModel() {

    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val error: MailError) : LoadState
    }

    /** [kind] is null for 所有信件, which is no server folder and so has no [SpecialFolder]. */
    data class FolderChip(val selection: FolderSelection, val kind: SpecialFolder?) {
        val key: String get() = kind?.name ?: "all"
    }

    data class UiState(
        val chips: List<FolderChip> = emptyList(),
        val others: List<String> = emptyList(),
        /**
         * Defaults to 所有信件: it is not a real folder name, so this can never spuriously match
         * a chip [load] resolves and get "kept" by accident (see [load]'s `keep`) -- it only ever
         * survives past the first [load] when the merge actually exists. When it doesn't, `keep`
         * falls through to the real 收件匣 folder, same as before 所有信件 existed.
         */
        val selected: FolderSelection = FolderSelection.AllMail,
        /** The server names of the folders 所有信件 merges. Empty until [load] has resolved them. */
        val mergedFolders: List<String> = emptyList(),
        val messages: List<MailRow> = emptyList(),
        val loadState: LoadState = LoadState.Idle,
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

        /** The resolved server name behind the 收件匣 chip, or null before [load] has resolved it. */
        val inboxFolder: String?
            get() = (chips.firstOrNull { it.kind == SpecialFolder.INBOX }?.selection as? FolderSelection.Real)?.name

        /**
         * True whenever the inbox's own mail is genuinely on screen: viewing 收件匣 directly, or
         * viewing 所有信件, which always merges 收件匣 in whenever it exists as a chip at all (see
         * [FolderSelection.AllMail.MERGED] and the `merged.size > 1` guard in [load]). Everything
         * that used to read "only do this for the inbox" -- new-mail polling, moving the
         * notification seen marker -- reads this instead, so defaulting to 所有信件 costs the user
         * none of that.
         */
        val inboxInView: Boolean get() = selectedKind == SpecialFolder.INBOX || selected == FolderSelection.AllMail

        /**
         * The real folders the current selection reads. Every repository call goes through this,
         * so no synthetic name can reach the server: 所有信件 resolves to the folders it merges,
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

    init {
        // Signing out must not leave the previous account's mail on screen for
        // whoever signs in next (spec §7.5).
        viewModelScope.launch {
            account.signedIn.collect { signedIn -> if (!signedIn) _state.value = UiState() }
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
                // 所有信件 only earns a chip when there are at least two folders to merge;
                // otherwise it would just be a second name for the one that resolved. It leads
                // the row -- it is the default view, not an extra one tacked on at the end.
                val chips = if (merged.size > 1) listOf(FolderChip(FolderSelection.AllMail, null)) + real else real
                // Fallback once 所有信件 isn't available (or the previous selection no longer
                // resolves): the real 收件匣 folder, same as the default before 所有信件 existed.
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
        }
    }

    fun refresh() {
        viewModelScope.launch { if (_state.value.searchResults != null) runSearch() else fetchFirstPage() }
    }

    fun selectFolder(selection: FolderSelection) {
        if (selection == _state.value.selected) return
        _state.update {
            it.copy(selected = selection, messages = emptyList(), cursors = emptyMap(), searchResults = null, searchText = "", searchLocalOnly = false)
        }
        viewModelScope.launch {
            showCached()
            fetchFirstPage()
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
     * Acts on [row]'s own folder, never on [UiState.selected]: in 所有信件 the selected chip is
     * not a folder at all, and the row next to this one may well live somewhere else.
     */
    fun toggleRead(row: MailRow) {
        val seen = !row.summary.flags.seen
        viewModelScope.launch {
            try {
                repository.setSeen(row.folder, row.uid, seen)
                updateMessage(row.key) { it.copy(flags = it.flags.copy(seen = seen)) }
            } catch (e: MailError) {
                fail(e)
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
     * two for 所有信件, however far the merged list has already been scrolled. That bound is the
     * reason the merged view refreshes only the newest page per folder -- Mail2000 caps
     * connections and starts answering 「伺服器忙線中」 under load.
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
            // Was "only for 收件匣 itself"; 所有信件 shows the inbox's own mail too, so it must
            // move the notification seen marker exactly as viewing 收件匣 always did.
            if (_state.value.inboxInView) {
                runCatching { checker.noteSeenByPage(repository.inboxStatus()) }
            }
        } catch (e: MailError) {
            fail(e)
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
     * Runs whenever the inbox's own mail is in view -- 收件匣 itself, or 所有信件 merging it in
     * (see [UiState.inboxInView]) -- every other real selection refreshes on pull-to-refresh
     * instead. [newest] is read from the inbox's own rows specifically, not [UiState.messages] as
     * a whole: in 所有信件, messages also holds 寄件備份 rows, whose UIDs live in a completely
     * unrelated namespace and would otherwise skew the "did the inbox actually grow" check.
     */
    private suspend fun poll() {
        val s = _state.value
        if (!s.inboxInView || s.searchResults != null) return
        try {
            val status = repository.inboxStatus()
            val newest = s.messages.filter { it.folder == s.inboxFolder }.maxOfOrNull { it.uid } ?: 0L
            if (status.uidNext > newest + 1) fetchFirstPage() else checker.noteSeenByPage(status)
        } catch (e: MailError) {
            // The same path as every other list failure: a rejected password has to
            // reach the account (spec §7.4) and a certificate failure has to reach
            // the UI (spec §12.3) instead of being retried silently every minute.
            fail(e)
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
     * existing lists changes just because 所有信件 exists.
     */
    private fun ordered(selection: FolderSelection, rows: List<MailRow>): List<MailRow> =
        if (selection is FolderSelection.AllMail) rows.sortedWith(NEWEST_FIRST) else rows

    private fun cursorsOf(pages: List<Pair<String, MailPage>>): Map<String, Int> =
        pages.mapNotNull { (folder, page) -> page.nextBeforeSeq?.let { folder to it } }.toMap()

    private fun fail(error: MailError) {
        if (error is MailError.AuthFailed) account.onAuthFailure()
        _state.update { it.copy(loadState = LoadState.Failed(error)) }
    }

    companion object {
        const val POLL_MS = 60_000L

        /** The date the card itself shows, then folder and UID so the merge order is stable. */
        private val NEWEST_FIRST =
            compareByDescending<MailRow> { it.summary.sentAt ?: it.summary.receivedAt ?: Instant.MIN }
                .thenBy { it.folder }
                .thenByDescending { it.uid }
    }
}
