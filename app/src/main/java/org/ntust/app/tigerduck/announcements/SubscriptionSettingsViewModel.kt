package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import javax.inject.Inject

/**
 * Drives SubscriptionSettingsScreen.
 *
 * Behaviour mirrors iOS BulletinNotificationSettingsView:
 *  - Rules are auto-saved (PUT replaces the whole snapshot) after every
 *    upsert / delete / toggle. The user never taps a save button — matches
 *    the Settings/Notes app idiom of "I tweaked, I swiped back, it's saved".
 *  - The "default seed" path applies a starter rule from the taxonomy's
 *    `default_tags` when the rule list is empty and the user explicitly opts
 *    in. iOS uses the same `seedDefault(from:)` shape.
 *  - Push diagnostics (FCM token present, server registration timestamp,
 *    last error) are exposed verbatim so the screen can render the same
 *    green-tick / orange-warning status rows that iOS shows.
 */
@HiltViewModel
class SubscriptionSettingsViewModel @Inject constructor(
    private val api: BulletinApiClient,
    private val identity: PushIdentity,
    private val pushRegistration: PushRegistrationService,
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
        val diagnostic: PushDiagnostic = PushDiagnostic(false, false, null, null),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { fetchTaxonomy() }
        load()
        pushRegistration.diagnostic
            .onEach { d -> _state.update { it.copy(diagnostic = d) } }
            .launchIn(viewModelScope)
    }

    private suspend fun fetchTaxonomy() {
        runCatching { api.fetchTaxonomy() }
            .onSuccess { tax -> _state.update { it.copy(taxonomy = tax) } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val response = api.fetchSubscriptions(identity.deviceId())
                _state.update {
                    it.copy(rules = response.rules, loadState = LoadState.Loaded)
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
            s.copy(rules = rules)
        }
        save()
    }

    fun deleteRule(index: Int) {
        val s = _state.value
        if (index !in s.rules.indices) return
        _state.update {
            it.copy(rules = it.rules.toMutableList().also { l -> l.removeAt(index) })
        }
        save()
    }

    fun toggleEnabled(index: Int) {
        val s = _state.value
        if (index !in s.rules.indices) return
        _state.update {
            it.copy(
                rules = it.rules.mapIndexed { i, r ->
                    if (i == index) r.copy(enabled = !r.enabled) else r
                },
            )
        }
        save()
    }

    /**
     * Seed a "follow the defaults" rule when the user starts from zero.
     * No-op if the rules list is non-empty or the taxonomy hasn't loaded —
     * matches iOS `seedDefault(from:)`.
     */
    fun applyDefaultRules() {
        val s = _state.value
        if (s.rules.isNotEmpty()) return
        val tax = s.taxonomy ?: return
        if (tax.defaultTags.isEmpty()) return
        val seeded = SubscriptionRule(
            name = null,
            orgs = emptyList(),
            tags = tax.defaultTags,
            mode = "OR",
            enabled = true,
        )
        _state.update { it.copy(rules = listOf(seeded)) }
        save()
    }

    private fun save() {
        viewModelScope.launch {
            _state.update { it.copy(saveState = SaveState.Saving) }
            try {
                val response = api.putSubscriptions(identity.deviceId(), _state.value.rules)
                _state.update {
                    it.copy(rules = response.rules, saveState = SaveState.Saved)
                }
            } catch (e: Exception) {
                _state.update { it.copy(saveState = SaveState.Failed(e.message ?: "error")) }
            }
        }
    }

    fun clearSaveState() {
        _state.update { it.copy(saveState = SaveState.Idle) }
    }
}
