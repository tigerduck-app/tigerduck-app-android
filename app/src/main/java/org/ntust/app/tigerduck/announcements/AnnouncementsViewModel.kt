package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Drives AnnouncementsScreen. Ports BulletinsViewModel.swift faithfully:
 * disk-cache seed → network refresh → background prefetch chain → filter
 * intersection. Sort key is `(postedAt DESC, id DESC)` so server pages and
 * cached items merge cleanly without duplicates or pinned-post jumps.
 */
@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val api: BulletinApiClient,
    private val cache: BulletinCache,
    private val repository: BulletinRepository,
    private val readState: BulletinReadStateStore,
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
    ) {
        val hasUnread: Boolean
            get() = filtered.any { it.id !in readIds }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        readState.readIds
            .onEach { ids -> _state.update { withDisplayed(it.copy(readIds = ids)) } }
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

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch {
            val cached = cache.load()
            if (cached.isNotEmpty()) {
                repository.putSummaries(cached)
                _state.update { applyFilters(it.copy(items = sortedUnique(cached))) }
            }
            refresh()
            launch { runCatching { fetchTaxonomyOnce() } }
        }
    }

    private suspend fun fetchTaxonomyOnce() {
        if (_state.value.taxonomy != null) return
        val tax = repository.getOrFetchTaxonomy {
            runCatching { api.fetchTaxonomy() }.getOrNull()
        } ?: return
        _state.update { it.copy(taxonomy = tax) }
    }

    fun refresh() {
        inflight?.cancel()
        prefetch?.cancel()
        loadMoreJob?.cancel()
        nextCursor = null
        inflight = viewModelScope.launch {
            // Capture before any suspend so a concurrent toggle of showDeleted
            // can't desync the fetch filter from the UI state.
            val includeDeleted = _state.value.showDeleted
            _state.update { it.copy(loadState = LoadState.Loading, isPaginating = false) }
            try {
                val response = api.fetchList(
                    cursor = null,
                    includeDeleted = includeDeleted,
                )
                // Defend against a cancelled launch resuming past the network
                // call: writing nextCursor / items here would overwrite the
                // newer refresh that triggered our cancellation.
                coroutineContext.ensureActive()
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(
                        it.copy(
                            items = merged,
                            loadState = LoadState.Loaded,
                            hasMore = response.nextCursor != null,
                        )
                    )
                }
                cache.save(merged)
                // Only prune when the cursor chain is exhausted — pruning on the
                // first page would drop read-IDs for older bulletins not yet
                // fetched (e.g., after Auto Backup restore on reinstall).
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                } else {
                    startBackgroundPrefetch(includeDeleted)
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
    private fun startBackgroundPrefetch(includeDeleted: Boolean) {
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
            latest?.let { cache.save(it) }
        }
    }

    fun loadMoreIfNeeded(item: BulletinSummary) {
        val s = _state.value
        if (!s.hasMore || s.isPaginating) return
        val tail = s.displayed.takeLast(5).map { it.id }
        if (item.id !in tail) return
        val cursor = nextCursor ?: return
        prefetch?.cancel()
        _state.update { it.copy(isPaginating = true) }
        loadMoreJob = viewModelScope.launch {
            try {
                val response = api.fetchList(cursor = cursor, includeDeleted = s.showDeleted)
                coroutineContext.ensureActive()
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
                cache.save(merged)
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

    fun setOrgFilter(orgs: Set<String>) =
        _state.update { applyFilters(it.copy(selectedOrgs = orgs)) }

    fun setTagFilter(tags: Set<String>) =
        _state.update { applyFilters(it.copy(selectedTags = tags)) }

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
            .filter {
                if (text.isEmpty()) true
                else it.displayTitle.contains(text, ignoreCase = true) ||
                        it.summary?.contains(text, ignoreCase = true) == true
            }
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
}
