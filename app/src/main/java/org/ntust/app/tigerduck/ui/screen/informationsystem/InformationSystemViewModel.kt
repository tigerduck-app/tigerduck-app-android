package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    )

    val isLoggedIn: StateFlow<Boolean> = authService.authState

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var hasLoaded = false

    fun load(useEnglish: Boolean) {
        if (hasLoaded) return
        hasLoaded = true
        val studentId = authService.storedStudentId ?: return
        val password = authService.storedPassword ?: return

        viewModelScope.launch {
            val cached = runCatching {
                portalService.fetchPortalLinks(studentId, password, useEnglish, forceRefresh = false)
            }.getOrNull()
            if (cached != null) {
                _state.update { applyFilters(it.copy(items = cached, loadState = LoadState.Loaded)) }
            }
            refresh(useEnglish, force = false)
        }
    }

    fun refresh(useEnglish: Boolean, force: Boolean = true) {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId == null || password == null) {
            _state.update { it.copy(loadState = LoadState.Failed(context.getString(R.string.common_not_signed_in))) }
            return
        }
        // The demo account has no real portal to scrape and no server to ask —
        // leave whatever's on screen (nothing) rather than reporting a failure.
        if (demoAccount.isActive) return

        viewModelScope.launch {
            if (!networkChecker.isAvailable()) return@launch
            _state.update { it.copy(loadState = LoadState.Loading) }
            try {
                val links = portalService.fetchPortalLinks(studentId, password, useEnglish, forceRefresh = force)
                _state.update { applyFilters(it.copy(items = links, loadState = LoadState.Loaded)) }
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
        viewModelScope.launch {
            // link.url's own host, not the portal host: NTUST's SSO is
            // per-service, so a warm session on i.ntust.edu.tw does not
            // imply this link's host has ever been visited.
            val ready = runCatching {
                portalService.ensureWebViewSession(link.url, studentId, password)
            }.getOrDefault(false)
            if (!ready) return@launch
            syncCookiesToWebView(portalService.allSessionCookies())
            _state.update { it.copy(pendingLink = link) }
        }
    }

    fun consumePendingLink() = _state.update { it.copy(pendingLink = null) }

    private fun applyFilters(s: State): State {
        val text = s.searchText.trim()
        val displayed = s.items.asSequence()
            .filter { s.selectedCategory == null || it.category == s.selectedCategory }
            .filter { text.isEmpty() || it.name.contains(text, ignoreCase = true) }
            .toList()
        return s.copy(displayed = displayed)
    }
}
