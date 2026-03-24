package com.tigerduck.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.tigerduck.app.auth.AuthService
import com.tigerduck.app.data.cache.DataCache
import com.tigerduck.app.data.model.AppFeature
import com.tigerduck.app.data.preferences.AppPreferences
import com.tigerduck.app.data.preferences.CredentialManager
import com.tigerduck.app.network.CalendarService
import com.tigerduck.app.network.LoadingState
import com.tigerduck.app.network.NtustSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppState @Inject constructor(
    val authService: AuthService,
    val sessionManager: NtustSessionManager,
    val prefs: AppPreferences,
    val credentials: CredentialManager,
    val dataCache: DataCache,
    val calendarService: CalendarService
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    var hasCompletedOnboarding: Boolean
        get() = prefs.hasCompletedOnboarding
        set(value) { prefs.hasCompletedOnboarding = value }

    var accentColorHex: Int
        get() = prefs.accentColorHex
        set(value) { prefs.accentColorHex = value }

    val accentColor: Color
        get() = Color(0xFF000000 or accentColorHex.toLong())

    var rememberAnnouncementFilter: Boolean
        get() = prefs.rememberAnnouncementFilter
        set(value) { prefs.rememberAnnouncementFilter = value }

    var savedAnnouncementDepartments: Set<String>
        get() = prefs.savedAnnouncementDepartments
        set(value) { prefs.savedAnnouncementDepartments = value }

    var showAbsoluteAssignmentTime: Boolean
        get() = prefs.showAbsoluteAssignmentTime
        set(value) { prefs.showAbsoluteAssignmentTime = value }

    var configuredTabs: List<AppFeature>
        get() = prefs.configuredTabs
        set(value) { prefs.configuredTabs = value }

    val isNtustLoggedIn: Boolean get() = authService.isNtustAuthenticated
    val isLibraryLoggedIn: Boolean get() = credentials.isLibraryTokenValid

    fun completeOnboarding() {
        hasCompletedOnboarding = true
    }

    fun backgroundSync(
        fetchCourses: suspend () -> Unit,
        fetchAssignments: suspend () -> Unit
    ) {
        if (!hasCompletedOnboarding) return
        scope.launch {
            _loadingState.value = LoadingState.LOADING
            val coursesJob = async { runCatching { fetchCourses() } }
            val assignmentsJob = async { runCatching { fetchAssignments() } }
            val calendarJob = async {
                runCatching {
                    val schoolEvents = calendarService.fetchAndParseICS()
                    if (schoolEvents.isNotEmpty()) {
                        val cached = dataCache.loadCalendarEvents().toMutableList()
                        cached.removeAll { it.sourceRaw == "school" }
                        cached.addAll(schoolEvents)
                        dataCache.saveCalendarEvents(cached)
                    }
                }
            }
            coursesJob.await()
            assignmentsJob.await()
            calendarJob.await()
            _loadingState.value = LoadingState.LOADED
        }
    }
}
