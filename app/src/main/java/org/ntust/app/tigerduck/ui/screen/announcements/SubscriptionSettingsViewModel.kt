package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Drives SubscriptionSettingsScreen.
 *
 * Behavior mirrors iOS BulletinNotificationSettingsView:
 *  - Rules are saved immediately on each upsert / delete / toggle: the edit
 *    shows at once and the whole list goes in one PUT, each rule by the
 *    `client_id` this device made for it, one save at a time
 *    ([SubscriptionListSaves]), as the backend has no per-rule route.
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
    /**
     * The app's saves of the list, which outlive the page. The default is one of its own, for
     * tests that are not about it.
     */
    private val listSaves: SubscriptionListSaves =
        SubscriptionListSaves(api, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
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
     * answer to a save of theirs may touch the page, which may show the next student's by then.
     */
    private var shownFor: SignIn? = null

    /**
     * What the page has done with its reads, counted on its thread for tests: an answer is read on
     * another thread and handled on the page's a moment later, so a test waits on these, not on
     * time. Nothing in the app reads them. The saves count their own ([SubscriptionListSaves]).
     */
    @VisibleForTesting
    internal class Probe {
        /** Answers to the page's reads handled, refusals too, whether they landed or not. */
        val answersHandled = AtomicInteger()
    }

    @VisibleForTesting
    internal val probe = Probe()

    init {
        viewModelScope.launch { fetchTaxonomy() }
        viewModelScope.launch { listSaves.ledger.collect(::mirror) }
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
        // A read that answers after a save has settled may predate that save's answer.
        val readAt = listSaves.settled(signIn)
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val response = api.fetchSubscriptions(signIn)
                // The list's PUT keeps each rule by its client_id, so every rule on the page has one.
                val rules = response.items.map { it.withClientId() }
                land(signIn) {
                    shownFor = signIn
                    // What the page shows, and how the saves stand, are the app's saves' ledger.
                    val ledger = listSaves.loaded(signIn, rules, readAt)
                    _state.update { it.copy(rules = ledger.rules, loadState = LoadState.Loaded, saveState = ledger.save) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: "error")) }
            }
            probe.answersHandled.incrementAndGet()
        }
    }

    /**
     * Upsert a rule. An existing rule replaces the one its ids name, wherever it stands by then
     * ([replacingRule]); [replacingIndex], where the editor found it, is only where one gone goes
     * back. A new rule takes [replacingIndex], or the end.
     */
    fun upsertRule(rule: SubscriptionRule, replacingIndex: Int?) {
        val signIn = editing() ?: return
        editList(signIn) { it.replacingRule(rule.withClientId(), replacingIndex) }
    }

    /** Delete rule at [index]. */
    fun deleteRule(index: Int) {
        val signIn = editing() ?: return
        if (index !in _state.value.rules.indices) return
        editList(signIn) { it.removing(index) }
    }

    /**
     * Deletes [rule], the editor's, wherever it stands now: named by its ids, as a refusal may have
     * put a rule back before it since the editor opened. One gone already has nothing to delete.
     */
    fun deleteRule(rule: SubscriptionRule) {
        val at = _state.value.rules.indexOfFirst { it.isSameRuleAs(rule) }
        if (at >= 0) deleteRule(at)
    }

    /** [edit] of the list on the page, shown at once and saved by the app's saves. */
    private fun editList(signIn: SignIn, edit: (List<SubscriptionRule>) -> List<SubscriptionRule>) {
        val ledger = listSaves.edit(signIn, edit(_state.value.rules))
        _state.update { it.copy(rules = ledger.rules, saveState = ledger.save) }
    }

    /**
     * Shows the app's saves' ledger whenever it moves, while it is the ledger of the sign-in whose
     * rules the page shows: an answer or a refusal, landed while the page was open or, for a page
     * opened again, before.
     */
    private fun mirror(ledger: SubscriptionListSaves.Ledger?) {
        if (ledger == null || ledger.signIn !== shownFor) return
        _state.update { it.copy(rules = ledger.rules, saveState = ledger.save) }
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
     * Puts the answer to a read of [signIn]'s on the page with [write], unless that sign-in has
     * ended: in one step with the sign-out's end ([SignIn.ifCurrent]), so a sign-out either comes
     * after the write or finds the page reading again under the sign-in that stands. No read of
     * another sign-in's is out by then: each read cancels the one before.
     */
    private fun land(signIn: SignIn, write: () -> Unit) {
        if (signIn.ifCurrent(write) == null) startOver()
    }

    /**
     * Drops the rules of a sign-in that has ended, and reads the rules again under the sign-in that
     * stands. With none, the read fails, and the page offers to try again. No edit is made of the
     * emptied list, so no save sends it: a save carries the list its edit left.
     */
    private fun startOver() {
        shownFor = null
        _state.update { it.copy(rules = emptyList(), saveState = SaveState.Idle) }
        load()
    }

    fun toggleEnabled(index: Int) {
        val signIn = editing() ?: return
        if (index !in _state.value.rules.indices) return
        editList(signIn) { it.toggling(index) }
    }

    /**
     * Seed a "follow the defaults" rule when the user starts from zero.
     * No-op if the rules list is non-empty or the taxonomy hasn't loaded —
     * matches iOS `seedDefault(from:)`.
     */
    fun applyDefaultRules() {
        val s = _state.value
        if (s.rules.isNotEmpty()) return
        upsertRule(s.taxonomy?.defaultRule() ?: return, replacingIndex = null)
    }

    fun clearSaveState() {
        _state.update { it.copy(saveState = SaveState.Idle) }
        // The app's saves keep how the last one went for a page opened again; this one showed it.
        shownFor?.let(listSaves::outcomeSeen)
    }
}

