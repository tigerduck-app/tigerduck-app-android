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
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.sync.MailChecker
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

    data class FolderChip(val name: String, val kind: SpecialFolder)

    data class UiState(
        val chips: List<FolderChip> = emptyList(),
        val others: List<String> = emptyList(),
        val selected: String = "INBOX",
        val messages: List<MailSummary> = emptyList(),
        val loadState: LoadState = LoadState.Idle,
        val nextBeforeSeq: Int? = null,
        val isPaginating: Boolean = false,
        val unreadOnly: Boolean = false,
        val searchText: String = "",
        val searchResults: List<MailSummary>? = null,
        val searchLocalOnly: Boolean = false,
        val isSearching: Boolean = false,
    ) {
        val displayed: List<MailSummary>
            get() = (searchResults ?: messages).let { list -> if (unreadOnly) list.filter { !it.flags.seen } else list }
        val selectedKind: SpecialFolder? get() = chips.firstOrNull { it.name == selected }?.kind
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
                val chips = SpecialFolder.entries.mapNotNull { kind -> folders.nameOf(kind)?.let { FolderChip(it, kind) } }
                val inbox = folders.nameOf(SpecialFolder.INBOX) ?: "INBOX"
                val current = _state.value.selected
                val keep = if (chips.any { it.name == current } || current in folders.others) current else inbox
                _state.update { it.copy(chips = chips, others = folders.others, selected = keep) }
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

    fun selectFolder(name: String) {
        if (name == _state.value.selected) return
        _state.update {
            it.copy(selected = name, messages = emptyList(), nextBeforeSeq = null, searchResults = null, searchText = "", searchLocalOnly = false)
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

    fun loadMoreIfNeeded(last: MailSummary) {
        val s = _state.value
        if (s.searchResults != null || s.isPaginating || s.nextBeforeSeq == null || s.messages.lastOrNull()?.uid != last.uid) return
        viewModelScope.launch { paginate() }
    }

    fun toggleRead(message: MailSummary) {
        val folder = _state.value.selected
        val seen = !message.flags.seen
        viewModelScope.launch {
            try {
                repository.setSeen(folder, message.uid, seen)
                updateMessage(message.uid) { it.copy(flags = it.flags.copy(seen = seen)) }
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
        val folder = _state.value.selected
        repository.cachedPage(folder)?.let { page ->
            if (_state.value.selected != folder) return
            _state.update { it.copy(messages = page.messages, nextBeforeSeq = page.nextBeforeSeq) }
        }
    }

    private suspend fun fetchFirstPage() {
        val folder = _state.value.selected
        _state.update { it.copy(loadState = LoadState.Loading) }
        try {
            val page = repository.loadPage(folder, null)
            if (_state.value.selected != folder) return
            _state.update { it.copy(messages = page.messages, nextBeforeSeq = page.nextBeforeSeq, loadState = LoadState.Loaded) }
            if (_state.value.selectedKind == SpecialFolder.INBOX) {
                runCatching { checker.noteSeenByPage(repository.inboxStatus()) }
            }
        } catch (e: MailError) {
            fail(e)
        }
    }

    private suspend fun paginate() {
        val s = _state.value
        val before = s.nextBeforeSeq ?: return
        _state.update { it.copy(isPaginating = true) }
        try {
            val page = repository.loadPage(s.selected, before)
            _state.update { st ->
                val known = st.messages.map { it.uid }.toSet()
                st.copy(messages = st.messages + page.messages.filter { it.uid !in known }, nextBeforeSeq = page.nextBeforeSeq)
            }
        } catch (e: MailError) {
            fail(e)
        } finally {
            _state.update { it.copy(isPaginating = false) }
        }
    }

    private suspend fun runSearch() {
        val s = _state.value
        val query = s.searchText.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = null, searchLocalOnly = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        try {
            val outcome = repository.search(s.selected, query)
            _state.update { it.copy(searchResults = outcome.messages, searchLocalOnly = outcome is SearchOutcome.LoadedOnly) }
        } catch (e: MailError) {
            fail(e)
        } finally {
            _state.update { it.copy(isSearching = false) }
        }
    }

    private suspend fun poll() {
        val s = _state.value
        if (s.selectedKind != SpecialFolder.INBOX || s.searchResults != null) return
        try {
            val status = repository.inboxStatus()
            val newest = s.messages.maxOfOrNull { it.uid } ?: 0L
            if (status.uidNext > newest + 1) fetchFirstPage() else checker.noteSeenByPage(status)
        } catch (e: MailError) {
            // The same path as every other list failure: a rejected password has to
            // reach the account (spec §7.4) and a certificate failure has to reach
            // the UI (spec §12.3) instead of being retried silently every minute.
            fail(e)
        }
    }

    private fun updateMessage(uid: Long, transform: (MailSummary) -> MailSummary) = _state.update { st ->
        st.copy(
            messages = st.messages.map { if (it.uid == uid) transform(it) else it },
            searchResults = st.searchResults?.map { if (it.uid == uid) transform(it) else it },
        )
    }

    private fun fail(error: MailError) {
        if (error is MailError.AuthFailed) account.onAuthFailure()
        _state.update { it.copy(loadState = LoadState.Failed(error)) }
    }

    companion object {
        const val POLL_MS = 60_000L
    }
}
