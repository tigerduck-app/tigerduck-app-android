package org.ntust.app.tigerduck.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.notification.SystemPermissions
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
                val response = api.fetchSubscriptions(identity.deviceId())
                _state.update {
                    it.copy(rules = response.rules, loadState = LoadState.Loaded)
                }
            } catch (e: CancellationException) {
                throw e
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

    private var saveJob: Job? = null
    // Monotonic token: every save() bumps it; the success handler only writes
    // back the server echo if no newer save() has displaced this one. Guards
    // the narrow window where execute() returns on the IO thread before the
    // job's cancellation is delivered — without this, the older PUT's echo
    // can overwrite the user's most-recent un-saved edits.
    private var saveGeneration = 0

    private fun save() {
        saveJob?.cancel()
        val generation = ++saveGeneration
        saveJob = viewModelScope.launch {
            // Coalesce rapid edits into a single PUT. Cancelling the coroutine
            // does NOT abort an in-flight OkHttp call (execute() is a blocking
            // I/O call; the cancel only fires once it returns), so two
            // back-to-back edits would race on the wire and the older PUT
            // could land last, leaving server state stale. The delay lets the
            // cancel-on-relaunch land before any request is sent.
            delay(SAVE_DEBOUNCE_MS)
            // updateAndGet pins the rules snapshot atomically with the
            // Saving flip so a concurrent upsert/delete can't shrink the PUT
            // body between the state read and the network call, which would
            // let the response echo overwrite the user's last edit.
            val rulesToSave = _state
                .updateAndGet { it.copy(saveState = SaveState.Saving) }
                .rules
            try {
                val response = api.putSubscriptions(identity.deviceId(), rulesToSave)
                if (generation == saveGeneration) {
                    _state.update {
                        it.copy(rules = response.rules, saveState = SaveState.Saved)
                    }
                }
            } catch (e: CancellationException) {
                // A new save() is replacing this one; reset Saving so the UI
                // doesn't appear stuck if the next job is still in its debounce.
                _state.update { it.copy(saveState = SaveState.Idle) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saveState = SaveState.Failed(e.message ?: "error")) }
            }
        }
    }

    fun clearSaveState() {
        _state.update { it.copy(saveState = SaveState.Idle) }
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 300L
    }
}
