package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnnouncementDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: BulletinApiClient,
    private val readState: BulletinReadStateStore,
) : ViewModel() {

    val bulletinId: Int = checkNotNull(savedState["id"])

    sealed interface State {
        data object Loading : State
        data class Loaded(val detail: BulletinDetail, val taxonomy: TaxonomyResponse?) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var taxonomy: TaxonomyResponse? = null

    init {
        // Opening the detail page is the read signal — matches iOS's
        // `readState.markRead` in BulletinDetailView.task(id:).
        readState.markRead(bulletinId)
        viewModelScope.launch {
            // Best-effort taxonomy fetch — labels render as raw ids if it
            // never resolves.
            runCatching { api.fetchTaxonomy() }.onSuccess { taxonomy = it }
        }
        reload()
    }

    fun reload() {
        _state.value = State.Loading
        viewModelScope.launch {
            try {
                val detail = api.fetchDetail(bulletinId)
                _state.value = State.Loaded(detail, taxonomy)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "error")
            }
        }
    }
}
