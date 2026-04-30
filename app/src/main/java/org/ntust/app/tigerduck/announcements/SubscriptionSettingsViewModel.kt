package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.push.PushIdentity
import javax.inject.Inject

/**
 * Drives SubscriptionSettingsScreen. Rules are edited as a draft list and
 * PUT to the server as one snapshot (matching the iOS contract). Save and
 * load both cover the first-launch race where the device row isn't
 * registered yet — load() returns an empty list, save() surfaces the error.
 */
@HiltViewModel
class SubscriptionSettingsViewModel @Inject constructor(
    private val api: BulletinApiClient,
    private val identity: PushIdentity,
) : ViewModel() {

    sealed interface LoadState {
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val message: String) : LoadState
    }

    sealed interface SaveState {
        data object Idle : SaveState
        data object Saving : SaveState
        data object Saved : SaveState
        data class Failed(val message: String) : SaveState
    }

    data class State(
        val rules: List<SubscriptionRule> = emptyList(),
        val taxonomy: TaxonomyResponse? = null,
        val loadState: LoadState = LoadState.Loading,
        val saveState: SaveState = SaveState.Idle,
        val isDirty: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { fetchTaxonomy() }
        load()
    }

    private suspend fun fetchTaxonomy() {
        runCatching { api.fetchTaxonomy() }
            .onSuccess { tax -> _state.update { it.copy(taxonomy = tax) } }
    }

    fun load() {
        if (_state.value.isDirty) return
        viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val response = api.fetchSubscriptions(identity.deviceId())
                _state.update {
                    it.copy(rules = response.rules, loadState = LoadState.Loaded, isDirty = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: "error")) }
            }
        }
    }

    fun upsertRule(rule: SubscriptionRule, replacingIndex: Int?) {
        _state.update { s ->
            val rules = s.rules.toMutableList()
            if (replacingIndex != null && replacingIndex in rules.indices) {
                rules[replacingIndex] = rule
            } else {
                rules += rule
            }
            s.copy(rules = rules, isDirty = true)
        }
    }

    fun deleteRule(index: Int) {
        _state.update { s ->
            if (index !in s.rules.indices) s
            else s.copy(rules = s.rules.toMutableList().also { it.removeAt(index) }, isDirty = true)
        }
    }

    fun toggleEnabled(index: Int) {
        _state.update { s ->
            if (index !in s.rules.indices) s
            else s.copy(
                rules = s.rules.mapIndexed { i, r ->
                    if (i == index) r.copy(enabled = !r.enabled) else r
                },
                isDirty = true,
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(saveState = SaveState.Saving) }
            try {
                val response = api.putSubscriptions(identity.deviceId(), _state.value.rules)
                _state.update {
                    it.copy(rules = response.rules, saveState = SaveState.Saved, isDirty = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(saveState = SaveState.Failed(e.message ?: "error")) }
            }
        }
    }
}
