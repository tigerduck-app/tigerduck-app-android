package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.NtustPortalError
import org.ntust.app.tigerduck.network.NtustPortalService
import org.ntust.app.tigerduck.network.model.PortalCategory
import org.ntust.app.tigerduck.network.model.PortalLink
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
    private val demoAccount: DemoAccount,
) : ViewModel() {

    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val message: String) : LoadState
    }

    data class State(
        val items: List<PortalLink> = emptyList(),
        val loadState: LoadState = LoadState.Idle,
        val selectedCategory: PortalCategory? = null,
        val searchText: String = "",
        val displayed: List<PortalLink> = emptyList(),
        /** Set once a tapped link's session is bridged and ready; the screen
         *  observes this to navigate, then calls [consumePendingLink]. */
        val pendingLink: PortalLink? = null,
        /** Set when [openLink] fails to bridge a session for the tapped link — the screen shows
         *  this as a snackbar, then calls [consumeOpenLinkError]. Without this, a failed
         *  [NtustPortalService.ensureWebViewSession] call used to leave the tap looking like it
         *  did nothing at all. */
        val openLinkError: String? = null,
    )

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Everything done on the signed-in account's behalf runs in this scope, so an account
    // change can cancel whatever the previous account still has in flight before it lands
    // in the next account's state.
    private var accountJob = SupervisorJob(viewModelScope.coroutineContext.job)
    private val accountScope get() = viewModelScope + accountJob

    private var hasLoaded = false
    private var lastUseEnglish: Boolean? = null

    init {
        // This screen can be a retained tab that outlives a sign-out, so it has to forget the
        // previous account itself. drop(1): the current value is where this ViewModel starts,
        // not a change.
        viewModelScope.launch {
            authService.authState.drop(1).collect { signedIn ->
                accountJob.cancel()
                accountJob = SupervisorJob(viewModelScope.coroutineContext.job)
                hasLoaded = false
                _state.value = State()
                if (signedIn) lastUseEnglish?.let(::load)
            }
        }
    }

    fun load(useEnglish: Boolean) {
        lastUseEnglish = useEnglish
        if (hasLoaded) return
        val studentId = authService.storedStudentId ?: return
        if (authService.storedPassword == null) return
        // Only once there is an account to load for: a visit while signed out must not stop
        // the load that signing in triggers.
        hasLoaded = true

        accountScope.launch {
            portalService.cachedPortalLinks(studentId, useEnglish)?.let { cached ->
                _state.update { applyFilters(it.copy(items = cached, loadState = LoadState.Loaded)) }
            }
            refresh(useEnglish)
        }
    }

    fun refresh(useEnglish: Boolean) {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId == null || password == null) {
            _state.update { it.copy(loadState = LoadState.Failed(context.getString(R.string.common_not_signed_in))) }
            return
        }
        // The demo account has no real portal to scrape and no server to ask —
        // leave whatever's on screen (nothing) rather than reporting a failure.
        if (demoAccount.isActive) return

        accountScope.launch {
            if (!networkChecker.isAvailable()) return@launch
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val links = portalService.fetchPortalLinks(studentId, password, useEnglish)
                _state.update { applyFilters(it.copy(items = links, loadState = LoadState.Loaded)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NtustPortalError) {
                _state.update {
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
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: context.getString(R.string.information_system_load_failed))) }
            }
        }
    }

    fun setCategory(category: PortalCategory?) =
        _state.update { applyFilters(it.copy(selectedCategory = category)) }

    fun setSearch(text: String) =
        _state.update { applyFilters(it.copy(searchText = text)) }

    /**
     * Bridges the current SSO session's cookies into the WebView's own cookie
     * store, then publishes [link] as pending — the screen observes
     * [State.pendingLink] and navigates once it's non-null. Cookie work needs
     * a coroutine (it may re-run SSO login), so this can't just return a URL
     * to navigate to synchronously.
     */
    fun openLink(link: PortalLink) {
        val studentId = authService.storedStudentId ?: return
        val password = authService.storedPassword ?: return
        accountScope.launch {
            // link.url's own host, not the portal host: NTUST's SSO is
            // per-service, so a warm session on i.ntust.edu.tw does not
            // imply this link's host has ever been visited.
            val result = runCatching {
                portalService.ensureWebViewSession(link.url, studentId, password)
            }
            // runCatching also catches the cancellation an account change sends; that must
            // end this coroutine, not show an error in the next account's screen.
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            val ready = result.getOrDefault(false)
            if (!ready) {
                // This used to just return@launch — a failed or throwing ensureWebViewSession
                // left the tap looking like it did nothing at all, with nothing in logcat either.
                android.util.Log.w(
                    "InformationSystemViewModel",
                    "openLink failed for ${link.url}",
                    result.exceptionOrNull(),
                )
                _state.update {
                    it.copy(openLinkError = context.getString(R.string.information_system_load_failed))
                }
                return@launch
            }
            // A sign-out's WebView cookie wipe is asynchronous; installing this account's cookies
            // before it lands would let it erase them.
            authService.awaitWebViewCookieWipe()
            syncCookiesToWebView(portalService.allSessionCookies())
            _state.update { it.copy(pendingLink = link) }
        }
    }

    fun consumePendingLink() = _state.update { it.copy(pendingLink = null) }

    fun consumeOpenLinkError() = _state.update { it.copy(openLinkError = null) }

    private fun applyFilters(s: State): State {
        val text = s.searchText.trim()
        val displayed = s.items.asSequence()
            .filter { s.selectedCategory == null || it.category == s.selectedCategory }
            .filter { text.isEmpty() || it.name.contains(text, ignoreCase = true) }
            .toList()
        return s.copy(displayed = displayed)
    }
}
