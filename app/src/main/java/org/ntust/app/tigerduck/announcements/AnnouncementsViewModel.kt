package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    ) {
        /** Items to display after applying the read-only filter on top of [filtered]. */
        val displayed: List<BulletinSummary>
            get() = if (unreadOnly) filtered.filter { it.id !in readIds } else filtered

        val hasUnread: Boolean
            get() = filtered.any { it.id !in readIds }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        readState.readIds
            .onEach { ids -> _state.update { it.copy(readIds = ids) } }
            .launchIn(viewModelScope)
    }

    private var nextCursor: Int? = null
    private var inflight: Job? = null
    private var prefetch: Job? = null
    private var loadMoreJob: Job? = null
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
        val cached = repository.taxonomy()
        if (cached != null) {
            _state.update { it.copy(taxonomy = cached) }
            return
        }
        val tax = runCatching { api.fetchTaxonomy() }.getOrNull() ?: return
        repository.setTaxonomy(tax)
        _state.update { it.copy(taxonomy = tax) }
    }

    fun refresh() {
        inflight?.cancel()
        prefetch?.cancel()
        loadMoreJob?.cancel()
        nextCursor = null
        inflight = viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val response = api.fetchList(
                    cursor = null,
                    includeDeleted = _state.value.showDeleted,
                )
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
                readState.prune(merged.map { it.id })
                if (response.nextCursor != null) startBackgroundPrefetch()
            } catch (e: Exception) {
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: "error")) }
            }
        }
    }

    private fun startBackgroundPrefetch() {
        prefetch?.cancel()
        prefetch = viewModelScope.launch {
            // Walk the cursor chain in the background so the user doesn't see
            // a load spinner mid-scroll. Cap at 5 pages — anything older is
            // legitimately a "load more" tap.
            var pages = 0
            while (pages < 5 && nextCursor != null) {
                pages++
                delay(150)
                val cursor = nextCursor ?: break
                val response = runCatching {
                    api.fetchList(cursor = cursor, includeDeleted = _state.value.showDeleted)
                }.getOrNull() ?: break
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(it.copy(items = merged, hasMore = response.nextCursor != null))
                }
                cache.save(merged)
                if (response.nextCursor == null) break
            }
        }
    }

    fun loadMoreIfNeeded(item: BulletinSummary) {
        val s = _state.value
        if (!s.hasMore || s.isPaginating) return
        val tail = s.filtered.takeLast(5).map { it.id }
        if (item.id !in tail) return
        val cursor = nextCursor ?: return
        prefetch?.cancel()
        _state.update { it.copy(isPaginating = true) }
        loadMoreJob = viewModelScope.launch {
            try {
                val response = api.fetchList(cursor = cursor, includeDeleted = s.showDeleted)
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
        _state.update { it.copy(unreadOnly = value) }

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
        return s.copy(filtered = filtered)
    }

    private fun sortedUnique(items: List<BulletinSummary>): List<BulletinSummary> {
        val byId = LinkedHashMap<Int, BulletinSummary>(items.size)
        // Later entries win — server fetch overwrites stale cache row for
        // the same id (e.g. importance/title_clean updates after LLM run).
        for (item in items) byId[item.id] = item
        return byId.values.sortedWith(
            compareByDescending<BulletinSummary> { it.postedAt?.let(::parseInstant) ?: Instant.EPOCH }
                .thenByDescending { it.id }
        )
    }

    private fun parseInstant(raw: String): Instant =
        try { Instant.parse(raw) } catch (_: Exception) { Instant.EPOCH }
}
