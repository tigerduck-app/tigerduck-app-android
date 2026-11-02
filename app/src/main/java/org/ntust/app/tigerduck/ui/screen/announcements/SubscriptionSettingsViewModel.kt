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
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.shared.account.SignIn
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
    private val session: AccountSession,
) : ViewModel() {

    @OptIn(StartsAccountWork::class)
    private fun signInNow() = session.current

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

    /**
     * The sign-in whose rules the page shows: the one its last load was read under, null until one
     * lands. An edit of them goes under it, and an answer lands only while it stands. The sign-out
     * closes the page only once it is on top, so one kept on another tab's back stack shows the
     * departed student's rules: none of them may go with the next student's session, and no
     * answer of theirs may land on the next student's page.
     */
    private var shownFor: SignIn? = null

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
        val signIn = signInNow()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val response = api.fetchSubscriptions(signIn)
                land(signIn) {
                    shownFor = signIn
                    _state.update { it.copy(rules = response.items, loadState = LoadState.Loaded) }
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
        val signIn = editing() ?: return
        sendEdit(
            signIn,
            send = {
                if (rule.id == null) api.createSubscription(rule, signIn) else api.updateSubscription(rule.id, rule, signIn)
            },
            saved = { s, saved ->
                val rules = s.rules.toMutableList()
                if (replacingIndex != null && replacingIndex in rules.indices) {
                    rules[replacingIndex] = saved
                } else {
                    rules += saved
                }
                s.copy(rules = rules, saveState = SaveState.Saved)
            },
            failed = { s, e -> s.copy(saveState = SaveState.Failed(e.message ?: "error")) },
        )
    }

    /**
     * Delete rule at [index]. If the rule has no server-side id it was never
     * persisted, so remove it locally without a network call.
     */
    fun deleteRule(index: Int) {
        val signIn = editing() ?: return
        val s = _state.value
        if (index !in s.rules.indices) return
        val rule = s.rules[index]
        _state.update { it.copy(rules = it.rules.toMutableList().also { l -> l.removeAt(index) }) }
        val id = rule.id ?: return
        sendEdit(
            signIn,
            send = { api.deleteSubscription(id, signIn) },
            saved = { s2, _ -> s2.copy(saveState = SaveState.Saved) },
            // Re-insert the rule on failure so the user can retry.
            failed = { s2, e ->
                val restored = s2.rules.toMutableList().also { it.add(index.coerceAtMost(it.size), rule) }
                s2.copy(rules = restored, saveState = SaveState.Failed(e.message ?: "error"))
            },
        )
    }

    /**
     * Sends one `/v3` edit, [send], made under [signIn], and puts its answer on the page while that
     * sign-in stands ([land]): [saved] with what the backend kept, or [failed] with why it refused.
     * Saving until then, and idle again if the page goes first.
     */
    private fun <T> sendEdit(
        signIn: SignIn,
        send: suspend () -> T,
        saved: (State, T) -> State,
        failed: (State, Exception) -> State,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(saveState = SaveState.Saving) }
            val kept = try {
                send()
            } catch (e: CancellationException) {
                _state.update { it.copy(saveState = SaveState.Idle) }
                throw e
            } catch (e: Exception) {
                land(signIn) { _state.update { failed(it, e) } }
                return@launch
            }
            land(signIn) { _state.update { saved(it, kept) } }
        }
    }

    /**
     * The sign-in an edit of the rules on the page goes under: the one they were read under. Null
     * while none has landed, and once that sign-in has ended, which starts the page over.
     */
    private fun editing(): SignIn? {
        val signIn = shownFor ?: return null
        if (signIn.isCurrent) return signIn
        startOver()
        return null
    }

    /**
     * Puts an answer to a request of [signIn]'s on the page with [write], unless that sign-in has
     * ended: in one step with the sign-out's end ([SignIn.ifCurrent]), so a sign-out either comes
     * after the write or finds the page starting over.
     */
    private fun land(signIn: SignIn, write: () -> Unit) {
        if (signIn.ifCurrent(write) == null) startOver()
    }

    /**
     * Drops the rules of a sign-in that has ended, and what was waiting on them, and reads the
     * rules again under the sign-in that stands. With none, the read fails, and the page offers to
     * try again.
     */
    private fun startOver() {
        shownFor = null
        _state.update { it.copy(rules = emptyList(), saveState = SaveState.Idle) }
        load()
    }

    /**
     * Monotonic token per rule id, bumped on every local edit to that rule.
     *
     * A PATCH echo may only be written back if no newer edit to the same rule
     * has been made since it was sent. Without it, two taps in flight together
     * settle on whichever response happens to land last, which is not
     * necessarily the later tap.
     */
    private val editGeneration = mutableMapOf<Int, Int>()

    fun toggleEnabled(index: Int) {
        val signIn = editing() ?: return
        val before = _state.value
        if (index !in before.rules.indices) return
        val previous = before.rules[index]
        val toggled = previous.copy(enabled = !previous.enabled)

        // Flip locally first. Both taps of a double-tap otherwise read the
        // same pre-tap value and send the same request, so the switch settles
        // on the opposite of what the user asked for.
        _state.update { st ->
            val rules = st.rules.toMutableList()
            if (index in rules.indices) rules[index] = toggled
            st.copy(rules = rules)
        }

        // Never persisted, so there is nothing to PATCH and no id to key a
        // generation on — the create path already replaces the row wholesale.
        val id = toggled.id ?: return upsertRule(toggled, replacingIndex = index)

        val generation = (editGeneration[id] ?: 0) + 1
        editGeneration[id] = generation

        sendEdit(
            signIn,
            send = { api.updateSubscription(id, toggled, signIn) },
            // A newer tap is already in flight; its response is the one
            // that should win, so drop this echo instead of overwriting.
            saved = { st, saved ->
                if (editGeneration[id] != generation) {
                    st
                } else {
                    st.copy(rules = st.replacing(id, saved), saveState = SaveState.Saved)
                }
            },
            failed = { st, e ->
                // Put back what the server last agreed to, so the switch does
                // not sit on a state that was never persisted.
                if (editGeneration[id] != generation) {
                    st
                } else {
                    st.copy(rules = st.replacing(id, previous), saveState = SaveState.Failed(e.message ?: "error"))
                }
            },
        )
    }

    private fun State.replacing(id: Int, rule: SubscriptionRule): List<SubscriptionRule> {
        val at = rules.indexOfFirst { it.id == id }
        if (at < 0) return rules
        return rules.toMutableList().also { it[at] = rule }
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
