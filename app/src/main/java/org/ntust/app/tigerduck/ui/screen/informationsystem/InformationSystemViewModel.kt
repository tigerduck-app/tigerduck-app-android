package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.NtustPortalError
import org.ntust.app.tigerduck.network.NtustPortalService
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.model.PortalCategory
import org.ntust.app.tigerduck.network.model.PortalLink
import org.ntust.app.tigerduck.shared.account.SignIn
import java.io.IOException
import javax.inject.Inject

/**
 * Drives [InformationSystemScreen]. Cache-first load (an instant paint from
 * the last scrape, if any) then a background refresh, following the same
 * shape as `AnnouncementsViewModel` / `ScoreViewModel`.
 */
@HiltViewModel
class InformationSystemViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val portalService: NtustPortalService,
    private val networkChecker: NetworkChecker,
    private val sessions: AccountSessions,
) : ViewModel() {

    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val message: String) : LoadState
    }

    data class State(
        val items: List<PortalLink> = emptyList(),
        /** The other language's page, which a search in that language reads too ([PortalSearch]). */
        val otherItems: List<PortalLink> = emptyList(),
        /** Whether [items] is the English page. */
        val englishPage: Boolean = false,
        val loadState: LoadState = LoadState.Idle,
        val selectedCategory: PortalCategory? = null,
        val searchText: String = "",
        val displayed: List<PortalLink> = emptyList(),
    )

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Everything done on the signed-in account's behalf runs in this scope, so an account
    // change can cancel whatever the previous account still has in flight before it lands
    // in the next account's state.
    private var accountJob = SupervisorJob(viewModelScope.coroutineContext.job)
    private val accountScope get() = viewModelScope + accountJob

    // Which student and language the shown links are for, null while nothing is loaded for any.
    private var loadedFor: Pair<String, Boolean>? = null
    private var lastUseEnglish: Boolean? = null

    init {
        // A retained tab follows every change, including a sign-out and sign-in that land
        // before it runs again. Subscribe before a change can leave the old student's page.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.school.changes.collect { accountChanged() }
        }
    }

    private fun accountChanged() {
        accountJob.cancel()
        accountJob = SupervisorJob(viewModelScope.coroutineContext.job)
        loadedFor = null
        _state.value = State()
        if (authService.authState.value) lastUseEnglish?.let(::load)
    }

    @OptIn(StartsAccountWork::class)
    fun load(useEnglish: Boolean) {
        lastUseEnglish = useEnglish
        val studentId = authService.storedStudentId ?: return
        if (authService.storedPassword == null) return
        if (loadedFor == studentId to useEnglish) return
        // The other language's names and URLs are not this language's; its load goes with them.
        if (loadedFor?.second != useEnglish) {
            accountJob.cancel()
            accountJob = SupervisorJob(viewModelScope.coroutineContext.job)
            _state.update {
                applyFilters(it.copy(items = emptyList(), otherItems = emptyList(), englishPage = useEnglish, loadState = LoadState.Idle))
            }
        }
        // Only once there is an account to load for: a visit while signed out must not stop
        // the load that signing in triggers.
        loadedFor = studentId to useEnglish

        val signIn = sessions.school.current
        accountScope.launch {
            portalService.cachedPortalLinks(studentId, useEnglish)?.let { cached ->
                if (isCurrent(signIn, studentId, useEnglish)) {
                    _state.update { applyFilters(it.copy(items = cached, loadState = LoadState.Loaded)) }
                }
            }
            portalService.cachedPortalLinks(studentId, !useEnglish)?.let { cached ->
                if (isCurrent(signIn, studentId, useEnglish)) _state.update { applyFilters(it.copy(otherItems = cached)) }
            }
            refresh(useEnglish, signIn)
        }
    }

    // Whether a result for [studentId] in this language is still the one the page is for.
    private fun isCurrent(signIn: SignIn, studentId: String, useEnglish: Boolean) =
        signIn.isCurrent &&
            authService.storedStudentId == studentId &&
            (lastUseEnglish ?: useEnglish) == useEnglish

    @OptIn(StartsAccountWork::class)
    fun refresh(useEnglish: Boolean) {
        refresh(useEnglish, sessions.school.current)
    }

    private fun refresh(useEnglish: Boolean, signIn: SignIn) {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId == null || password == null) {
            _state.update { it.copy(loadState = LoadState.Failed(context.getString(R.string.common_not_signed_in))) }
            return
        }

        accountScope.launch {
            if (!isCurrent(signIn, studentId, useEnglish)) return@launch
            if (!networkChecker.isAvailable()) {
                forgetIfNothingLoaded()
                return@launch
            }
            // Another run's Loading is not what this one goes back to, which would never end.
            val shown = _state.value.loadState.takeUnless { it is LoadState.Loading } ?: LoadState.Idle
            _state.update { it.copy(loadState = LoadState.Loading) }
            // A result is published only if the account and language it started for still hold.
            fun publish(change: (State) -> State) {
                if (isCurrent(signIn, studentId, useEnglish)) _state.update(change)
            }
            try {
                val links = portalService.fetchPortalLinks(studentId, password, useEnglish, signIn)
                publish { applyFilters(it.copy(items = links, loadState = LoadState.Loaded)) }
                // Then the other page, which a search in its language reads too. It only widens the
                // search: failed, it leaves this page's list as it is.
                try {
                    val other = portalService.fetchPortalLinks(studentId, password, !useEnglish, signIn)
                    publish { applyFilters(it.copy(otherItems = other)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NtustPortalError) {
                publish {
                    it.copy(
                        loadState = LoadState.Failed(
                            when (e) {
                                is NtustPortalError.NotAuthenticated -> context.getString(R.string.common_not_signed_in)
                                is NtustPortalError.RedirectedToSSO -> context.getString(R.string.information_system_load_failed)
                                is NtustPortalError.InvalidResponse -> context.getString(R.string.information_system_load_failed)
                                is NtustPortalError.ParseFailed -> context.getString(R.string.information_system_load_failed)
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                // No network leaves what's on screen, as the check above does. That is the demo
                // account's case too: DemoModeInterceptor refuses as the radio being off would.
                val after = if (e.isOffline) shown else LoadState.Failed(e.message ?: context.getString(R.string.information_system_load_failed))
                publish { it.copy(loadState = after) }
            }
            if (isCurrent(signIn, studentId, useEnglish)) forgetIfNothingLoaded()
        }
    }

    /** Nothing to show: the next visit tries again rather than finding this load done. */
    private fun forgetIfNothingLoaded() {
        if (_state.value.items.isEmpty()) loadedFor = null
    }

    fun setCategory(category: PortalCategory?) =
        _state.update { applyFilters(it.copy(selectedCategory = category)) }

    fun setSearch(text: String) =
        _state.update { applyFilters(it.copy(searchText = text)) }

    private fun applyFilters(s: State): State {
        val pairs = PortalSearch.pairs(s.items, s.otherItems)
            .filter { s.selectedCategory == null || (it.shown ?: it.other)?.category == s.selectedCategory }
        return s.copy(displayed = PortalSearch.search(pairs, s.searchText, uiEnglish = s.englishPage))
    }
}

/** Whether the network gave no answer: to the portal's own call, or to the SSO sign-in ahead of it, which wraps it. */
private val Exception.isOffline: Boolean get() = this is IOException || this is SsoLoginError.NetworkError
