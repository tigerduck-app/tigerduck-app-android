package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.preferences.AppPreferences.Companion.themeColorsDark
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) :
    FirstTriggerSeenStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Language change forces a full network re-fetch so course names come
    // back in the new locale.
    private val _appLanguageChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val appLanguageChanged: SharedFlow<Unit> = _appLanguageChanged.asSharedFlow()

    // Abbreviation toggle is purely a display transform — subscribers
    // re-derive names from the in-memory cache, no network call.
    private val _useEnglishCourseAbbreviationChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val useEnglishCourseAbbreviationChanged: SharedFlow<Unit> =
        _useEnglishCourseAbbreviationChanged.asSharedFlow()

    private val _useEnglishClassroomAbbreviationChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val useEnglishClassroomAbbreviationChanged: SharedFlow<Unit> =
        _useEnglishClassroomAbbreviationChanged.asSharedFlow()

    private val _classroomMandarinDisplayChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val classroomMandarinDisplayChanged: SharedFlow<Unit> =
        _classroomMandarinDisplayChanged.asSharedFlow()

    // Toggle flips emit so `TigerDuckApp` can mirror the new value to the
    // paired watch via `WearScheduleBridge.publish()`. Phone-side SecureScreen
    // reads the AppState mutable state directly and doesn't need this signal.
    private val _disableScreenCaptureProtectionChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val disableScreenCaptureProtectionChanged: SharedFlow<Unit> =
        _disableScreenCaptureProtectionChanged.asSharedFlow()

    private val _lastSyncSource = MutableStateFlow(SyncSource.NONE)
    val lastSyncSource: StateFlow<SyncSource> = _lastSyncSource.asStateFlow()

    fun setLastSyncSource(source: SyncSource) { _lastSyncSource.value = source }

    val isSyncLocalOnly: Boolean
        get() = cloudSyncEnabled && _lastSyncSource.value == SyncSource.LOCAL

    // Opt-in: must default to false. A true default would silently enable
    // upload for existing users on upgrade (they never see the onboarding
    // sync page, and silent v3 migration logs them in without interaction).
    var cloudSyncEnabled: Boolean
        get() = prefs.getBoolean("cloudSyncEnabled", false)
        set(value) = prefs.edit().putBoolean("cloudSyncEnabled", value).apply()

    var syncCourses: Boolean
        get() = prefs.getBoolean("syncCourses", true)
        set(value) = prefs.edit().putBoolean("syncCourses", value).apply()

    var syncCourseColors: Boolean
        get() = prefs.getBoolean("syncCourseColors", true)
        set(value) = prefs.edit().putBoolean("syncCourseColors", value).apply()

    var syncCourseNames: Boolean
        get() = prefs.getBoolean("syncCourseNames", true)
        set(value) = prefs.edit().putBoolean("syncCourseNames", value).apply()

    var syncAssignments: Boolean
        get() = prefs.getBoolean("syncAssignments", true)
        set(value) = prefs.edit().putBoolean("syncAssignments", value).apply()

    var pendingConflictCategories: Set<String>
        get() = prefs.getStringSet("pendingConflictCategories", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("pendingConflictCategories", value).apply()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("hasCompletedOnboarding", false)
        set(value) = prefs.edit().putBoolean("hasCompletedOnboarding", value).apply()

    // --- Update notification (issue #89) ---
    // Sentinel for "no update prompt shown yet".
    var lastUpdatePromptVersionCode: Int
        get() = prefs.getInt("lastUpdatePromptVersionCode", -1)
        set(value) = prefs.edit().putInt("lastUpdatePromptVersionCode", value).apply()

    var lastUpdatePromptEpoch: Long
        get() = prefs.getLong("lastUpdatePromptEpoch", 0L)
        set(value) = prefs.edit().putLong("lastUpdatePromptEpoch", value).apply()

    // -1 sentinel = no version skipped. Tapping "Skip this version" on the
    // update prompt writes the offered versionCode here; any future check that
    // resolves to the same versionCode is suppressed indefinitely. A newer
    // versionCode re-arms the prompt because the equality check fails.
    var skippedUpdateVersionCode: Int
        get() = prefs.getInt("skippedUpdateVersionCode", -1)
        set(value) = prefs.edit().putInt("skippedUpdateVersionCode", value).apply()

    // --- "What's new" dialog ---
    // WHATS_NEW_UNSET (-1) means no versionCode has been recorded yet — either
    // a fresh install or an upgrade from a build that predates this pref.
    // MainActivity.resolveWhatsNew() tells the two apart via
    // hasCompletedOnboarding: a fresh install is suppressed, a real upgrade
    // shows the dialog once.
    // WHATS_NEW_REPLAY (0) is the debug "Replay What's new" sentinel — it is
    // not a real versionCode, and tells resolveWhatsNew() to show the newest
    // authored entry regardless of this build's versionCode.
    var lastSeenWhatsNewVersionCode: Int
        get() = prefs.getInt("lastSeenWhatsNewVersionCode", WHATS_NEW_UNSET)
        set(value) = prefs.edit().putInt("lastSeenWhatsNewVersionCode", value).apply()

    private val _accentColorChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val accentColorChanged: SharedFlow<Unit> = _accentColorChanged.asSharedFlow()

    var accentColorHex: Int
        get() = prefs.getInt("accentColorHex", 0x007AFF)
        set(value) {
            val previous = accentColorHex
            prefs.edit().putInt("accentColorHex", value).apply()
            if (value != previous) _accentColorChanged.tryEmit(Unit)
        }

    var browserPreference: String
        get() = prefs.getString("browserPreference", "system") ?: "system"
        set(value) = prefs.edit().putString("browserPreference", value).apply()

    /** One of "system", "dark", "light". */
    var themeMode: String
        get() = prefs.getString("themeMode", "system") ?: "system"
        set(value) = prefs.edit().putString("themeMode", value).apply()

    /** One of "system", "zh-Hant", "en". */
    var appLanguage: String
        get() {
            // Default fresh installs to "system" so the device locale wins
            // until the user makes an explicit pick in Settings.
            val stored = prefs.getString("appLanguage", null)
                ?: AppLanguageManager.SYSTEM
            return AppLanguageManager.normalize(stored)
        }
        set(value) {
            val normalized = AppLanguageManager.normalize(value)
            val previous = appLanguage
            prefs.edit().putString("appLanguage", normalized).apply()
            if (normalized != previous) _appLanguageChanged.tryEmit(Unit)
        }

    var showAbsoluteAssignmentTime: Boolean
        get() = prefs.getBoolean("showAbsoluteAssignmentTime", false)
        set(value) = prefs.edit().putBoolean("showAbsoluteAssignmentTime", value).apply()

    var rememberAnnouncementFilter: Boolean
        get() = prefs.getBoolean("rememberAnnouncementFilter", false)
        set(value) = prefs.edit().putBoolean("rememberAnnouncementFilter", value).apply()

    var colorHashV2Migrated: Boolean
        get() = prefs.getBoolean("color_hash_v2_migrated", false)
        set(value) = prefs.edit().putBoolean("color_hash_v2_migrated", value).apply()

    /**
     * Persisted department (org) filter for the announcements list. Always
     * written when the filter changes; restored on launch only when
     * [rememberAnnouncementFilter] is true. Cleared whenever that toggle
     * goes false so disabling actually wipes the saved selection.
     */
    var savedAnnouncementOrgs: Set<String>
        get() {
            val json = prefs.getString("savedAnnouncementOrgs", null) ?: return emptySet()
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                val list: List<String>? = gson.fromJson(json, type)
                list?.toSet() ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }
        set(value) {
            val editor = prefs.edit()
            if (value.isEmpty()) editor.remove("savedAnnouncementOrgs")
            else editor.putString("savedAnnouncementOrgs", gson.toJson(value.toList()))
            editor.apply()
        }

    var useEnglishCourseAbbreviation: Boolean
        get() = prefs.getBoolean("useEnglishCourseAbbreviation", true)
        set(value) {
            val previous = useEnglishCourseAbbreviation
            prefs.edit().putBoolean("useEnglishCourseAbbreviation", value).apply()
            if (value != previous) _useEnglishCourseAbbreviationChanged.tryEmit(Unit)
        }

    var useEnglishClassroomAbbreviation: Boolean
        get() = prefs.getBoolean("useEnglishClassroomAbbreviation", true)
        set(value) {
            val previous = useEnglishClassroomAbbreviation
            prefs.edit().putBoolean("useEnglishClassroomAbbreviation", value).apply()
            if (value != previous) _useEnglishClassroomAbbreviationChanged.tryEmit(Unit)
        }

    /** One of "original" (Mandarin shortened_name), "pinyin", "translated". */
    var classroomMandarinDisplay: String
        get() = prefs.getString("classroomMandarinDisplay", null)
            ?.takeIf { it in CLASSROOM_MANDARIN_DISPLAY_OPTIONS }
            ?: CLASSROOM_MANDARIN_DISPLAY_ORIGINAL
        set(value) {
            val normalized = if (value in CLASSROOM_MANDARIN_DISPLAY_OPTIONS) {
                value
            } else CLASSROOM_MANDARIN_DISPLAY_ORIGINAL
            val previous = classroomMandarinDisplay
            prefs.edit().putString("classroomMandarinDisplay", normalized).apply()
            if (normalized != previous) _classroomMandarinDisplayChanged.tryEmit(Unit)
        }

    var homeAssignmentFilter: AssignmentFilter
        get() {
            val raw = prefs.getString("homeAssignmentFilter", null)
            return AssignmentFilter.entries.firstOrNull { it.name == raw }
                ?: AssignmentFilter.INCOMPLETE
        }
        set(value) = prefs.edit().putString("homeAssignmentFilter", value.name).apply()

    var configuredTabs: List<AppFeature>
        get() {
            val json = prefs.getString("configuredTabs", null)
                ?: return AppFeature.defaultTabs
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                val ids: List<String> = gson.fromJson(json, type) ?: return AppFeature.defaultTabs
                ids.mapNotNull { AppFeature.fromId(it) }.ifEmpty { AppFeature.defaultTabs }
            } catch (e: Exception) {
                AppFeature.defaultTabs
            }
        }
        set(value) {
            prefs.edit().putString("configuredTabs", gson.toJson(value.map { it.id })).apply()
        }

    var invertSliderDirection: Boolean
        get() = prefs.getBoolean("invertSliderDirection", false)
        set(value) = prefs.edit().putBoolean("invertSliderDirection", value).apply()

    /**
     * Multiplier (0.8…1.6, step 0.05) applied to the course-name text in
     * class-table cards. Always normalized on read so a manually-edited
     * pref or a value persisted by a future range tweak can't escape the
     * current bounds.
     */
    var courseNameScale: Float
        get() {
            if (!prefs.contains("courseNameScale")) return CourseNameScale.DEFAULT
            return CourseNameScale.normalize(
                prefs.getFloat("courseNameScale", CourseNameScale.DEFAULT)
            )
        }
        set(value) = prefs.edit()
            .putFloat("courseNameScale", CourseNameScale.normalize(value))
            .apply()

    /** One of "auto", "enabled", "disabled". */
    var rotationMode: String
        get() = prefs.getString("rotationMode", null)
            ?.takeIf { it in ROTATION_MODE_OPTIONS }
            ?: ROTATION_MODE_AUTO
        set(value) {
            val normalized = if (value in ROTATION_MODE_OPTIONS) value else ROTATION_MODE_AUTO
            prefs.edit().putString("rotationMode", normalized).apply()
        }

    var analyticsEnabled: Boolean
        get() = prefs.getBoolean("analyticsEnabled", false)
        set(value) = prefs.edit().putBoolean("analyticsEnabled", value).apply()

    var libraryFeatureEnabled: Boolean
        get() = prefs.getBoolean("libraryFeatureEnabled", false)
        set(value) = prefs.edit().putBoolean("libraryFeatureEnabled", value).apply()

    // Defaults ON (matching iOS): the user discovers the gesture on their first
    // accidental flip, where the first-trigger prompt explains it and offers to
    // turn it off. The sensor still only runs while the parent Library feature
    // is enabled, so a user with Library off pays no battery cost.
    var flipToLibraryEnabled: Boolean
        get() = prefs.getBoolean("flipToLibraryEnabled", true)
        set(value) = prefs.edit().putBoolean("flipToLibraryEnabled", value).apply()

    // --- First-trigger prompts ---
    // One-shot "you just did X for the first time — keep it?" prompts, keyed by
    // a stable storage slug (see FirstTriggerPromptKey). The flag is written
    // only when the user makes a Keep / Turn-off choice, never on mere display,
    // so a prompt dismissed by any other path re-arms on the next trigger.
    override fun hasSeenFirstTriggerPrompt(storageKey: String): Boolean =
        prefs.getBoolean("firstTriggerPromptSeen.$storageKey", false)

    override fun setFirstTriggerPromptSeen(storageKey: String, seen: Boolean) {
        prefs.edit().apply {
            if (seen) putBoolean("firstTriggerPromptSeen.$storageKey", true)
            else remove("firstTriggerPromptSeen.$storageKey")
        }.apply()
    }

    var notifyAssignments: Boolean
        get() = prefs.getBoolean("notifyAssignments", true)
        set(value) = prefs.edit().putBoolean("notifyAssignments", value).apply()

    /**
     * Per-offset opt-in for assignment due reminders. Persisted as raw-value
     * strings so a freshly-added [AssignmentReminderOffset] entry deserialises
     * cleanly (unknown rawValues are simply dropped on read).
     *
     * Absent key (fresh install or upgrade from <= v1.4.x where only a single
     * 1h-before reminder existed) → seed with [AssignmentReminderOffset.DEFAULTS].
     */
    var notifyAssignmentOffsets: Set<AssignmentReminderOffset>
        get() {
            val stored = prefs.getStringSet("notifyAssignmentOffsets", null)
                ?: return AssignmentReminderOffset.DEFAULTS
            return stored.mapNotNullTo(mutableSetOf()) { AssignmentReminderOffset.fromRawValue(it) }
        }
        set(value) {
            prefs.edit()
                .putStringSet("notifyAssignmentOffsets", value.map { it.rawValue }.toSet())
                .apply()
        }

    var homeSections: List<HomeSection>
        get() {
            val json = prefs.getString("homeSections", null) ?: return HomeSection.defaults()
            return try {
                val type = object : TypeToken<List<HomeSection>>() {}.type
                @Suppress("DEPRECATION")
                gson.fromJson<List<HomeSection>>(json, type)
                    ?.filter { it.type != HomeSection.HomeSectionType.QUICK_WIDGETS }
                    // Renumber after the filter: dropping a section out of the
                    // middle leaves a gap, and HomeSectionLayout.add derives the
                    // next value from list size, so the gap becomes a duplicate.
                    ?.mapIndexed { i, s -> s.copy(sortOrder = i) }
                    ?.ifEmpty { HomeSection.defaults() }
                    ?: HomeSection.defaults()
            } catch (e: Exception) {
                HomeSection.defaults()
            }
        }
        set(value) {
            prefs.edit().putString("homeSections", gson.toJson(value)).apply()
        }

    var ssoLoginTimestamp: Long
        get() = prefs.getLong("ssoLoginTimestamp", 0L)
        set(value) = prefs.edit().putLong("ssoLoginTimestamp", value).apply()

    fun clearSsoTimestamp() {
        prefs.edit().remove("ssoLoginTimestamp").apply()
    }

    /**
     * Debug-only escape hatch from the Developer section: when true,
     * [org.ntust.app.tigerduck.ui.component.SecureScreen] skips applying
     * `WindowManager.LayoutParams.FLAG_SECURE`, allowing screenshots and
     * screen recordings of normally-protected surfaces (login sheets,
     * library account screen, onboarding password page). The Developer
     * row that writes this is gated on `BuildConfig.DEBUG`, and the
     * SecureScreen reader is gated the same way so a release build can
     * never honor a stale-from-debug value.
     */
    var disableScreenCaptureProtection: Boolean
        get() = prefs.getBoolean("disableScreenCaptureProtection", false)
        set(value) {
            val previous = disableScreenCaptureProtection
            prefs.edit().putBoolean("disableScreenCaptureProtection", value).apply()
            if (value != previous) _disableScreenCaptureProtectionChanged.tryEmit(Unit)
        }

    /**
     * Debug-only override for the Announcement (bulletin) base URL.
     * Written from Settings → Developer → API endpoint; read on every
     * BulletinApiClient call so changes take effect without relaunch.
     * Null means "use BuildConfig.PUSH_BASE_URL". The writer screen is
     * DEBUG-gated, so release builds never see a non-null value here.
     */
    var announcementApiBaseUrlOverride: String?
        get() = prefs.getString("announcementApiBaseUrlOverride", null)?.takeIf { it.isNotBlank() }
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) editor.remove("announcementApiBaseUrlOverride")
            else editor.putString("announcementApiBaseUrlOverride", value)
            editor.apply()
        }

    /**
     * Monotonic version for on-device user-data layout. Bumped whenever the
     * app ships a change that needs a one-shot migration (see DataMigration).
     * 0 covers every pre-migration-system build (fresh install or upgrade).
     */
    var dataSchemaVersion: Int
        get() = prefs.getInt("dataSchemaVersion", 0)
        set(value) = prefs.edit().putInt("dataSchemaVersion", value).apply()

    /** Wipe every pref key. Used by the full-reset flow only. */
    fun clearAllPrefs() {
        prefs.edit().clear().apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Read [key], or generate-and-persist a value via [factory] if absent.
     * Used by [org.ntust.app.tigerduck.push.PushIdentity] to mint a stable
     * device id on first launch.
     */
    @Synchronized
    fun getOrCreateString(key: String, factory: () -> String): String {
        prefs.getString(key, null)?.let { return it }
        val created = factory()
        prefs.edit().putString(key, created).apply()
        return created
    }

    /** Semester the user last viewed in 課表. Null until first pick. */
    var classTableSelectedSemester: String?
        get() = prefs.getString("classTableSelectedSemester", null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove("classTableSelectedSemester")
            else editor.putString("classTableSelectedSemester", value)
            editor.apply()
        }

    /**
     * Semester codes published by NTUST, newest first — see
     * [org.ntust.app.tigerduck.network.SemesterCatalog]. Empty until the first
     * successful fetch, which the catalogue reads as "fall back to the month
     * heuristic".
     *
     * Stored as a delimited string rather than a `StringSet` because
     * `SharedPreferences` sets are unordered, and this list's whole value is
     * that it is newest-first.
     */
    var semesterCatalogTerms: List<String>
        get() = prefs.getString("semesterCatalogTerms", null)
            ?.split(SEMESTER_LIST_DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) = prefs.edit()
            .putString("semesterCatalogTerms", value.joinToString(SEMESTER_LIST_DELIMITER))
            .apply()

    /**
     * The term the 選課 system is currently open for (`LoginEnable`). Runs
     * weeks ahead of the term in session, so it is not interchangeable with
     * [org.ntust.app.tigerduck.AppConstants.CurrentTerm.CODE].
     */
    var semesterCatalogSelection: String?
        get() = prefs.getString("semesterCatalogSelection", null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove("semesterCatalogSelection")
            else editor.putString("semesterCatalogSelection", value)
            editor.apply()
        }

    /** Wall-clock millis of the last successful catalogue fetch. 0 = never. */
    var semesterCatalogRefreshedAt: Long
        get() = prefs.getLong("semesterCatalogRefreshedAt", 0L)
        set(value) = prefs.edit().putLong("semesterCatalogRefreshedAt", value).apply()

    fun hapticStrength(scenario: HapticScenario): Int =
        prefs.getInt("haptic_strength_${scenario.prefKey}", scenario.defaultStrengthPct)
            .coerceIn(0, 100)

    fun setHapticStrength(scenario: HapticScenario, value: Int) {
        prefs.edit()
            .putInt("haptic_strength_${scenario.prefKey}", value.coerceIn(0, 100))
            .apply()
    }

    fun hapticDurationMs(scenario: HapticScenario): Int =
        prefs.getInt("haptic_duration_${scenario.prefKey}", scenario.defaultDurationMs)
            .coerceIn(MIN_TUNABLE_HAPTIC_DURATION_MS, MAX_TUNABLE_HAPTIC_DURATION_MS)

    fun setHapticDurationMs(scenario: HapticScenario, value: Int) {
        prefs.edit()
            .putInt(
                "haptic_duration_${scenario.prefKey}",
                value.coerceIn(MIN_TUNABLE_HAPTIC_DURATION_MS, MAX_TUNABLE_HAPTIC_DURATION_MS),
            )
            .apply()
    }

    companion object {
        const val WHATS_NEW_UNSET = -1
        const val WHATS_NEW_REPLAY = 0

        /** Comma is safe: NTUST semester codes are `[0-9]{3}[12H]`. */
        private const val SEMESTER_LIST_DELIMITER = ","

        const val MIN_TUNABLE_HAPTIC_DURATION_MS = 5
        const val MAX_TUNABLE_HAPTIC_DURATION_MS = 60

        const val ROTATION_MODE_AUTO = "auto"
        const val ROTATION_MODE_ENABLED = "enabled"
        const val ROTATION_MODE_DISABLED = "disabled"
        val ROTATION_MODE_OPTIONS = setOf(
            ROTATION_MODE_AUTO,
            ROTATION_MODE_ENABLED,
            ROTATION_MODE_DISABLED,
        )

        const val CLASSROOM_MANDARIN_DISPLAY_ORIGINAL = "original"
        const val CLASSROOM_MANDARIN_DISPLAY_PINYIN = "pinyin"
        const val CLASSROOM_MANDARIN_DISPLAY_TRANSLATED = "translated"
        val CLASSROOM_MANDARIN_DISPLAY_OPTIONS = setOf(
            CLASSROOM_MANDARIN_DISPLAY_ORIGINAL,
            CLASSROOM_MANDARIN_DISPLAY_PINYIN,
            CLASSROOM_MANDARIN_DISPLAY_TRANSLATED,
        )

        /**
         * Accent color palette — canonical (light-mode) hex. The user's pick
         * is always stored as the light hex; [themeColorsDark] provides the
         * paired dark variant at the same index so themes swap in-place.
         */
        val themeColors: List<Pair<String, Int>> = listOf(
            "Blue" to 0x007AFF,
            "Purple" to 0xAF52DE,
            "Pink" to 0xFF2D55,
            "Red" to 0xFF3B30,
            "Orange" to 0xFF9500,
            "Green" to 0x34C759,
            "Teal" to 0x5AC8FA,
            "Indigo" to 0x5856D6,
        )

        val themeColorsDark: List<Pair<String, Int>> = listOf(
            "Blue" to 0x0A84FF,
            "Purple" to 0xBF5AF2,
            "Pink" to 0xFF375F,
            "Red" to 0xFF453A,
            "Orange" to 0xFF9F0A,
            "Green" to 0x32D74B,
            "Teal" to 0x64D2FF,
            "Indigo" to 0x5E5CE6,
        )

        init {
            require(themeColors.size == themeColorsDark.size) {
                "themeColors and themeColorsDark must be the same size"
            }
            require(themeColors.map { it.first } == themeColorsDark.map { it.first }) {
                "themeColors and themeColorsDark must share names in the same order"
            }
        }

        /** Look up the dark-mode companion for a given light-mode accent hex. */
        fun accentDarkVariant(lightHex: Int): Int {
            val idx = themeColors.indexOfFirst { it.second == lightHex }
            return if (idx >= 0) themeColorsDark[idx].second else lightHex
        }
    }
}
