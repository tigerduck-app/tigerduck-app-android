package org.ntust.app.tigerduck.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.CalendarService
import org.ntust.app.tigerduck.network.LoadingState
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
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
    val calendarService: CalendarService,
    val systemPermissions: SystemPermissions,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    @param:ApplicationContext private val appContext: Context,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncJob: Job? = null

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    @Suppress("unused")
    val loadingState: StateFlow<LoadingState> = _loadingState

    /**
     * `true` when on-device data could not be migrated cleanly (e.g. Keystore
     * corruption wiped the credential store, or the user downgraded the app).
     * The UI shows a non-dismissable dialog and calls [performFullReset] on
     * confirm.
     */
    private val _needsUserReset = MutableStateFlow(false)
    val needsUserReset: StateFlow<Boolean> = _needsUserReset

    init {
        when (DataMigration(appContext, prefs, credentials).run()) {
            DataMigration.Outcome.NeedsUserReset -> _needsUserReset.value = true
            DataMigration.Outcome.Ok -> Unit
        }
    }

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
            widgetUpdater.requestUpdate()
        }

    /**
     * Accent color resolved for the current theme mode. [accentColorHex] always
     * stores the canonical (light) hex; in dark mode we return the paired
     * dark variant so the tint stays vibrant against dark surfaces.
     */
    fun accentColor(isDark: Boolean = TigerDuckTheme.isDarkMode): Color {
        val hex = if (isDark) {
            AppPreferences.accentDarkVariant(accentColorHex)
        } else {
            accentColorHex
        }
        return Color(0xFF000000L or (hex.toLong() and 0xFFFFFFL))
    }

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

    private var themeModeState by mutableStateOf(prefs.themeMode)

    /** One of "system", "dark", "light". */
    var themeMode: String
        get() = themeModeState
        set(value) {
            if (themeModeState == value) return
            themeModeState = value
            prefs.themeMode = value
            widgetUpdater.requestUpdate()
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

    // Transient signal from the library-shortcut widget: when the user taps
    // the widget while the library feature is disabled, we navigate to
    // Settings and flip this so SettingsScreen surfaces an "enable first"
    // dialog. Not persisted — lives only within the process.
    var pendingLibraryEnablePrompt by mutableStateOf(false)

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

    /**
     * Wipe every piece of on-device user state (prefs, credentials, JSON
     * cache) and return the user to onboarding. Called from the reset
     * dialog after migration returns [DataMigration.Outcome.NeedsUserReset].
     */
    fun performFullReset() {
        scope.launch {
            runCatching { dataCache.clearAllUserData() }
            credentials.clearAll()
            prefs.clearAllPrefs()
            // Re-stamp the schema so the dialog doesn't re-fire on next launch.
            prefs.dataSchemaVersion = DataMigration.CURRENT_SCHEMA

            // The mutableState caches above were seeded from prefs at init
            // time. Re-read so the UI shows defaults instead of ghost values
            // from the wiped store.
            hasCompletedOnboardingState = prefs.hasCompletedOnboarding
            accentColorHexState = prefs.accentColorHex
            showAbsoluteAssignmentTimeState = prefs.showAbsoluteAssignmentTime
            browserPreferenceState = prefs.browserPreference
            themeModeState = prefs.themeMode
            invertSliderDirectionState = prefs.invertSliderDirection
            notifyAssignmentsState = prefs.notifyAssignments
            libraryFeatureEnabledState = prefs.libraryFeatureEnabled
            configuredTabsState = prefs.configuredTabs

            _needsUserReset.value = false
        }
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

            val hasCachedData = cached.isNotEmpty()
            _loadingState.value = if (anySucceeded || hasCachedData) LoadingState.LOADED else LoadingState.ERROR
        }
    }
}
