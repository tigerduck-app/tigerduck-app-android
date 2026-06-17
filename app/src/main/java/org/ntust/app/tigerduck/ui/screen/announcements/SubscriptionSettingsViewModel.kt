package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.notification.SystemPermissions
import javax.inject.Inject

/**
 * Drives SubscriptionSettingsScreen.
 *
 * Behaviour mirrors iOS BulletinNotificationSettingsView:
 *  - Rules are saved immediately on each upsert / delete / toggle via v3
 *    CRUD endpoints (POST, PATCH, DELETE on /bulletin-subscriptions).
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
    private val repository: BulletinRepository,
    val systemPermissions: SystemPermissions,
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { fetchTaxonomy() }
        load()
    }

    private suspend fun fetchTaxonomy() {
        val tax = repository.getOrFetchTaxonomy {
            runCatching { api.fetchTaxonomy() }.getOrNull()
        } ?: return
        _state.update { it.copy(taxonomy = tax) }
    }

    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val response = api.fetchSubscriptions()
                _state.update {
                    it.copy(rules = response.items, loadState = LoadState.Loaded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: "error")) }
            }
        }
    }

    /**
     * Upsert a rule: POST if new (no id), PATCH if existing.
     * Replaces [replacingIndex] in the local list on success.
     */
    fun upsertRule(rule: SubscriptionRule, replacingIndex: Int?) {
        viewModelScope.launch {
            _state.update { it.copy(saveState = SaveState.Saving) }
            try {
                val saved = if (rule.id == null) {
                    api.createSubscription(rule)
                } else {
                    api.updateSubscription(rule.id, rule)
                }
                _state.update { s ->
                    val rules = s.rules.toMutableList()
                    if (replacingIndex != null && replacingIndex in rules.indices) {
                        rules[replacingIndex] = saved
                    } else {
                        rules += saved
                    }
                    s.copy(rules = rules, saveState = SaveState.Saved)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(saveState = SaveState.Idle) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saveState = SaveState.Failed(e.message ?: "error")) }
            }
        }
    }

    /**
     * Delete rule at [index]. If the rule has no server-side id it was never
     * persisted, so remove it locally without a network call.
     */
    fun deleteRule(index: Int) {
        val s = _state.value
        if (index !in s.rules.indices) return
        val rule = s.rules[index]
        _state.update { it.copy(rules = it.rules.toMutableList().also { l -> l.removeAt(index) }) }
        val id = rule.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(saveState = SaveState.Saving) }
            try {
                api.deleteSubscription(id)
                _state.update { it.copy(saveState = SaveState.Saved) }
            } catch (e: CancellationException) {
                _state.update { it.copy(saveState = SaveState.Idle) }
                throw e
            } catch (e: Exception) {
                // Re-insert the rule on failure so the user can retry.
                _state.update { s2 ->
                    val restored = s2.rules.toMutableList().also { it.add(index.coerceAtMost(it.size), rule) }
                    s2.copy(rules = restored, saveState = SaveState.Failed(e.message ?: "error"))
                }
            }
        }
    }

    fun toggleEnabled(index: Int) {
        val s = _state.value
        if (index !in s.rules.indices) return
        val toggled = s.rules[index].copy(enabled = !s.rules[index].enabled)
        upsertRule(toggled, replacingIndex = index)
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
        upsertRule(seeded, replacingIndex = null)
    }

    fun clearSaveState() {
        _state.update { it.copy(saveState = SaveState.Idle) }
    }
}
