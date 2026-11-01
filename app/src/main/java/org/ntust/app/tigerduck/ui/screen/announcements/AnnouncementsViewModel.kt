package org.ntust.app.tigerduck.ui.screen.announcements

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.util.AppReturns
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.util.logSummary
import java.time.Instant
import javax.inject.Inject

/**
 * When the announcement list fetches on its own — see [RefreshPolicy]. The
 * default: on the first visit after launch only. Bulletins arrive a few a
 * day, and the ones a user subscribes to are pushed.
 */
internal val AnnouncementsRefreshPolicy = RefreshPolicy()

/**
 * The empty list's title and message. With bulletins loaded but none shown, a filter, a search or
 * unread only is what hid them, and the message says nothing matches rather than that there is
 * nothing at all.
 */
internal fun bulletinEmptyText(hasItems: Boolean, unreadOnly: Boolean, hasActiveFilter: Boolean): Pair<Int, Int> = when {
    !hasItems -> R.string.bulletin_no_bulletins_title to R.string.bulletin_no_bulletins_message
    else -> (if (unreadOnly) R.string.bulletin_no_unread_title else R.string.bulletin_no_bulletins_title) to
        (if (unreadOnly || hasActiveFilter) R.string.bulletin_no_match_message else R.string.bulletin_no_bulletins_message)
}

internal enum class BulletinPlaceholder { EMPTY, FAILED }

/**
 * What stands in for an empty list, or null for none: the failure only while there are no
 * bulletins at all -- bulletins a filter or search hides are still there, and saying the load
 * failed would hide that it was the filter -- and the empty state once a load has answered or
 * bulletins are in hand.
 */
internal fun bulletinPlaceholder(hasItems: Boolean, hasDisplayed: Boolean, loadState: AnnouncementsViewModel.LoadState): BulletinPlaceholder? = when {
    hasDisplayed -> null
    !hasItems && loadState is AnnouncementsViewModel.LoadState.Failed -> BulletinPlaceholder.FAILED
    hasItems || loadState is AnnouncementsViewModel.LoadState.Loaded -> BulletinPlaceholder.EMPTY
    else -> null
}

/**
 * Whether a bulletin answers the search [text]: its title as shown, its original title -- the
 * shown one may have been cleaned up past the wording someone remembers -- or its summary.
 */
internal fun BulletinSummary.matchesSearch(text: String): Boolean =
    displayTitle.contains(text, ignoreCase = true) ||
        title.contains(text, ignoreCase = true) ||
        summary?.contains(text, ignoreCase = true) == true

/** How long the app may sit in the background before the bulletin search is cleared on return. */
internal const val BULLETIN_SEARCH_RESET_MS = 5 * 60_000L

/** Whether coming back after [awayMs] in the background clears the search. */
internal fun bulletinSearchResets(awayMs: Long): Boolean = awayMs > BULLETIN_SEARCH_RESET_MS

/**
 * Drives AnnouncementsScreen. Ports BulletinsViewModel.swift faithfully:
 * disk-cache seed → network refresh → background prefetch chain → filter
 * intersection. Sort key is `(postedAt DESC, id DESC)` so server pages and
 * cached items merge cleanly without duplicates or pinned-post jumps.
 */
@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val api: BulletinApiClient,
    private val cache: BulletinCache,
    private val repository: BulletinRepository,
    private val readState: BulletinReadStateStore,
    private val demoAccount: DemoAccount,
    private val credentials: CredentialManager,
    appReturns: AppReturns,
) : ViewModel() {


    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val message: String) : LoadState
    }

    data class State(
        val items: List<BulletinSummary> = emptyList(),
        val filtered: List<BulletinSummary> = emptyList(),
        val loadState: LoadState = LoadState.Idle,
        val isPaginating: Boolean = false,
        val hasMore: Boolean = true,
        val taxonomy: TaxonomyResponse? = null,
        val selectedOrgs: Set<String> = emptySet(),
        val selectedTags: Set<String> = emptySet(),
        val searchText: String = "",
        val showDeleted: Boolean = false,
        val unreadOnly: Boolean = false,
        val readIds: Set<Int> = emptySet(),
        /**
         * [filtered] minus already-read items when [unreadOnly] is on. Stored
         * (not computed) so Compose recompositions triggered by frequent
         * readIds ticks don't re-run the filter pass on every read.
         */
        val displayed: List<BulletinSummary> = emptyList(),
        /** For the sync dot's "Last synced" row; see [BulletinCache.loadFetchedAt]. */
        val syncedAtMs: Long = 0L,
    ) {
        val hasUnread: Boolean
            get() = filtered.any { it.id !in readIds }

        /** A department, category or search narrows the list, so an empty one has hidden bulletins. */
        val hasActiveFilter: Boolean
            get() = selectedOrgs.isNotEmpty() || selectedTags.isNotEmpty() || searchText.isNotBlank()
    }

    private val _state = MutableStateFlow(
        // A return visit finds the department list in memory, so the chips are
        // there from the first frame, with whatever this entry saved; a first
        // visit waits for fetchTaxonomyOnce(). A new entry has nothing saved,
        // so its filters start empty.
        repository.taxonomy().let {
            State(taxonomy = it, selectedOrgs = savedOrgs(it), selectedTags = savedTags(it))
        }
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Counts the searches cleared on the app's return ([BULLETIN_SEARCH_RESET_MS]), for the screen
     * to drop the search field's focus along with its text.
     */
    private val _searchResets = MutableStateFlow(0)
    val searchResets: StateFlow<Int> = _searchResets.asStateFlow()

    init {
        readState.readIds
            .onEach { ids -> _state.update { withDisplayed(it.copy(readIds = ids)) } }
            .launchIn(viewModelScope)
        // A search left behind for more than five minutes in the background is cleared, so coming
        // back later does not open on a stale filtered list; a quick switch to another app keeps
        // it. The whole app's coming and going, not the list screen's: the app can be left from a
        // bulletin's own page, where the list is not on screen to notice.
        appReturns.returns
            .onEach { away ->
                if (bulletinSearchResets(away) && _state.value.searchText.isNotEmpty()) {
                    setSearch("")
                    _searchResets.update { it + 1 }
                }
            }
            .launchIn(viewModelScope)
    }

    // Main-thread-only: read/written from coroutines launched on
    // viewModelScope, which inherits Dispatchers.Main.immediate. Do not
    // touch from a non-Main dispatcher without adding synchronisation.
    private var nextCursor: Int? = null
    private var inflight: Job? = null
    private var prefetch: Job? = null
    private var loadMoreJob: Job? = null

    // Main-thread-only — see nextCursor.
    private var hasLoaded = false

    // Main-thread-only — see nextCursor. Whose list the last load was for.
    private var loadedFor: ListOwner? = null

    fun load() {
        // A list held since a sign-out or an endpoint move is let go of here too: this view model
        // outlives a tab switch when its tab is the first, which never leaves the back stack.
        loadedFor?.let { held ->
            if (held.generation != repository.generation) forgetTaxonomy()
            if (!keeps(held)) startOver()
        }
        if (hasLoaded) return
        hasLoaded = true
        val owner = listOwner()
        loadedFor = owner
        viewModelScope.launch {
            val cached = cache.load()
            val fetchedAt = cache.loadFetchedAt()
            // Read for a list let go of meanwhile: the old account's or backend's.
            if (droppedAfterLetGo(owner)) return@launch
            _state.update { it.copy(syncedAtMs = fetchedAt) }
            if (cached.isNotEmpty()) {
                repository.putSummaries(cached)
                _state.update { applyFilters(it.copy(items = sortedUnique(cached))) }
            }
            // This view model is rebuilt on every visit, so each visit used
            // to refetch the first page and prefetch five more behind it,
            // only to replace the cached list with the same list. A return
            // the policy does not fetch for keeps what the last refresh
            // saved, and picks the cursor up where it stopped.
            val session = repository.listSession(_state.value.showDeleted)
            val policy = AnnouncementsRefreshPolicy
            val fetch = when {
                // Nothing to show: there is no list to keep.
                cached.isEmpty() -> true
                // The first visit since the app started.
                session == null -> policy.onLaunch
                else -> policy.onRevisit && RefreshPolicy.hasPassed(
                    session.fetchedAtMs, SystemClock.elapsedRealtime(), policy.minInterval,
                )
            }
            if (fetch) {
                refresh()
            } else {
                // Without a session (a policy that skips the launch) there is
                // no cursor either, so the cached list does not page on until
                // a pull.
                nextCursor = session?.nextCursor
                _state.update {
                    it.copy(loadState = LoadState.Loaded, hasMore = session?.nextCursor != null)
                }
            }
            launch {
                try {
                    fetchTaxonomyOnce()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Taxonomy is decorative (org/tag labels); the list works
                    // without it. Still log — a permanently failing taxonomy
                    // endpoint would otherwise be invisible.
                    android.util.Log.w("AnnouncementsVM", "Taxonomy fetch failed: ${e.logSummary()}")
                }
            }
        }
    }

    private suspend fun fetchTaxonomyOnce() {
        if (_state.value.taxonomy != null) return
        val tax = repository.getOrFetchTaxonomy {
            runCatching { api.fetchTaxonomy() }.getOrNull()
        } ?: return
        _state.update {
            applyFilters(it.copy(taxonomy = tax, selectedOrgs = savedOrgs(tax), selectedTags = savedTags(tax)))
        }
    }

    /**
     * The departments this back-stack entry has selected, from [savedState]: a
     * selection belongs to the visit that made it, and when Android kills the
     * process while a bulletin is open, Back arrives in a recreated view model
     * that would otherwise start empty. None until the department list has
     * loaded, and only those it still lists — a filter with no chip on screen
     * to show it or turn it off is what this keeps out. A new entry has nothing
     * saved; its filters start empty, as the Remember bulletin filters option
     * once stopped doing (#376).
     */
    private fun savedOrgs(taxonomy: TaxonomyResponse?): Set<String> =
        savedIds(KEY_SELECTED_ORGS, taxonomy?.orgs?.map { it.id })

    /** The categories this entry has selected; see [savedOrgs]. */
    private fun savedTags(taxonomy: TaxonomyResponse?): Set<String> =
        savedIds(KEY_SELECTED_TAGS, taxonomy?.tags?.map { it.id })

    private fun savedIds(key: String, known: List<String>?): Set<String> {
        if (known == null) return emptySet()
        val saved = savedState.get<Array<String>>(key) ?: return emptySet()
        return saved.toSet() intersect known.toSet()
    }

    fun refresh() {
        // The demo account's bulletins are the ones its sign-in cached, and
        // DemoModeInterceptor refuses the fetch: the cached list is the loaded
        // one, with no page behind it, rather than a refresh that can only fail.
        if (demoAccount.isActive) {
            _state.update { it.copy(loadState = LoadState.Loaded, hasMore = false) }
            return
        }
        inflight?.cancel()
        prefetch?.cancel()
        loadMoreJob?.cancel()
        nextCursor = null
        inflight = viewModelScope.launch {
            // Capture before any suspend so a concurrent toggle of showDeleted
            // can't desync the fetch filter from the UI state.
            val includeDeleted = _state.value.showDeleted
            _state.update { it.copy(loadState = LoadState.Loading, isPaginating = false) }
            // The school account and backend as they are now; a page that arrives for another is dropped.
            val owner = listOwner()
            try {
                val response = api.fetchList(
                    cursor = null,
                    includeDeleted = includeDeleted,
                )
                // Defend against a cancelled launch resuming past the network
                // call: writing nextCursor / items here would overwrite the
                // newer refresh that triggered our cancellation.
                coroutineContext.ensureActive()
                if (droppedAfterLetGo(owner)) return@launch
                // Wall clock, for the "Last synced" row and across restarts;
                // the session's elapsedRealtime below is neither.
                val fetchedAt = System.currentTimeMillis()
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(
                        it.copy(
                            items = merged,
                            loadState = LoadState.Loaded,
                            hasMore = response.nextCursor != null,
                            syncedAtMs = fetchedAt,
                        )
                    )
                }
                if (!saveUnlessLetGo(merged, fetchedAt, owner)) return@launch
                repository.listRefreshed(
                    SystemClock.elapsedRealtime(), includeDeleted, response.nextCursor,
                )
                // Only prune when the cursor chain is exhausted — pruning on the
                // first page would drop read-IDs for older bulletins not yet
                // fetched (e.g., after Auto Backup restore on reinstall).
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                } else {
                    startBackgroundPrefetch(includeDeleted, owner)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Allow load() to retry on next screen entry if the initial
                // fetch failed and there's no cached data to fall back on.
                if (_state.value.items.isEmpty()) {
                    hasLoaded = false
                }
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: "error")) }
            }
        }
    }

    // refresh() cancels prefetch before relaunching, but cancellation of a
    // coroutine suspended inside withContext(Dispatchers.IO) (the OkHttp call)
    // is deferred until the network returns. In that window prefetch and the
    // new inflight can both resume on Main and race on nextCursor / state.
    // Dispatchers.Main.immediate makes overlap vanishingly rare today, but the
    // ensureActive() before each write is what actually keeps the older job
    // from clobbering newer state — don't remove it on the assumption that
    // Main scheduling alone is enough.
    private fun startBackgroundPrefetch(includeDeleted: Boolean, owner: ListOwner) {
        prefetch?.cancel()
        prefetch = viewModelScope.launch {
            // Walk the cursor chain in the background so the user doesn't see
            // a load spinner mid-scroll. Cap at 5 pages — anything older is
            // legitimately a "load more" tap.
            var pages = 0
            var latest: List<BulletinSummary>? = null
            while (pages < 5 && nextCursor != null) {
                pages++
                delay(150)
                val cursor = nextCursor ?: break
                val response = try {
                    api.fetchList(cursor = cursor, includeDeleted = includeDeleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                coroutineContext.ensureActive()
                if (droppedAfterLetGo(owner)) return@launch
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(it.copy(items = merged, hasMore = response.nextCursor != null))
                }
                latest = merged
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                    break
                }
            }
            // Persist the final merged snapshot once when the prefetch chain
            // settles, instead of rewriting summaries.json on every page.
            latest?.let {
                if (saveUnlessLetGo(it, null, owner)) repository.listAdvanced(includeDeleted, nextCursor)
            }
        }
    }

    /**
     * Asks for the next page once the list has been scrolled within five bulletins of the end.
     *
     * Takes the id rather than the bulletin, because the id is what the caller can be sure of:
     * it is the list item's key, and so survives anything the screen puts above the bulletins.
     */
    fun loadMoreIfNeeded(lastVisibleId: Int) {
        val s = _state.value
        if (!s.hasMore || s.isPaginating) return
        val tail = s.displayed.takeLast(5).map { it.id }
        if (lastVisibleId !in tail) return
        val cursor = nextCursor ?: return
        prefetch?.cancel()
        _state.update { it.copy(isPaginating = true) }
        loadMoreJob = viewModelScope.launch {
            val owner = listOwner()
            try {
                val response = api.fetchList(cursor = cursor, includeDeleted = s.showDeleted)
                coroutineContext.ensureActive()
                if (droppedAfterLetGo(owner)) return@launch
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(
                        it.copy(
                            items = merged,
                            isPaginating = false,
                            hasMore = response.nextCursor != null,
                        )
                    )
                }
                if (!saveUnlessLetGo(merged, null, owner)) return@launch
                repository.listAdvanced(s.showDeleted, response.nextCursor)
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(isPaginating = false) }
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isPaginating = false) }
            }
        }
    }

    /**
     * Whose list a fetch is for: the school account, read from
     * [CredentialManager.schoolAccountGeneration], and the backend, by [BulletinRepository.generation],
     * which each move of the API endpoint to another backend moves -- there and back again too.
     * A school sign-out and an endpoint move each let go of the cached list and its date
     * ([org.ntust.app.tigerduck.auth.AuthService.logout],
     * [org.ntust.app.tigerduck.ui.screen.debug.EndpointChange]).
     */
    private data class ListOwner(val account: Int, val generation: Int)

    private fun listOwner() = ListOwner(credentials.schoolAccountGeneration, repository.generation)

    /** Whether the list [owner] read is still the one kept: no sign-out and no endpoint move since. */
    private fun keeps(owner: ListOwner): Boolean =
        credentials.isSchoolAccount(owner.account) && repository.generation == owner.generation

    /**
     * After an endpoint move: the departments and categories, and the ones this entry selected,
     * were the old backend's, whose ids the new one need not share. A selection left on would
     * hide every bulletin of the new backend.
     */
    private fun forgetTaxonomy() {
        savedState.remove<Array<String>>(KEY_SELECTED_ORGS)
        savedState.remove<Array<String>>(KEY_SELECTED_TAGS)
        _state.update { applyFilters(it.copy(taxonomy = null, selectedOrgs = emptySet(), selectedTags = emptySet())) }
    }

    /**
     * Whether the list [owner] read has been let go of since, before a fetch: by a school
     * sign-out, or by an API endpoint move. A page that arrives after it would write the list
     * back -- the departing student's list would be the next one's first paint, and the old
     * backend's rows would take the new backend's by id. Bulletins are public, but the sign-out
     * is still where the list is let go of. So the page is dropped and the list starts over: the
     * next visit reads the cleared cache, then the feed, as on the iPhone. The write to the cache
     * that follows a page let through asks again, under the cache's lock; see [saveUnlessLetGo].
     */
    private fun droppedAfterLetGo(owner: ListOwner): Boolean {
        if (keeps(owner)) return false
        startOver()
        return true
    }

    /**
     * Writes the list, and its date when given, unless it has been let go of since [owner] was
     * read -- asked under the cache's lock, which the sign-out's and the endpoint move's clear
     * take too, and both move what [keeps] reads before they start it: the check
     * [droppedAfterLetGo] made a moment earlier cannot be overtaken between it and the write.
     * Refused, the list starts over as there.
     */
    private suspend fun saveUnlessLetGo(items: List<BulletinSummary>, fetchedAt: Long?, owner: ListOwner): Boolean {
        // Asked again once the save is back: a list let go of while it was written is cleared
        // after it, and what follows the save -- the list session, the pruning, the walk through
        // older pages -- would act on the old list.
        if (cache.saveIf(items, fetchedAt) { keeps(owner) } && keeps(owner)) return true
        startOver()
        return false
    }

    /** Back to before the first visit: nothing in memory, and [load] seeds from the cache again. */
    private fun startOver() {
        prefetch?.cancel()
        loadMoreJob?.cancel()
        nextCursor = null
        hasLoaded = false
        _state.update {
            applyFilters(
                it.copy(items = emptyList(), loadState = LoadState.Idle, isPaginating = false, hasMore = true, syncedAtMs = 0L)
            )
        }
    }

    fun setOrgFilter(orgs: Set<String>) {
        savedState[KEY_SELECTED_ORGS] = orgs.toTypedArray()
        _state.update { applyFilters(it.copy(selectedOrgs = orgs)) }
    }

    fun setTagFilter(tags: Set<String>) {
        savedState[KEY_SELECTED_TAGS] = tags.toTypedArray()
        _state.update { applyFilters(it.copy(selectedTags = tags)) }
    }

    fun setSearch(text: String) =
        _state.update { applyFilters(it.copy(searchText = text)) }

    fun setUnreadOnly(value: Boolean) =
        _state.update { withDisplayed(it.copy(unreadOnly = value)) }

    fun setShowDeleted(value: Boolean) {
        if (_state.value.showDeleted == value) return
        _state.update { applyFilters(it.copy(showDeleted = value)) }
        refresh()
    }

    fun toggleRead(id: Int) = readState.toggleRead(id)

    fun markAllRead() {
        val visibleIds = _state.value.filtered.map { it.id }
        readState.markAllRead(visibleIds)
    }

    private fun applyFilters(s: State): State {
        val text = s.searchText.trim()
        val filtered = s.items.asSequence()
            .filter { s.showDeleted || !it.isDeleted }
            .filter { s.selectedOrgs.isEmpty() || it.canonicalOrg in s.selectedOrgs }
            .filter {
                s.selectedTags.isEmpty() || it.contentTags.any { tag -> tag in s.selectedTags }
            }
            .filter { text.isEmpty() || it.matchesSearch(text) }
            .toList()
        return withDisplayed(s.copy(filtered = filtered))
    }

    private fun withDisplayed(s: State): State {
        val displayed = if (s.unreadOnly) s.filtered.filter { it.id !in s.readIds } else s.filtered
        return s.copy(displayed = displayed)
    }

    private fun sortedUnique(items: List<BulletinSummary>): List<BulletinSummary> {
        val byId = LinkedHashMap<Int, BulletinSummary>(items.size)
        // Later entries win — server fetch overwrites stale cache row for
        // the same id (e.g. importance/title_clean updates after LLM run).
        for (item in items) byId[item.id] = item
        return byId.values.sortedWith(
            compareByDescending<BulletinSummary> {
                it.postedAt?.let(::parseInstant) ?: Instant.EPOCH
            }
                .thenByDescending { it.id }
        )
    }

    private fun parseInstant(raw: String): Instant =
        try {
            Instant.parse(raw)
        } catch (_: Exception) {
            Instant.EPOCH
        }

    private companion object {
        /** [SavedStateHandle] keys: the ids selected on this back-stack entry, as string arrays. */
        const val KEY_SELECTED_ORGS = "selectedOrgs"
        const val KEY_SELECTED_TAGS = "selectedTags"
    }
}
