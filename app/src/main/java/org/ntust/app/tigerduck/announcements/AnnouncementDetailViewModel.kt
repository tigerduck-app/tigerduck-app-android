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
) : ViewModel() {

    val bulletinId: Int = checkNotNull(savedState["id"])

    sealed interface State {
        data object Loading : State
        data class Loaded(val detail: BulletinDetail) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = State.Loading
        viewModelScope.launch {
            try {
                _state.value = State.Loaded(api.fetchDetail(bulletinId))
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "error")
            }
        }
    }
}
