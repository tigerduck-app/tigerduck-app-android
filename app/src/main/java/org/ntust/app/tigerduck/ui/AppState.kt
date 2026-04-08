package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.CalendarService
import org.ntust.app.tigerduck.network.LoadingState
import org.ntust.app.tigerduck.network.NtustSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncJob: Job? = null

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    @Suppress("unused")
    val loadingState: StateFlow<LoadingState> = _loadingState

    private var hasCompletedOnboardingState by mutableStateOf(prefs.hasCompletedOnboarding)

    var hasCompletedOnboarding: Boolean
        get() = hasCompletedOnboardingState
        set(value) {
            if (hasCompletedOnboardingState == value) return
            hasCompletedOnboardingState = value
            prefs.hasCompletedOnboarding = value
        }

    private var accentColorHexState by mutableIntStateOf(prefs.accentColorHex)

    var accentColorHex: Int
        get() = accentColorHexState
        set(value) {
            if (accentColorHexState == value) return
            accentColorHexState = value
            prefs.accentColorHex = value
        }

    val accentColor: Color
        get() = Color(0xFF000000L or (accentColorHex.toLong() and 0xFFFFFFL))

    private var rememberAnnouncementFilterState by mutableStateOf(prefs.rememberAnnouncementFilter)

    var rememberAnnouncementFilter: Boolean
        get() = rememberAnnouncementFilterState
        set(value) {
            if (rememberAnnouncementFilterState == value) return
            rememberAnnouncementFilterState = value
            prefs.rememberAnnouncementFilter = value
        }

    @Suppress("unused")
    var savedAnnouncementDepartments: Set<String>
        get() = prefs.savedAnnouncementDepartments
        set(value) { prefs.savedAnnouncementDepartments = value }

    private var showAbsoluteAssignmentTimeState by mutableStateOf(prefs.showAbsoluteAssignmentTime)

    var showAbsoluteAssignmentTime: Boolean
        get() = showAbsoluteAssignmentTimeState
        set(value) {
            if (showAbsoluteAssignmentTimeState == value) return
            showAbsoluteAssignmentTimeState = value
            prefs.showAbsoluteAssignmentTime = value
        }

    private var browserPreferenceState by mutableStateOf(prefs.browserPreference)

    var browserPreference: String
        get() = browserPreferenceState
        set(value) {
            if (browserPreferenceState == value) return
            browserPreferenceState = value
            prefs.browserPreference = value
        }

    private var timeSliderStyleState by mutableStateOf(prefs.timeSliderStyle)

    var timeSliderStyle: String
        get() = timeSliderStyleState
        set(value) {
            if (timeSliderStyleState == value) return
            timeSliderStyleState = value
            prefs.timeSliderStyle = value
        }

    private var invertSliderDirectionState by mutableStateOf(prefs.invertSliderDirection)

    var invertSliderDirection: Boolean
        get() = invertSliderDirectionState
        set(value) {
            if (invertSliderDirectionState == value) return
            invertSliderDirectionState = value
            prefs.invertSliderDirection = value
        }

    private var notifyAssignmentsState by mutableStateOf(prefs.notifyAssignments)

    var notifyAssignments: Boolean
        get() = notifyAssignmentsState
        set(value) {
            if (notifyAssignmentsState == value) return
            notifyAssignmentsState = value
            prefs.notifyAssignments = value
        }

    private var libraryFeatureEnabledState by mutableStateOf(prefs.libraryFeatureEnabled)

    var libraryFeatureEnabled: Boolean
        get() = libraryFeatureEnabledState
        set(value) {
            if (libraryFeatureEnabledState == value) return
            libraryFeatureEnabledState = value
            prefs.libraryFeatureEnabled = value
        }

    private var configuredTabsState by mutableStateOf(prefs.configuredTabs)

    var configuredTabs: List<AppFeature>
        get() = configuredTabsState
        set(value) {
            if (configuredTabsState == value) return
            configuredTabsState = value
            prefs.configuredTabs = value
        }

    val isNtustLoggedIn: Boolean get() = authService.isNtustAuthenticated
    @Suppress("unused")
    val isLibraryLoggedIn: Boolean get() = credentials.isLibraryTokenValid

    fun completeOnboarding() {
        hasCompletedOnboarding = true
    }

    @Suppress("unused")
    fun backgroundSync(
        fetchCourses: suspend () -> Unit,
        fetchAssignments: suspend () -> Unit
    ) {
        if (!hasCompletedOnboarding) return
        syncJob?.cancel()
        syncJob = scope.launch {
            _loadingState.value = LoadingState.LOADING
            val coursesJob = async { runCatching { fetchCourses() } }
            val assignmentsJob = async { runCatching { fetchAssignments() } }
            val calendarJob = async { runCatching { calendarService.fetchAndParseICS() } }
            val coursesResult = coursesJob.await()
            val assignmentsResult = assignmentsJob.await()
            val schoolEventsResult = calendarJob.await()

            val anySucceeded = coursesResult.isSuccess || assignmentsResult.isSuccess || schoolEventsResult.isSuccess

            val cached = dataCache.loadCalendarEvents().toMutableList()
            var changed = false

            if (assignmentsResult.isSuccess) {
                val moodleEvents = dataCache.loadAssignments().map { assignment ->
                    CalendarEvent(
                        eventId = "moodle-${assignment.assignmentId}",
                        title = assignment.title,
                        date = assignment.dueDate,
                        sourceRaw = EventSource.MOODLE.raw
                    )
                }
                cached.removeAll { it.sourceRaw == EventSource.MOODLE.raw }
                cached.addAll(moodleEvents)
                changed = true
            }

            if (schoolEventsResult.isSuccess) {
                val newSchoolEvents = schoolEventsResult.getOrDefault(emptyList())
                if (newSchoolEvents.isNotEmpty()) {
                    cached.removeAll { it.sourceRaw == EventSource.SCHOOL.raw }
                    cached.addAll(newSchoolEvents)
                    changed = true
                }
            }

            if (changed) {
                dataCache.saveCalendarEvents(cached)
            }

            _loadingState.value = if (anySucceeded) LoadingState.LOADED else LoadingState.ERROR
        }
    }
}
