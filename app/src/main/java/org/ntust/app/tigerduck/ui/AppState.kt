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
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CourseNameScale
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.preferences.DisplayScale
import org.ntust.app.tigerduck.data.preferences.LibraryTabKeepMigration
import org.ntust.app.tigerduck.data.preferences.effectiveCloudSyncEnabled
import org.ntust.app.tigerduck.library.FlipToLibraryDefaultOffMigration
import org.ntust.app.tigerduck.library.FlipToLibrarySetting
import org.ntust.app.tigerduck.library.LibrarySession
import org.ntust.app.tigerduck.network.CalendarService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.theme.AccentGradient
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What [AppState.cloudSyncEnabled]'s setter should actually apply when asked
 * to write [requested] on [flavor] — the writer-side mirror of
 * [effectiveCloudSyncEnabled]. fdroid ships without Google Play Services and
 * can never run TigerSync's course-sync / server-push pipeline regardless of
 * what is stored (see that function's doc); [effectiveCloudSyncEnabled]
 * already guarantees every *reader* agrees on that. Without this, a future
 * caller setting [AppState.cloudSyncEnabled] to `true` on fdroid would make
 * every reader of it see sync as on, since the setter otherwise just mirrors
 * whatever it is given. A request to turn it *off* always takes effect, on
 * every flavor.
 *
 * [flavor] defaults to [BuildConfig.FLAVOR] for the one production call
 * site; tests pass it explicitly, the same reason
 * [effectiveCloudSyncEnabled] does.
 */
internal fun effectiveCloudSyncWrite(requested: Boolean, flavor: String = BuildConfig.FLAVOR): Boolean =
    effectiveCloudSyncEnabled(storedValue = requested, flavor = flavor)

/**
 * Whether to store [AppFeature.previousDefaultTabs] as this install's bar now
 * that the default has Mail in Calendar's place: only for an install that
 * predates the change ([hasCompletedOnboarding]) and only when its bar is
 * untouched — a stored bar is the user's own and stays as it is.
 */
internal fun keepsPreviousDefaultTabs(hasCompletedOnboarding: Boolean, hasStoredTabs: Boolean): Boolean =
    hasCompletedOnboarding && !hasStoredTabs

/**
 * The bottom bar's one-time fix-ups at startup, in their order.
 */
internal fun settleBottomBar(prefs: AppPreferences) {
    // An untouched bottom bar isn't stored — it follows
    // AppFeature.defaultTabs — so when 2.3.0 swapped the default's Calendar
    // for Mail, an existing user's bar would change under them. Keep their
    // old default once instead; 2.3.0's What's New asks whether to
    // switch. Checked once per install: a fresh install's first launch,
    // before onboarding, has nothing to keep and gets the new default.
    if (!prefs.previousDefaultTabsChecked) {
        if (keepsPreviousDefaultTabs(prefs.hasCompletedOnboarding, prefs.hasStoredConfiguredTabs)) {
            prefs.configuredTabs = AppFeature.previousDefaultTabs
        }
        prefs.previousDefaultTabsChecked = true
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
    // After the pin above, so a pre-2.3.0 bar it stores stays as it is:
    // What's New asks about Mail, then about Library.
    LibraryTabKeepMigration.runIfNeeded(prefs)
}

@Singleton
class AppState @Inject constructor(
    val authService: AuthService,
    val sessionManager: NtustSessionManager,
    val prefs: AppPreferences,
    val credentials: CredentialManager,
    val dataCache: DataCache,
    val calendarService: CalendarService,
    val systemPermissions: SystemPermissions,
    private val dataMigration: DataMigration,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    private val pushRegistration: org.ntust.app.tigerduck.push.PushRegistrationService,
    private val demoAccount: org.ntust.app.tigerduck.demo.DemoAccount,
    private val mailAccount: org.ntust.app.tigerduck.mail.MailAccount,
    val librarySession: LibrarySession,
    private val wearBridge: org.ntust.app.tigerduck.wear.WearScheduleBridge,
    private val browserData: org.ntust.app.tigerduck.browser.BrowserDataCleaner,
    private val courseChangeStore: org.ntust.app.tigerduck.coursechanges.CourseChangeStore,
) {
    /**
     * Whether the demo account was signed in when this process started.
     *
     * Sampled once, for what is decided once per process — the onboarding
     * skip below, and rewriting the demo data. The demo sign-in switches the
     * network and the status dots itself, without waiting for a restart.
     */
    private val demoMode = demoAccount.isActive

    init {
        if (demoMode) {
            org.ntust.app.tigerduck.ui.component.ServerStatusTracker.enterDemoMode()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncJob: Job? = null

    init {
        if (demoMode) scope.launch { demoAccount.reapply() }
    }

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
        // Already run — and cached — by TigerDuckApp.onCreate, which is what
        // guarantees migrations beat every non-Activity cache reader. This
        // call only reads the verdict so the reset prompt can fire.
        when (dataMigration.run()) {
            DataMigration.Outcome.NeedsUserReset -> _needsUserReset.value = true
            DataMigration.Outcome.Ok -> Unit
        }
        settleBottomBar(prefs)
        // Before flipToLibraryEnabled is read below, so the switch it may
        // store is the one this process starts with.
        FlipToLibraryDefaultOffMigration.runIfNeeded(prefs)
    }

    private var hasCompletedOnboardingState by mutableStateOf(prefs.hasCompletedOnboarding)

    /**
     * Demo mode reports the wizard as done without writing the preference.
     * A demo session restarted before the wizard was finished is not sent back
     * through it: the sign-in it would ask for has already happened.
     * Not persisting it keeps a device that leaves demo mode showing the
     * wizard again, which is what a signed-out install should do.
     */
    var hasCompletedOnboarding: Boolean
        get() = hasCompletedOnboardingState || demoMode
        set(value) {
            if (hasCompletedOnboardingState == value) return
            hasCompletedOnboardingState = value
            prefs.hasCompletedOnboarding = value
        }

    private var onboardingVersionState by mutableIntStateOf(prefs.onboardingVersion)

    /**
     * True for an install that finished an older wizard than this build ships.
     *
     * The user stays signed in throughout: this reads a plain preference and
     * the wizard's sign-in page recognises a live session, so nothing in the
     * re-run touches stored credentials. See [AppPreferences.onboardingVersion]
     * for why this is not done by clearing [hasCompletedOnboarding].
     *
     * Demo mode is excluded for the same reason it reports the wizard as done
     * — a demo session must not be interrupted by it.
     */
    val needsOnboardingRerun: Boolean
        get() = hasCompletedOnboardingState && !demoMode &&
            onboardingVersionState < AppPreferences.ONBOARDING_VERSION

    /** Whether the wizard owns the screen: never seen, or seen too long ago. */
    val showOnboarding: Boolean
        get() = !hasCompletedOnboarding || needsOnboardingRerun

    // The widgets draw the swatch, and redraw only when asked.
    private val accentSetting = AccentSetting(prefs, onFixedChange = { widgetUpdater.requestUpdate() })

    /**
     * The accent swatch last picked, as its canonical (light) hex. It stays
     * while the wallpaper's colors are picked, and is what every surface that
     * cannot follow them draws; see [AccentPolicy].
     */
    val accentColorHex: Int
        get() = accentSetting.lightHex

    /**
     * Whether the app's screens draw the wallpaper's colors: picked in
     * Settings, on Android 12 or later.
     */
    val usesWallpaperColors: Boolean
        get() = accentSetting.appFollowsWallpaper

    /** Picks the accent swatch of canonical (light) hex [lightHex], in place of the wallpaper too. */
    fun pickAccentColor(lightHex: Int) = accentSetting.pick(lightHex)

    /** Picks the wallpaper's colors for the app's screens, keeping the swatch for the rest. */
    fun pickWallpaperColors() = accentSetting.pickWallpaper()

    /**
     * The accent as the current theme mode draws it. [accentColorHex] always
     * stores the canonical (light) hex; in dark mode we return the paired
     * dark variant so the tint stays vibrant against dark surfaces, and in
     * light mode a tone dark enough for the light cards, which for Orange,
     * Green and Teal is darker than their swatches (see [AccentPolicy.fixedColor]).
     */
    fun accentColor(isDark: Boolean = TigerDuckTheme.isDarkMode): Color =
        AccentPolicy.fixedColor(accentColorHex, isDark)

    /**
     * The end of the gradient picked, as its canonical (light) hex, or null for
     * a single color. [accentColorHex] is its start.
     */
    val accentGradientEndHex: Int?
        get() = accentSetting.gradientEnd

    /** The way the accent gradient runs. */
    val accentGradientDirection: AccentGradientDirection
        get() = accentSetting.gradientDirection

    /** Picks the gradient from [startHex] to [endHex], canonical (light) hexes, in place of the wallpaper too. */
    fun pickAccentGradient(startHex: Int, endHex: Int) = accentSetting.pickGradient(startHex, endHex)

    /** Picks the way the accent gradient runs. */
    fun pickAccentGradientDirection(direction: AccentGradientDirection) = accentSetting.pickDirection(direction)

    /** The gradient picked as the current theme mode draws it, or null for a single color. */
    fun accentGradient(isDark: Boolean = TigerDuckTheme.isDarkMode): AccentGradient? =
        accentGradientEndHex?.let { AccentGradient(AccentPolicy.fixedColor(it, isDark), accentGradientDirection) }

    /** What text on the accent draws in, for a color of the user's own; null for the theme's own. */
    fun accentOnColor(isDark: Boolean = TigerDuckTheme.isDarkMode): Color? =
        AccentPolicy.onAccent(accentColorHex, isDark)

    private val visualPresetSetting =
        VisualPresetSetting(prefs, onChange = { widgetUpdater.requestUpdate() })

    /**
     * The interface style picked in Settings > Display. The app root provides
     * it to every screen through [org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy].
     */
    var visualPreset: VisualPreset
        get() = visualPresetSetting.value
        set(value) {
            visualPresetSetting.value = value
        }

    private var showAbsoluteAssignmentTimeState by mutableStateOf(prefs.showAbsoluteAssignmentTime)

    var showAbsoluteAssignmentTime: Boolean
        get() = showAbsoluteAssignmentTimeState
        set(value) {
            if (showAbsoluteAssignmentTimeState == value) return
            showAbsoluteAssignmentTimeState = value
            prefs.showAbsoluteAssignmentTime = value
        }

    private var alwaysShowAllPeriodsState by mutableStateOf(prefs.alwaysShowAllPeriods)

    /** Keep every period on the timetable even when empty. */
    var alwaysShowAllPeriods: Boolean
        get() = alwaysShowAllPeriodsState
        set(value) {
            if (alwaysShowAllPeriodsState == value) return
            alwaysShowAllPeriodsState = value
            prefs.alwaysShowAllPeriods = value
        }

    private var showClassroomInClassTableState by mutableStateOf(prefs.showClassroomInClassTable)

    /** Print each course's room in the corner of its class-table cell. */
    var showClassroomInClassTable: Boolean
        get() = showClassroomInClassTableState
        set(value) {
            if (showClassroomInClassTableState == value) return
            showClassroomInClassTableState = value
            prefs.showClassroomInClassTable = value
        }

    private var pinTodayCoursesOnClassTableState by mutableStateOf(prefs.pinTodayCoursesOnClassTable)

    /** Show today's courses above the class table's grid. */
    var pinTodayCoursesOnClassTable: Boolean
        get() = pinTodayCoursesOnClassTableState
        set(value) {
            if (pinTodayCoursesOnClassTableState == value) return
            pinTodayCoursesOnClassTableState = value
            prefs.pinTodayCoursesOnClassTable = value
        }

    private var outlineRequiredCoursesState by mutableStateOf(prefs.outlineRequiredCourses)

    /** Outline each required course's class-table cell. */
    var outlineRequiredCourses: Boolean
        get() = outlineRequiredCoursesState
        set(value) {
            if (outlineRequiredCoursesState == value) return
            outlineRequiredCoursesState = value
            prefs.outlineRequiredCourses = value
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

    private var moodleOpenInState by mutableStateOf(prefs.moodleOpenIn)

    /** The Open Moodle in setting; see [AppPreferences.moodleOpenIn]. */
    var moodleOpenIn: String
        get() = moodleOpenInState
        set(value) {
            if (moodleOpenInState == value) return
            moodleOpenInState = value
            prefs.moodleOpenIn = value
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

    private val displayScaleSetting = DisplayScaleSetting(prefs, onWidgetChange = { widgetUpdater.requestUpdate() })

    /** A size on the Display and text size page; see [DisplayScale]. */
    fun displayScale(scale: DisplayScale): Float = displayScaleSetting[scale]

    /** Picks [value] for [scale]; a widget size redraws the widgets. */
    fun setDisplayScale(scale: DisplayScale, value: Float) {
        displayScaleSetting[scale] = value
    }

    /** The app's display and text sizes as picked, which MainActivity makes itself again at. */
    val appScale: AppScale get() = displayScaleSetting.app

    private var rotationModeState by mutableStateOf(prefs.rotationMode)

    /** One of "auto", "enabled", "disabled". */
    var rotationMode: String
        get() = rotationModeState
        set(value) {
            if (rotationModeState == value) return
            rotationModeState = value
            prefs.rotationMode = value
        }

    // AppPreferences is the record for these two, and NotificationSettingsSync's
    // pull writes it directly. This copy re-reads it on every change, and an
    // offset toggle is computed from it: see AssignmentReminderSettingsState.
    private val assignmentReminderSettings = AssignmentReminderSettingsState(prefs, scope)

    var notifyAssignments: Boolean
        get() = assignmentReminderSettings.enabled
        set(value) {
            assignmentReminderSettings.enabled = value
        }

    val notifyAssignmentOffsets: Set<AssignmentReminderOffset>
        get() = assignmentReminderSettings.offsets

    /** Turns [offset] on or off in the stored set of assignment reminder offsets. */
    fun setNotifyAssignmentOffsetEnabled(offset: AssignmentReminderOffset, enabled: Boolean) =
        assignmentReminderSettings.setOffsetEnabled(offset, enabled)

    /** The flip-to-library switch; read after the migrations above. */
    val flipToLibrary = FlipToLibrarySetting(prefs)

    var flipToLibraryEnabled: Boolean
        get() = flipToLibrary.enabled
        set(value) {
            flipToLibrary.enabled = value
        }

    /** The flip switch in Settings; see [FlipToLibrarySetting.setFromSettings]. */
    fun setFlipToLibraryFromSettings(enabled: Boolean) = flipToLibrary.setFromSettings(enabled)

    private var cloudSyncEnabledState by mutableStateOf(prefs.cloudSyncEnabled)

    var cloudSyncEnabled: Boolean
        get() = cloudSyncEnabledState
        set(value) {
            val effective = effectiveCloudSyncWrite(requested = value)
            if (cloudSyncEnabledState == effective) return
            cloudSyncEnabledState = effective
            prefs.cloudSyncEnabled = effective
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

    // Transient signal from signed-out empty states (Home, ClassTable,
    // Calendar, Score): when the user taps "Sign in" we navigate to
    // Settings and flip this so the NTUST account row pulses, drawing the
    // user's attention to the action they need to take. Consumed (cleared)
    // by SettingsScreen after the animation finishes.
    var pendingNtustSignInHighlight by mutableStateOf(false)

    // "Sign in again", after the automatic sign-in failed: opens the school
    // sign-in over whatever page asked (NtustSignInSheetHost), as the iPhone
    // does. Not persisted.
    var ntustSignInSheetRequested by mutableStateOf(false)

    // The refusal whose "Automatic sign-in failed" banner the user closed. It
    // stays closed on every page for the rest of this run; a later refusal is
    // another object and shows again. Not persisted.
    var dismissedPasswordRejection by mutableStateOf<StoredPasswordLatch.Rejection?>(null)

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

    // authState, matching SettingsViewModel.isNtustLoggedIn — a property with
    // this name must mean "signed in", not "has a warm SSO cookie".
    val isNtustLoggedIn: Boolean get() = authService.authState.value

    /** Whether a library account is signed in, as Settings shows it; see [LibrarySession]. */
    val isLibraryLoggedIn: Boolean get() = librarySession.signedIn.value

    fun completeOnboarding() {
        hasCompletedOnboarding = true
        // Stamped only on the way out, so a wizard abandoned halfway — killed
        // from the recents list, say — is offered again on the next launch
        // rather than being recorded as confirmed.
        onboardingVersionState = AppPreferences.ONBOARDING_VERSION
        prefs.onboardingVersion = AppPreferences.ONBOARDING_VERSION
        // Push registration is held back until this point so no device
        // identity reaches the backend before the privacy page has been
        // seen. Consent has landed, so release the token that arrived
        // during onboarding — nothing else would re-trigger a register for
        // a user who never signs in.
        scope.launch { pushRegistration.onOnboardingCompleted() }
        // Asked nothing until now, and no return to the app comes at the wizard's end.
        scope.launch { courseChangeStore.refresh() }
    }

    /**
     * The steps of a full reset, in [FullReset]. [DataCache.clearEverything],
     * not `clearAllUserData`: a logout keeps device-wide caches on purpose,
     * but this is a factory reset and a leftover cache is precisely what makes
     * the "fresh start" it promises not one.
     */
    private val fullReset = FullReset(
        // Its password, caches, alarms and posted notifications.
        signOutMail = { mailAccount.signOut() },
        // The school sign-out keeps a demo library signed in; a full reset does not.
        signOutSchool = {
            authService.logout()
            demoAccount.signOutLibrary()
        },
        clearCaches = { dataCache.clearEverything() },
        credentials = credentials,
        prefs = prefs,
        publishLibraryCredentialsToWatch = wearBridge::publishLibraryCredentials,
        clearBrowser = browserData::clear,
    )

    /**
     * Wipe every piece of on-device user state (prefs, credentials, JSON
     * cache) and return the user to onboarding. Called from the reset
     * dialog after migration returns [DataMigration.Outcome.NeedsUserReset].
     * The steps are [FullReset]'s.
     */
    fun performFullReset() {
        fullReset.start(scope) {
            // The mutableState caches above were seeded from prefs at init
            // time. Re-read so the UI shows defaults instead of ghost values
            // from the wiped store.
            hasCompletedOnboardingState = prefs.hasCompletedOnboarding
            onboardingVersionState = prefs.onboardingVersion
            accentSetting.reload()
            visualPresetSetting.reload()
            showAbsoluteAssignmentTimeState = prefs.showAbsoluteAssignmentTime
            alwaysShowAllPeriodsState = prefs.alwaysShowAllPeriods
            showClassroomInClassTableState = prefs.showClassroomInClassTable
            pinTodayCoursesOnClassTableState = prefs.pinTodayCoursesOnClassTable
            outlineRequiredCoursesState = prefs.outlineRequiredCourses
            useEnglishCourseAbbreviationState = prefs.useEnglishCourseAbbreviation
            useEnglishClassroomAbbreviationState = prefs.useEnglishClassroomAbbreviation
            classroomMandarinDisplayState = prefs.classroomMandarinDisplay
            browserPreferenceState = prefs.browserPreference
            moodleOpenInState = prefs.moodleOpenIn
            themeModeState = prefs.themeMode
            appLanguageState = prefs.appLanguage
            invertSliderDirectionState = prefs.invertSliderDirection
            courseNameScaleState = prefs.courseNameScale.also {
                TigerDuckTheme.setCourseNameScale(it)
            }
            rotationModeState = prefs.rotationMode
            displayScaleSetting.reload()
            assignmentReminderSettings.reload()
            flipToLibrary.reload()
            cloudSyncEnabledState = prefs.cloudSyncEnabled
            disableScreenCaptureProtectionState = prefs.disableScreenCaptureProtection
            configuredTabsState = prefs.configuredTabs
            HapticScenario.tunable.forEach { scenario ->
                hapticStrengthStates[scenario] = prefs.hapticStrength(scenario)
                hapticDurationStates[scenario] = prefs.hapticDurationMs(scenario)
            }
            // The widgets draw the wiped courses, accent and interface style,
            // and redraw only when asked; reload() above does not ask.
            widgetUpdater.requestUpdate()

            librarySession.refresh()
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
                android.util.Log.w("AppState", "backgroundSync: courses fetch failed: ${it.logSummary()}")
            }
            assignmentsResult.exceptionOrNull()?.let {
                android.util.Log.w("AppState", "backgroundSync: assignments fetch failed: ${it.logSummary()}")
            }
            schoolEventsResult.exceptionOrNull()?.let {
                android.util.Log.w("AppState", "backgroundSync: calendar fetch failed: ${it.logSummary()}")
            }

            val anySucceeded =
                coursesResult.isSuccess || assignmentsResult.isSuccess || schoolEventsResult.isSuccess

            val cached = dataCache.loadCalendarEvents().toMutableList()
            var changed = false

            if (assignmentsResult.isSuccess) {
                val moodleEvents = dataCache.loadAssignments().map { assignment ->
                    CalendarEvent(
                        eventId = CalendarEvent.moodleEventId(assignment.assignmentId),
                        title = assignment.title,
                        date = assignment.studentDueDate,
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
