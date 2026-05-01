package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnnouncementDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: BulletinApiClient,
    private val cache: BulletinCache,
    private val repository: BulletinRepository,
    private val readState: BulletinReadStateStore,
) : ViewModel() {

    val bulletinId: Int = checkNotNull(savedState["id"])

    sealed interface State {
        /** No summary in cache yet — typically a deep-link cold open. */
        data class Loading(val taxonomy: TaxonomyResponse?) : State

        /** Have the list summary; body is still loading. Renders chrome instantly. */
        data class Partial(
            val summary: BulletinSummary,
            val taxonomy: TaxonomyResponse?,
        ) : State

        data class Loaded(val detail: BulletinDetail, val taxonomy: TaxonomyResponse?) : State
        data class Failed(
            val summary: BulletinSummary?,
            val taxonomy: TaxonomyResponse?,
            val message: String,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading(null))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Opening the detail page is the read signal — matches iOS's
        // `readState.markRead` in BulletinDetailView.task(id:).
        readState.markRead(bulletinId)

        val cachedTaxonomy = repository.taxonomy()
        val cachedDetail = repository.detail(bulletinId)
        val cachedSummary = repository.summary(bulletinId)

        _state.value = when {
            cachedDetail != null -> State.Loaded(cachedDetail, cachedTaxonomy)
            cachedSummary != null -> State.Partial(cachedSummary, cachedTaxonomy)
            else -> State.Loading(cachedTaxonomy)
        }

        // Disk fallback when the in-memory repo is empty (cold start, deep
        // link). Mirrors iOS DataCache.loadBulletinDetail — re-opens after a
        // process kill have no spinner at all.
        if (cachedDetail == null) {
            viewModelScope.launch {
                val disk = cache.loadDetail(bulletinId) ?: return@launch
                if (repository.detail(bulletinId) != null) return@launch
                repository.putDetail(disk)
                _state.update { current ->
                    if (current is State.Loaded) current
                    else State.Loaded(disk, repository.taxonomy())
                }
            }
        }

        if (cachedTaxonomy == null) {
            viewModelScope.launch {
                runCatching { api.fetchTaxonomy() }
                    .onSuccess { tax ->
                        repository.setTaxonomy(tax)
                        _state.update { current ->
                            when (current) {
                                is State.Loading -> current.copy(taxonomy = tax)
                                is State.Partial -> current.copy(taxonomy = tax)
                                is State.Loaded -> current.copy(taxonomy = tax)
                                is State.Failed -> current.copy(taxonomy = tax)
                            }
                        }
                    }
            }
        }

        refresh()
    }

    fun reload() {
        if (_state.value is State.Failed) {
            // Show a chrome-only state again so the body slot transitions back
            // to a loader/summary preview while we retry.
            val summary = repository.summary(bulletinId)
            _state.value = if (summary != null) {
                State.Partial(summary, repository.taxonomy())
            } else {
                State.Loading(repository.taxonomy())
            }
        }
        refresh()
    }

    private var refreshJob: Job? = null

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val detail = api.fetchDetail(bulletinId)
                repository.putDetail(detail)
                cache.saveDetail(detail)
                _state.update { State.Loaded(detail, repository.taxonomy()) }
            } catch (e: Exception) {
                _state.update { current ->
                    // Offline-first: when we already have a body on screen,
                    // suppress the failure so the user keeps reading. Only
                    // surface Failed if there's nothing rendered yet.
                    when (current) {
                        is State.Loaded -> current
                        else -> State.Failed(
                            summary = repository.summary(bulletinId),
                            taxonomy = repository.taxonomy(),
                            message = e.message ?: "error",
                        )
                    }
                }
            }
        }
    }
}
