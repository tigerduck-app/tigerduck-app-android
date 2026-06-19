package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CourseNameScale
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.CalendarService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
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
    private val dataMigration: DataMigration,
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
        when (dataMigration.run()) {
            DataMigration.Outcome.NeedsUserReset -> _needsUserReset.value = true
            DataMigration.Outcome.Ok -> Unit
        }
        // TODO: remove in a future release once unfinished features ship (or
        // enough time has passed that no users still have these entries
        // persisted). Strips features that were once tab-pinnable but route
        // to a placeholder, so the bottom bar never shows a "coming soon" tab.
        val saved = prefs.configuredTabs
        val cleaned = saved.filterNot { it in AppFeature.unfinishedFeatures }
        if (cleaned != saved) {
            prefs.configuredTabs = cleaned.ifEmpty { AppFeature.defaultTabs }
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

    private var rememberAnnouncementFilterState by mutableStateOf(prefs.rememberAnnouncementFilter)

    /**
     * When on, [org.ntust.app.tigerduck.announcements.AnnouncementsViewModel]
     * restores the persisted org-filter selection on next launch. Flipping
     * this off wipes the saved selection so re-enabling later doesn't
     * silently revive a stale filter.
     */
    var rememberAnnouncementFilter: Boolean
        get() = rememberAnnouncementFilterState
        set(value) {
            if (rememberAnnouncementFilterState == value) return
            rememberAnnouncementFilterState = value
            prefs.rememberAnnouncementFilter = value
            if (!value) prefs.savedAnnouncementOrgs = emptySet()
        }

    private var useEnglishCourseAbbreviationState by mutableStateOf(prefs.useEnglishCourseAbbreviation)

    var useEnglishCourseAbbreviation: Boolean
        get() = useEnglishCourseAbbreviationState
        set(value) {
            if (useEnglishCourseAbbreviationState == value) return
            useEnglishCourseAbbreviationState = value
            prefs.useEnglishCourseAbbreviation = value
        }

    private var useEnglishClassroomAbbreviationState by mutableStateOf(prefs.useEnglishClassroomAbbreviation)

    var useEnglishClassroomAbbreviation: Boolean
        get() = useEnglishClassroomAbbreviationState
        set(value) {
            if (useEnglishClassroomAbbreviationState == value) return
            useEnglishClassroomAbbreviationState = value
            prefs.useEnglishClassroomAbbreviation = value
        }

    private var classroomMandarinDisplayState by mutableStateOf(prefs.classroomMandarinDisplay)

    /** One of "original", "pinyin", "translated". */
    var classroomMandarinDisplay: String
        get() = classroomMandarinDisplayState
        set(value) {
            if (classroomMandarinDisplayState == value) return
            classroomMandarinDisplayState = value
            prefs.classroomMandarinDisplay = value
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

    private var appLanguageState by mutableStateOf(prefs.appLanguage)

    /** One of "system", "zh-Hant", "en". */
    var appLanguage: String
        get() = appLanguageState
        set(value) {
            val normalized = AppLanguageManager.normalize(value)
            if (appLanguageState == normalized) return
            appLanguageState = normalized
            prefs.appLanguage = normalized
            AppLanguageManager.apply(normalized)
        }

    private var invertSliderDirectionState by mutableStateOf(prefs.invertSliderDirection)

    var invertSliderDirection: Boolean
        get() = invertSliderDirectionState
        set(value) {
            if (invertSliderDirectionState == value) return
            invertSliderDirectionState = value
            prefs.invertSliderDirection = value
        }

    private var courseNameScaleState by mutableFloatStateOf(
        prefs.courseNameScale.also { TigerDuckTheme.setCourseNameScale(it) }
    )

    /**
     * Live-observable course-name scale (0.8…1.6×). Reads recompose every
     * call site that touches it (e.g. `CourseCard`'s name `Text`), so the
     * slider in `CourseNameSizeSettingsScreen` updates the class table
     * mid-drag. Always normalized on write so the persisted value never
     * drifts off the 0.05× ticks. Also mirrored to [TigerDuckTheme] so
     * unrelated component files can read it without DI, and pushes a widget
     * refresh so the launcher tiles re-read the new scale on the next render.
     */
    var courseNameScale: Float
        get() = courseNameScaleState
        set(value) {
            val normalized = CourseNameScale.normalize(value)
            if (courseNameScaleState == normalized) return
            courseNameScaleState = normalized
            prefs.courseNameScale = normalized
            TigerDuckTheme.setCourseNameScale(normalized)
            widgetUpdater.requestUpdate()
        }

    private var rotationModeState by mutableStateOf(prefs.rotationMode)

    /** One of "auto", "enabled", "disabled". */
    var rotationMode: String
        get() = rotationModeState
        set(value) {
            if (rotationModeState == value) return
            rotationModeState = value
            prefs.rotationMode = value
        }

    private var notifyAssignmentsState by mutableStateOf(prefs.notifyAssignments)

    var notifyAssignments: Boolean
        get() = notifyAssignmentsState
        set(value) {
            if (notifyAssignmentsState == value) return
            notifyAssignmentsState = value
            prefs.notifyAssignments = value
        }

    private var notifyAssignmentOffsetsState by mutableStateOf(prefs.notifyAssignmentOffsets)

    var notifyAssignmentOffsets: Set<AssignmentReminderOffset>
        get() = notifyAssignmentOffsetsState
        set(value) {
            if (notifyAssignmentOffsetsState == value) return
            notifyAssignmentOffsetsState = value
            prefs.notifyAssignmentOffsets = value
        }

    private var libraryFeatureEnabledState by mutableStateOf(prefs.libraryFeatureEnabled)

    var libraryFeatureEnabled: Boolean
        get() = libraryFeatureEnabledState
        set(value) {
            if (libraryFeatureEnabledState == value) return
            libraryFeatureEnabledState = value
            prefs.libraryFeatureEnabled = value
        }

    private var flipToLibraryEnabledState by mutableStateOf(prefs.flipToLibraryEnabled)

    var flipToLibraryEnabled: Boolean
        get() = flipToLibraryEnabledState
        set(value) {
            if (flipToLibraryEnabledState == value) return
            flipToLibraryEnabledState = value
            prefs.flipToLibraryEnabled = value
        }

    private var cloudSyncEnabledState by mutableStateOf(prefs.cloudSyncEnabled)

    var cloudSyncEnabled: Boolean
        get() = cloudSyncEnabledState
        set(value) {
            if (cloudSyncEnabledState == value) return
            cloudSyncEnabledState = value
            prefs.cloudSyncEnabled = value
        }

    private var disableScreenCaptureProtectionState by
            mutableStateOf(prefs.disableScreenCaptureProtection)

    var disableScreenCaptureProtection: Boolean
        get() = disableScreenCaptureProtectionState
        set(value) {
            if (disableScreenCaptureProtectionState == value) return
            disableScreenCaptureProtectionState = value
            prefs.disableScreenCaptureProtection = value
        }

    // Transient signal from the library-shortcut widget: when the user taps
    // the widget while the library feature is disabled, we navigate to
    // Settings and flip this so SettingsScreen surfaces an "enable first"
    // dialog. Not persisted — lives only within the process.
    var pendingLibraryEnablePrompt by mutableStateOf(false)

    // Transient signal from signed-out empty states (Home, ClassTable,
    // Calendar, Score): when the user taps the lock icon we navigate to
    // Settings and flip this so the NTUST account row pulses, drawing the
    // user's attention to the action they need to take. Consumed (cleared)
    // by SettingsScreen after the animation finishes.
    var pendingNtustSignInHighlight by mutableStateOf(false)

    private var configuredTabsState by mutableStateOf(prefs.configuredTabs)

    var configuredTabs: List<AppFeature>
        get() = configuredTabsState
        set(value) {
            if (configuredTabsState == value) return
            configuredTabsState = value
            prefs.configuredTabs = value
        }

    private val hapticStrengthStates: SnapshotStateMap<HapticScenario, Int> =
        mutableStateMapOf<HapticScenario, Int>().apply {
            HapticScenario.tunable.forEach { put(it, prefs.hapticStrength(it)) }
        }

    private val hapticDurationStates: SnapshotStateMap<HapticScenario, Int> =
        mutableStateMapOf<HapticScenario, Int>().apply {
            HapticScenario.tunable.forEach { put(it, prefs.hapticDurationMs(it)) }
        }

    fun hapticStrength(scenario: HapticScenario): Int =
        hapticStrengthStates[scenario] ?: scenario.defaultStrengthPct

    fun setHapticStrength(scenario: HapticScenario, value: Int) {
        if (!scenario.userTunable) return
        val clamped = value.coerceIn(0, 100)
        if (hapticStrengthStates[scenario] == clamped) return
        hapticStrengthStates[scenario] = clamped
        prefs.setHapticStrength(scenario, clamped)
    }

    fun hapticDurationMs(scenario: HapticScenario): Int =
        hapticDurationStates[scenario] ?: scenario.defaultDurationMs

    fun setHapticDurationMs(scenario: HapticScenario, value: Int) {
        if (!scenario.userTunable) return
        val clamped = value.coerceIn(
            AppPreferences.MIN_TUNABLE_HAPTIC_DURATION_MS,
            AppPreferences.MAX_TUNABLE_HAPTIC_DURATION_MS,
        )
        if (hapticDurationStates[scenario] == clamped) return
        hapticDurationStates[scenario] = clamped
        prefs.setHapticDurationMs(scenario, clamped)
    }

    fun resetHapticToDefault(scenario: HapticScenario) {
        if (!scenario.userTunable) return
        setHapticStrength(scenario, scenario.defaultStrengthPct)
        setHapticDurationMs(scenario, scenario.defaultDurationMs)
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
            rememberAnnouncementFilterState = prefs.rememberAnnouncementFilter
            useEnglishCourseAbbreviationState = prefs.useEnglishCourseAbbreviation
            useEnglishClassroomAbbreviationState = prefs.useEnglishClassroomAbbreviation
            classroomMandarinDisplayState = prefs.classroomMandarinDisplay
            browserPreferenceState = prefs.browserPreference
            themeModeState = prefs.themeMode
            appLanguageState = prefs.appLanguage
            invertSliderDirectionState = prefs.invertSliderDirection
            courseNameScaleState = prefs.courseNameScale.also {
                TigerDuckTheme.setCourseNameScale(it)
            }
            rotationModeState = prefs.rotationMode
            notifyAssignmentsState = prefs.notifyAssignments
            notifyAssignmentOffsetsState = prefs.notifyAssignmentOffsets
            libraryFeatureEnabledState = prefs.libraryFeatureEnabled
            flipToLibraryEnabledState = prefs.flipToLibraryEnabled
            cloudSyncEnabledState = prefs.cloudSyncEnabled
            disableScreenCaptureProtectionState = prefs.disableScreenCaptureProtection
            configuredTabsState = prefs.configuredTabs
            HapticScenario.tunable.forEach { scenario ->
                hapticStrengthStates[scenario] = prefs.hapticStrength(scenario)
                hapticDurationStates[scenario] = prefs.hapticDurationMs(scenario)
            }

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

            // Sync degrades gracefully on partial failure, but the reasons
            // must reach logcat — "sync silently does nothing" was previously
            // undiagnosable in the field.
            coursesResult.exceptionOrNull()?.let {
                android.util.Log.w("AppState", "backgroundSync: courses fetch failed", it)
            }
            assignmentsResult.exceptionOrNull()?.let {
                android.util.Log.w("AppState", "backgroundSync: assignments fetch failed", it)
            }
            schoolEventsResult.exceptionOrNull()?.let {
                android.util.Log.w("AppState", "backgroundSync: calendar fetch failed", it)
            }

            val anySucceeded =
                coursesResult.isSuccess || assignmentsResult.isSuccess || schoolEventsResult.isSuccess

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
            _loadingState.value =
                if (anySucceeded || hasCachedData) LoadingState.LOADED else LoadingState.ERROR
        }
    }
}
