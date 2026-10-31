package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.preferences.AppPreferences.Companion.themeColorsDark
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.ClassReminders
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The effective value of the `cloudSyncEnabled` preference: [storedValue] on
 * every flavor except fdroid, which ships without Google Play Services and
 * can never run TigerSync's course-sync / server-push pipeline regardless of
 * what is stored. [AppPreferences.cloudSyncEnabled]'s getter is the one call
 * site that applies this, so every reader — direct (`PushApiClient`,
 * `NotificationSettingsSync`'s gates, `HomeBackendSync`, ...) or through
 * [org.ntust.app.tigerduck.ui.AppState.cloudSyncEnabled], which seeds its
 * in-memory mirror from this same getter — agrees on fdroid without each one
 * re-deriving the flavor check itself.
 *
 * [flavor] defaults to [BuildConfig.FLAVOR] for that one production call
 * site. Tests pass it explicitly instead: [AppPreferences] can't be
 * constructed on the plain JVM this module's tests run on (its constructor
 * calls the real `Context.getSharedPreferences`, and there is neither
 * Robolectric nor a mocking library here), so this pure function — not the
 * getter itself — is what a unit test exercises. Taking the flavor as a
 * parameter also means the same test passes under both
 * `testPlayDebugUnitTest` and `testFdroidDebugUnitTest`, instead of a literal
 * expectation on `BuildConfig.FLAVOR` that would only be true under one of
 * them.
 */
fun effectiveCloudSyncEnabled(storedValue: Boolean, flavor: String = BuildConfig.FLAVOR): Boolean =
    storedValue && !flavor.equals("fdroid", ignoreCase = true)

/**
 * The stored Home layout, made safe to show: the read side of
 * [AppPreferences.homeSections], a pure function for the same reason as
 * [effectiveCloudSyncEnabled].
 *
 * Gson builds [HomeSection] without running its constructor (CLAUDE.md,
 * "Upgrade-safe persistence"), so whatever an older build wrote differently
 * arrives as a null behind a non-null Kotlin type, which cannot be tested
 * for once the object exists. The repairs are therefore made on the JSON
 * tree, before the types are built:
 *
 * - a section whose `type`, or a shortcut whose `feature`, names a constant
 *   this build does not have is dropped rather than left to NPE in the
 *   grid — the one rename so far, GPA to SCORE, is instead mapped by the
 *   `@SerializedName` alternate on [AppFeature.SCORE], which `gson` applies
 *   here too;
 * - a section without `widgets` reads back with none, where the Kotlin
 *   default would not have applied.
 *
 * QUICK_WIDGETS sections are kept: builds before 2.5 filtered them out of
 * what they read ("feature temporarily disabled") but left them stored, so
 * the layout a user saved comes back now that the grid ships. The
 * renumbering remains for a layout such a build re-saved with the gap that
 * left, as HomeSectionLayout.add would otherwise hand out a duplicate.
 */
internal fun restoreHomeSections(json: String?, gson: Gson): List<HomeSection> {
    json ?: return HomeSection.defaults()
    return try {
        val repaired = JsonArray()
        JsonParser.parseString(json).asJsonArray
            .filterIsInstance<JsonObject>()
            .filter { gson.fromJson(it.get("type"), HomeSection.HomeSectionType::class.java) != null }
            .forEach { section ->
                val kept = JsonArray()
                section.get("widgets")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.filterIsInstance<JsonObject>()
                    ?.filter { gson.fromJson(it.get("feature"), AppFeature::class.java) != null }
                    ?.forEach(kept::add)
                section.add("widgets", kept)
                repaired.add(section)
            }
        val type = object : TypeToken<List<HomeSection>>() {}.type
        gson.fromJson<List<HomeSection>>(repaired, type)
            ?.mapIndexed { i, s -> s.copy(sortOrder = i) }
            ?.ifEmpty { HomeSection.defaults() }
            ?: HomeSection.defaults()
    } catch (e: Exception) {
        HomeSection.defaults()
    }
}

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) :
    FirstTriggerSeenStore, AssignmentReminderPrefs, ReviewPromptStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    init {
        // The "Remember bulletin filters" option is gone: the bulletin page's
        // chips start with nothing selected on every visit, so nothing reads
        // the option or the departments it kept. An install that had them
        // stored is rid of them here; once gone, these removals change nothing
        // and skip the disk write.
        prefs.edit()
            .remove("rememberAnnouncementFilter")
            .remove("savedAnnouncementOrgs")
            .apply()
    }

    // Where builds before the server-time course-reset check kept this
    // phone's clock at its last full sync; see legacyLastCourseSyncAtMs.
    private val legacySyncPrefs: SharedPreferences =
        context.getSharedPreferences("tigerduck_sync", Context.MODE_PRIVATE)
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

    // Default on, matching iOS/macOS (`AppDefaults.cloudSyncEnabled`). One
    // account's devices are expected to agree without being configured, so
    // the two platforms have to agree on the default too — a false default
    // here meant a phone and a Mac on the same account behaved differently
    // out of the box.
    //
    // The cost is that existing users are upgraded into it: they never see
    // the onboarding sync page, and the silent v3 migration signs them in
    // without interaction. The 2.0.1 "What's new" entry names cross-device
    // sync and says where to turn it off, which is what makes that
    // defensible rather than silent.
    //
    // The getter runs the stored value through effectiveCloudSyncEnabled, so
    // fdroid reads false here no matter what is stored — see that function's
    // doc. The setter still writes the raw value: it is the user's stored
    // choice, not the effective one, and every reader goes through the
    // getter anyway.
    var cloudSyncEnabled: Boolean
        get() = effectiveCloudSyncEnabled(prefs.getBoolean("cloudSyncEnabled", true))
        set(value) = setSyncSwitch("cloudSyncEnabled", value)

    var syncCourses: Boolean
        get() = prefs.getBoolean("syncCourses", true)
        set(value) = setSyncSwitch("syncCourses", value)

    var syncCourseColors: Boolean
        get() = prefs.getBoolean("syncCourseColors", true)
        set(value) = setSyncSwitch("syncCourseColors", value)

    var syncCourseNames: Boolean
        get() = prefs.getBoolean("syncCourseNames", true)
        set(value) = setSyncSwitch("syncCourseNames", value)

    var syncAssignments: Boolean
        get() = prefs.getBoolean("syncAssignments", true)
        set(value) = setSyncSwitch("syncAssignments", value)

    /**
     * Stores a sync switch, and forgets the upload ledger ([forgetUploads])
     * when it goes from off to on, wherever it is turned: the backend may
     * have dropped the lists while it was off on every device.
     */
    private fun setSyncSwitch(key: String, value: Boolean) {
        val wasOn = prefs.getBoolean(key, true)
        prefs.edit().putBoolean(key, value).apply()
        if (value && !wasOn) forgetUploads()
    }

    // Flips so NotificationSettingsSync can reconcile: turning this switch
    // back on doesn't itself change notifyAssignments/notifyAssignmentOffsets,
    // so nothing else would notice that the section has to be taken from the
    // account's document again. Emitted only on an actual change, mirroring
    // syncLiveActivityChanged below.
    private val _syncAssignmentRemindersChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val syncAssignmentRemindersChanged: SharedFlow<Unit> = _syncAssignmentRemindersChanged.asSharedFlow()

    /** "Synced content" toggle — assignment due-date reminders. */
    var syncAssignmentReminders: Boolean
        get() = prefs.getBoolean("syncAssignmentReminders", true)
        set(value) {
            val previous = syncAssignmentReminders
            prefs.edit().putBoolean("syncAssignmentReminders", value).apply()
            if (value != previous) _syncAssignmentRemindersChanged.tryEmit(Unit)
        }

    // Flips so NotificationSettingsSync can reconcile: turning this switch
    // back on doesn't itself change any of the five live_activity values,
    // so nothing else would notice that the section has to be taken from the
    // account's document again. Emitted only on an actual change, same as
    // appLanguageChanged above.
    private val _syncLiveActivityChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val syncLiveActivityChanged: SharedFlow<Unit> = _syncLiveActivityChanged.asSharedFlow()

    /** "Synced content" toggle — Live Activity / Live Updates state. */
    var syncLiveActivity: Boolean
        get() = prefs.getBoolean("syncLiveActivity", true)
        set(value) {
            val previous = syncLiveActivity
            prefs.edit().putBoolean("syncLiveActivity", value).apply()
            if (value != previous) _syncLiveActivityChanged.tryEmit(Unit)
        }

    var pendingConflictCategories: Set<String>
        get() = prefs.getStringSet("pendingConflictCategories", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("pendingConflictCategories", value).apply()

    /**
     * The re-enable check's categories, named as in [pendingConflictCategories],
     * whose data a full sync has applied on this phone for the signed-in
     * account. Only these are marked for the check when switched back on: for
     * any other, the phone holds none of the account's data to compare. Added
     * to by `HomeBackendSync.pull`, filled by `DataMigration.migrate3to4`, and
     * cleared at sign-out by [clearSyncStamps].
     */
    var syncedCategories: Set<String>
        get() = prefs.getStringSet("syncedCategories", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("syncedCategories", value).apply()

    /**
     * Whether a full sync has taken the signed-in account's assignment marks
     * on this phone, or sent them up as its first sync, so that from now on a
     * server holding no marks is the truth — see
     * `AssignmentOverrideReconciler.isFirstUpload`. False on an install that
     * predates it, which therefore runs that first sync once more. Cleared at
     * sign-out by [clearSyncStamps]. Turning sync off keeps it: turning it
     * back on marks the category for the re-enable check, and syncs leave the
     * marks alone until that is settled.
     */
    var assignmentMarksSyncedOnce: Boolean
        get() = prefs.getBoolean("assignmentMarksSyncedOnce", false)
        set(value) = prefs.edit().putBoolean("assignmentMarksSyncedOnce", value).apply()

    /**
     * The server's `courses_reset_at` as the last full sync read it, for the
     * next one to tell a new reset from one already applied; empty once a
     * sync found none, and null before the signed-in account's first sync
     * here — see `CourseSyncReconciler.coursesReset`. Cleared at sign-out by
     * [clearSyncStamps].
     */
    var lastSeenCoursesResetAt: String?
        get() = prefs.getString("lastSeenCoursesResetAt", null)
        set(value) = prefs.edit().putString("lastSeenCoursesResetAt", value).apply()

    /**
     * This phone's clock at an older build's last full sync, epoch ms, or 0:
     * read by the first sync since the update, which then forgets it
     * ([forgetLegacyLastCourseSyncAt]); see `CourseSyncReconciler.coursesReset`.
     */
    val legacyLastCourseSyncAtMs: Long
        get() = legacySyncPrefs.getLong("last_course_sync_at", 0L)

    fun forgetLegacyLastCourseSyncAt() {
        legacySyncPrefs.edit().remove("last_course_sync_at").apply()
    }

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("hasCompletedOnboarding", false)
        set(value) = prefs.edit().putBoolean("hasCompletedOnboarding", value).apply()

    /**
     * Which revision of the wizard this install has actually been through.
     *
     * Stamped by [org.ntust.app.tigerduck.ui.AppState.completeOnboarding] with
     * [ONBOARDING_VERSION]. The default of 0 is every pre-2.0.1 install: they
     * finished onboarding before this key existed, which means they finished a
     * wizard that had no TigerSync page and no 2.0.1 privacy copy. Those
     * installs are walked through it once — still signed in, since nothing
     * here touches [CredentialManager]. See `AppState.needsOnboardingRerun`.
     *
     * Separate from [hasCompletedOnboarding] on purpose: that flag also gates
     * push registration and the "What's new" fresh-install check, and clearing
     * it to re-show the wizard would make an upgrading user look like a brand
     * new one to both.
     */
    var onboardingVersion: Int
        get() = prefs.getInt("onboardingVersion", 0)
        set(value) = prefs.edit().putInt("onboardingVersion", value).apply()

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

    // --- Play Store rating (issue #332) ---
    // ReviewPromptStore; the rule is in ReviewPrompt. Primitives with
    // "nothing yet" defaults, so an install upgraded into this starts
    // counting from zero with nothing to migrate. Not cleared at sign-out:
    // the ask is once per install, whatever account is on it.
    override var reviewPromptDaysUsed: Int
        get() = prefs.getInt("reviewPromptDaysUsed", 0)
        set(value) = prefs.edit().putInt("reviewPromptDaysUsed", value).apply()

    override var reviewPromptLastDay: String?
        get() = prefs.getString("reviewPromptLastDay", null)
        set(value) = prefs.edit().putString("reviewPromptLastDay", value).apply()

    override var reviewPromptAsked: Boolean
        get() = prefs.getBoolean("reviewPromptAsked", false)
        set(value) = prefs.edit().putBoolean("reviewPromptAsked", value).apply()

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

    /**
     * Fires when the accent the watch is sent changes: the swatch, the
     * gradient (see [storeAccentPick]) or its direction.
     */
    val accentColorChanged: SharedFlow<Unit> = _accentColorChanged.asSharedFlow()

    /**
     * The accent swatch last picked in Settings, as its light palette hex. It
     * keeps its value while [useWallpaperColors] is on. Read through
     * [AccentPolicy.fixedLightHex], so nothing ever draws a value that is no
     * color.
     */
    var accentColorHex: Int
        get() = AccentPolicy.fixedLightHex(prefs.getInt("accentColorHex", AccentPolicy.DEFAULT_LIGHT_HEX))
        set(value) {
            val previous = accentColorHex
            prefs.edit().putInt("accentColorHex", value).apply()
            if (value != previous) _accentColorChanged.tryEmit(Unit)
        }

    /**
     * Whether the wallpaper's colors are picked in Settings in place of the
     * swatch. A switch of its own, and not a value of [accentColorHex], so
     * the swatch survives the pick: [AccentPolicy] says which surfaces still
     * draw it, and an older build, which knows nothing of this key, goes on
     * drawing it too.
     */
    var useWallpaperColors: Boolean
        get() = prefs.getBoolean("useWallpaperColors", false)
        set(value) = prefs.edit().putBoolean("useWallpaperColors", value).apply()

    /**
     * The end color of the accent gradient picked, as its light palette hex,
     * or [AccentPolicy.NO_GRADIENT] while the pick is a single color.
     * [accentColorHex] holds its start, which every surface that draws no
     * gradient draws alone.
     */
    var accentGradientEndHex: Int
        get() = prefs.getInt("accentGradientEndHex", AccentPolicy.NO_GRADIENT)
        set(value) = prefs.edit().putInt("accentGradientEndHex", value).apply()

    /**
     * The start color the gradient was picked with. An older build knows only
     * [accentColorHex], and a pick made there changes it: the gradient counts
     * only while the two still match (see [AccentPolicy.gradientEnd]).
     */
    var accentGradientStartHex: Int
        get() = prefs.getInt("accentGradientStartHex", AccentPolicy.NO_GRADIENT)
        set(value) = prefs.edit().putInt("accentGradientStartHex", value).apply()

    /** The preferences the accent the watch is sent is read from. */
    private fun watchAccent() =
        listOf(accentColorHex, accentGradientStartHex, accentGradientEndHex, accentGradientDirection.code)

    /**
     * Stores a pick from the accent color page whole: the swatch [lightHex],
     * and the gradient from [gradientStartHex] to [gradientEndHex], or
     * [AccentPolicy.NO_GRADIENT] for both. One write, and one
     * [accentColorChanged] when the pick changes anything: a gradient from
     * the swatch already picked leaves [accentColorHex] as it was, and the
     * watch, which reads all three as it hears the change, must never hear a
     * pick half stored.
     */
    fun storeAccentPick(lightHex: Int, gradientStartHex: Int, gradientEndHex: Int) {
        val changed = lightHex != accentColorHex ||
            gradientStartHex != accentGradientStartHex ||
            gradientEndHex != accentGradientEndHex
        prefs.edit()
            .putInt("accentColorHex", lightHex)
            .putInt("accentGradientStartHex", gradientStartHex)
            .putInt("accentGradientEndHex", gradientEndHex)
            .apply()
        if (changed) _accentColorChanged.tryEmit(Unit)
    }

    /** The way the accent gradient runs, stored as its [AccentGradientDirection.code]. */
    var accentGradientDirection: AccentGradientDirection
        get() = AccentGradientDirection.fromCode(
            prefs.getInt("accentGradientDirection", AccentGradientDirection.DEFAULT.code),
        )
        set(value) {
            val previous = accentGradientDirection
            prefs.edit().putInt("accentGradientDirection", value.code).apply()
            if (value != previous) _accentColorChanged.tryEmit(Unit)
        }

    var browserPreference: String
        get() = prefs.getString("browserPreference", "system") ?: "system"
        set(value) = prefs.edit().putString("browserPreference", value).apply()

    /**
     * Where "Open in Moodle" goes while the Moodle app is installed: [MOODLE_OPEN_IN_APP], the
     * default, as before the setting, or [MOODLE_OPEN_IN_BROWSER], the TigerDuck browser signed in.
     * Without the app every page opens in the browser, whatever this says; see `openMoodlePage`.
     */
    var moodleOpenIn: String
        get() = readMoodleOpenIn(prefs)
        set(value) = prefs.edit().putString(MOODLE_OPEN_IN_KEY, value).apply()

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

    // Stored name predates the rename from "always show A/B/C" to "always
    // show every period" — keeping it carries the setting over.
    private val _alwaysShowAllPeriods =
        MutableStateFlow(prefs.getBoolean("alwaysShowPeriodsABC", false))

    /**
     * Observable form of [alwaysShowAllPeriods], for the class table.
     *
     * The grid derives its row list from this, and the toggle lives on a
     * different screen — a plain getter would leave the timetable showing
     * the old rows until something else happened to invalidate it.
     */
    val alwaysShowAllPeriodsFlow: StateFlow<Boolean> =
        _alwaysShowAllPeriods.asStateFlow()

    /**
     * Keep every period — lunch (5) and the evening block (A–D) included —
     * on the timetable even when no course uses them. Off by default: an
     * empty evening is rows of nothing for the majority who never have a
     * class there.
     */
    var alwaysShowAllPeriods: Boolean
        get() = _alwaysShowAllPeriods.value
        set(value) {
            prefs.edit().putBoolean("alwaysShowPeriodsABC", value).apply()
            _alwaysShowAllPeriods.value = value
        }

    private val _showClassroomInClassTable =
        MutableStateFlow(prefs.getBoolean("showClassroomInClassTable", true))

    /**
     * Observable form of [showClassroomInClassTable], for the class table,
     * whose cells read it while the toggle lives on the Settings screen.
     */
    val showClassroomInClassTableFlow: StateFlow<Boolean> =
        _showClassroomInClassTable.asStateFlow()

    /**
     * Print each course's room in the corner of its class-table cell. On by
     * default, as on the iPhone: where a class meets is what students look up
     * next to which course it is. It defaulted off before 2.4.0, and a
     * default is nothing stored, so the flip alone upgrades everyone who never
     * touched the switch; whoever turned it off stored a false and keeps it.
     * Device-local, like every display preference; the settings document the
     * backend syncs does not carry it.
     */
    var showClassroomInClassTable: Boolean
        get() = _showClassroomInClassTable.value
        set(value) {
            prefs.edit().putBoolean("showClassroomInClassTable", value).apply()
            _showClassroomInClassTable.value = value
        }

    private val _pinTodayCoursesOnClassTable =
        MutableStateFlow(prefs.getBoolean(PIN_TODAY_COURSES_ON_CLASS_TABLE, false))

    /** Observable form of [pinTodayCoursesOnClassTable], for the class table. */
    val pinTodayCoursesOnClassTableFlow: StateFlow<Boolean> =
        _pinTodayCoursesOnClassTable.asStateFlow()

    /**
     * Show today's courses above the class table's grid. Off by default, as
     * on the iPhone: Home already lists them, and without them the grid
     * starts at the top of the page. Device-local.
     */
    var pinTodayCoursesOnClassTable: Boolean
        get() = _pinTodayCoursesOnClassTable.value
        set(value) {
            prefs.edit().putBoolean(PIN_TODAY_COURSES_ON_CLASS_TABLE, value).apply()
            _pinTodayCoursesOnClassTable.value = value
        }

    private val _outlineRequiredCourses =
        MutableStateFlow(prefs.getBoolean(OUTLINE_REQUIRED_COURSES, false))

    /** Observable form of [outlineRequiredCourses], for the class table. */
    val outlineRequiredCoursesFlow: StateFlow<Boolean> = _outlineRequiredCourses.asStateFlow()

    /**
     * Outline each required course's class-table cell. Off by default, as on
     * the iPhone, where it is a mark the user asks for. Device-local.
     */
    var outlineRequiredCourses: Boolean
        get() = _outlineRequiredCourses.value
        set(value) {
            prefs.edit().putBoolean(OUTLINE_REQUIRED_COURSES, value).apply()
            _outlineRequiredCourses.value = value
        }

    /**
     * The interface style picked in Settings > Display. TigerDuck until the
     * user picks another; a stored style this build does not know reads as
     * TigerDuck too. Device-local, as on the iPhone: the settings the backend
     * syncs do not carry it.
     */
    var visualPreset: VisualPreset
        get() = VisualPreset.fromRawValue(prefs.getString(VISUAL_PRESET_KEY, null))
        set(value) {
            val previous = visualPreset
            prefs.edit().putString(VISUAL_PRESET_KEY, value.rawValue).apply()
            if (value != previous) _visualPresetChanged.tryEmit(Unit)
        }

    private val _visualPresetChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Fires when [visualPreset] changes, so the watch's course cards can follow it. */
    val visualPresetChanged: SharedFlow<Unit> = _visualPresetChanged.asSharedFlow()

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

    /** Whether a bottom bar is stored at all — an untouched one isn't, and follows the default. */
    val hasStoredConfiguredTabs: Boolean
        get() = prefs.contains("configuredTabs")

    /**
     * Set once this install has been checked for an untouched bottom bar to
     * keep when the default changed in 2.3.0 (see `AppState`). A flag rather
     * than a [dataSchemaVersion] step, like [legacyNotificationChannelsDeleted]:
     * a downgrade shouldn't get the reset prompt over a tab bar.
     */
    var previousDefaultTabsChecked: Boolean
        get() = prefs.getBoolean("previousDefaultTabsChecked", false)
        set(value) = prefs.edit().putBoolean("previousDefaultTabsChecked", value).apply()

    /**
     * Set once this install has been through [LibraryTabKeepMigration]. A flag
     * rather than a [dataSchemaVersion] step, like [previousDefaultTabsChecked].
     */
    var libraryTabKeepChecked: Boolean
        get() = prefs.getBoolean("libraryTabKeepChecked", false)
        set(value) = prefs.edit().putBoolean("libraryTabKeepChecked", value).apply()

    /**
     * Set once What's New has asked whether Mail takes Calendar's place in the bottom bar, and
     * answered either way, or the install ran 2.3.0 or 2.3.1, whose What's New asked it.
     */
    var mailBarQuestionAsked: Boolean
        get() = prefs.getBoolean("mailBarQuestionAsked", false)
        set(value) = prefs.edit().putBoolean("mailBarQuestionAsked", value).apply()

    /** Deletes the old library opt-in switch, from before the library was always on. */
    fun forgetLegacyLibraryOptIn() {
        prefs.edit().remove(LEGACY_LIBRARY_OPT_IN).apply()
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

    /** One of the sizes on the Display and text size page, normalized; see [DisplayScale]. */
    fun displayScale(scale: DisplayScale): Float = scale.read(prefs)

    /** Stores [value] as [scale]'s nearest step. */
    fun setDisplayScale(scale: DisplayScale, value: Float) {
        prefs.edit().putFloat(scale.key, scale.normalize(value)).apply()
    }

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

    // Default off, as on the iPhone: with a library account signed in, the
    // first flip offers it (FlipToLibraryPrompt). Until 2.5 it defaulted on,
    // which FlipToLibraryDefaultOffMigration handles for an upgrade.
    var flipToLibraryEnabled: Boolean
        get() = prefs.getBoolean(FLIP_TO_LIBRARY, false)
        set(value) = prefs.edit().putBoolean(FLIP_TO_LIBRARY, value).apply()

    /** Whether [flipToLibraryEnabled] was ever written, by its switch or by the prompt. */
    val hasStoredFlipToLibrary: Boolean
        get() = prefs.contains(FLIP_TO_LIBRARY)

    /**
     * Set once this install has been through `FlipToLibraryDefaultOffMigration`.
     * A flag rather than a [dataSchemaVersion] step, like [previousDefaultTabsChecked].
     */
    var flipToLibraryDefaultOffChecked: Boolean
        get() = prefs.getBoolean("flipToLibraryDefaultOffChecked", false)
        set(value) = prefs.edit().putBoolean("flipToLibraryDefaultOffChecked", value).apply()

    // --- First-trigger prompts ---
    // One-shot "you just did X for the first time — keep it?" prompts, keyed by
    // a stable storage slug (see FirstTriggerPromptKey). The flag is written
    // only when the user taps one of the prompt's buttons, never on mere display,
    // so a prompt dismissed by any other path re-arms on the next trigger.
    override fun hasSeenFirstTriggerPrompt(storageKey: String): Boolean =
        prefs.getBoolean("firstTriggerPromptSeen.$storageKey", false)

    override fun setFirstTriggerPromptSeen(storageKey: String, seen: Boolean) {
        prefs.edit().apply {
            if (seen) putBoolean("firstTriggerPromptSeen.$storageKey", true)
            else remove("firstTriggerPromptSeen.$storageKey")
        }.apply()
    }

    // Emitted on an actual change to either setting below, whoever made it.
    // AppState's copy of them re-reads on it, because a pull of the shared
    // `notification` document writes these directly, not through AppState.
    private val _assignmentReminderSettingsChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val assignmentReminderSettingsChanged: SharedFlow<Unit> =
        _assignmentReminderSettingsChanged.asSharedFlow()

    override var notifyAssignments: Boolean
        get() = prefs.getBoolean("notifyAssignments", true)
        set(value) {
            val previous = notifyAssignments
            prefs.edit().putBoolean("notifyAssignments", value).apply()
            if (value != previous) _assignmentReminderSettingsChanged.tryEmit(Unit)
        }

    /**
     * Per-offset opt-in for assignment due reminders. Persisted as raw-value
     * strings so a freshly-added [AssignmentReminderOffset] entry deserialises
     * cleanly (unknown rawValues are simply dropped on read).
     *
     * Absent key (fresh install or upgrade from <= v1.4.x where only a single
     * 1h-before reminder existed) → seed with [AssignmentReminderOffset.DEFAULTS].
     */
    override var notifyAssignmentOffsets: Set<AssignmentReminderOffset>
        get() {
            val stored = prefs.getStringSet("notifyAssignmentOffsets", null)
                ?: return AssignmentReminderOffset.DEFAULTS
            return stored.mapNotNullTo(mutableSetOf()) { AssignmentReminderOffset.fromRawValue(it) }
        }
        set(value) {
            val previous = notifyAssignmentOffsets
            prefs.edit()
                .putStringSet("notifyAssignmentOffsets", value.map { it.rawValue }.toSet())
                .apply()
            if (value != previous) _assignmentReminderSettingsChanged.tryEmit(Unit)
        }

    /**
     * Whether [notifyAssignments]/[notifyAssignmentOffsets] as they stand now
     * have *not* yet been confirmed to have reached the server — the
     * assignments-section mirror of `LiveActivityPreferences.hasUnconfirmedSyncEdit`;
     * see that property's KDoc for why this has to be persisted rather than
     * kept in memory, and why a plain `Boolean` rather than a version counter.
     * A plain hand-written `getBoolean`/`putBoolean` pair, not a field on a
     * Gson-deserialized class, so the upgrade-safe-persistence rule does not
     * apply here.
     */
    var hasUnconfirmedAssignmentSyncEdit: Boolean
        get() = prefs.getBoolean("hasUnconfirmedAssignmentSyncEdit", false)
        set(value) = prefs.edit().putBoolean("hasUnconfirmedAssignmentSyncEdit", value).apply()

    // Emitted on an actual change to the class reminder's switch or lead
    // time, whoever made it — the settings screen or a pull of the shared
    // `notification` document — so LiveActivityManager re-arms the alarms.
    private val _classReminderSettingsChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val classReminderSettingsChanged: SharedFlow<Unit> = _classReminderSettingsChanged.asSharedFlow()

    /**
     * Class reminders on or off; see [ClassReminders]. The default is a new
     * install's: an existing one is given its value once, from the Live
     * Update settings it used to follow, by [ClassReminders.migrateIfNeeded].
     */
    var classReminderEnabled: Boolean
        get() = prefs.getBoolean("classReminderEnabled", true)
        set(value) {
            val previous = classReminderEnabled
            prefs.edit().putBoolean("classReminderEnabled", value).apply()
            if (value != previous) _classReminderSettingsChanged.tryEmit(Unit)
        }

    /**
     * Minutes before each class the reminder fires. One of
     * [ClassReminders.LEAD_CHOICES_MINUTES], or a lead time carried over from
     * the Live Update's slider, held to its range.
     */
    var classReminderLeadMinutes: Int
        get() = ClassReminders.clampLead(
            prefs.getInt("classReminderLeadMinutes", ClassReminders.DEFAULT_LEAD_MINUTES)
        )
        set(value) {
            val previous = classReminderLeadMinutes
            val clamped = ClassReminders.clampLead(value)
            prefs.edit().putInt("classReminderLeadMinutes", clamped).apply()
            if (clamped != previous) _classReminderSettingsChanged.tryEmit(Unit)
        }

    /**
     * The user picked [classReminderLeadMinutes] on this device since the
     * document's `courses.reminder_offsets_minutes` was last adopted. Until
     * then a push leaves those offsets alone: they may hold a value this
     * build offers no choice for. iOS's `hasPickedClassReminderLead`.
     */
    var hasPickedClassReminderLead: Boolean
        get() = prefs.getBoolean("hasPickedClassReminderLead", false)
        set(value) = prefs.edit().putBoolean("hasPickedClassReminderLead", value).apply()

    /**
     * The `courses` section's mirror of [hasUnconfirmedAssignmentSyncEdit]:
     * a class reminder edit not yet confirmed to have reached the server.
     */
    var hasUnconfirmedClassReminderSyncEdit: Boolean
        get() = prefs.getBoolean("hasUnconfirmedClassReminderSyncEdit", false)
        set(value) = prefs.edit().putBoolean("hasUnconfirmedClassReminderSyncEdit", value).apply()

    /**
     * The `notification` document's `live_activity`, `assignments` and
     * `courses` sections as this phone last synced them, the base of the
     * field-by-field merge; see `NotificationSettingsSync`'s
     * `LastSyncedSections`. Cleared at sign-out with the unconfirmed-edit
     * flags. JSON text the sync parses itself, not a class Gson fills in, so
     * no field of it can be missing after an upgrade; a build before these
     * keys wrote none, which the sync reads as nothing recorded.
     */
    var lastSyncedLiveActivitySettings: String?
        get() = prefs.getString("lastSyncedLiveActivitySettings", null)
        set(value) = prefs.edit().putString("lastSyncedLiveActivitySettings", value).apply()

    var lastSyncedAssignmentReminderSettings: String?
        get() = prefs.getString("lastSyncedAssignmentReminderSettings", null)
        set(value) = prefs.edit().putString("lastSyncedAssignmentReminderSettings", value).apply()

    var lastSyncedClassReminderSettings: String?
        get() = prefs.getString("lastSyncedClassReminderSettings", null)
        set(value) = prefs.edit().putString("lastSyncedClassReminderSettings", value).apply()

    /**
     * Set once this device has run the step for the first launch of the build
     * that brought the three keys above, signed in or not; see
     * `NotificationSettingsSync`'s `LastSyncedSeed`. Never cleared: it belongs
     * to the device, so a sign-out keeps it, and the step runs only once.
     */
    var lastSyncedSettingsSeedDone: Boolean
        get() = prefs.getBoolean("lastSyncedSettingsSeedDone", false)
        set(value) = prefs.edit().putBoolean("lastSyncedSettingsSeedDone", value).apply()

    /** Set once [ClassReminders.migrateIfNeeded] has given this install its class reminder settings. */
    var classReminderSettingsMigrated: Boolean
        get() = prefs.getBoolean("classReminderSettingsMigrated", false)
        set(value) = prefs.edit().putBoolean("classReminderSettingsMigrated", value).apply()

    var homeSections: List<HomeSection>
        get() = restoreHomeSections(prefs.getString("homeSections", null), gson)
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
     * When Moodle last answered a whole round of assignments, epoch ms; 0
     * for never, or not since the last sign-out. The age of what Home, the
     * class table and the calendar show, and what the background worker
     * measures its assignment refresh against — see
     * [org.ntust.app.tigerduck.ui.RefreshPolicies].
     *
     * A flow, for the "Last synced" row that shows it.
     */
    private val _schoolDataSyncedAtMs =
        MutableStateFlow(prefs.getLong("schoolDataSyncedAtMs", 0L))
    val schoolDataSyncedAtMs: StateFlow<Long> = _schoolDataSyncedAtMs.asStateFlow()

    fun markSchoolDataSynced(atMs: Long) {
        prefs.edit().putLong("schoolDataSyncedAtMs", atMs).apply()
        _schoolDataSyncedAtMs.value = atMs
    }

    /**
     * When the library last issued the signed-in library account a QR code,
     * epoch ms; 0 for never, or not since that account signed out. The age of
     * what the Library page shows, for its "Last synced" row. Forgotten with
     * the kept code itself once no library account is signed in — see
     * `LibrarySession` — not by [clearSyncStamps]: the library account is its
     * own, and a school sign-out takes it only when it is the same student's.
     */
    private val _librarySyncedAtMs =
        MutableStateFlow(prefs.getLong("librarySyncedAtMs", 0L))
    val librarySyncedAtMs: StateFlow<Long> = _librarySyncedAtMs.asStateFlow()

    fun markLibrarySynced(atMs: Long) {
        prefs.edit().putLong("librarySyncedAtMs", atMs).apply()
        _librarySyncedAtMs.value = atMs
    }

    fun forgetLibrarySynced() {
        prefs.edit().remove("librarySyncedAtMs").apply()
        _librarySyncedAtMs.value = 0L
    }

    /**
     * On sign-out, this stamp, the background worker's two below,
     * [syncedCategories], [assignmentMarksSyncedOnce],
     * [lastSeenCoursesResetAt] and the upload ledgers: what they record
     * belonged to the account that left, and the next account's worker must
     * not wait out the old one's hour before its first sync.
     */
    fun clearSyncStamps() {
        prefs.edit()
            .remove("schoolDataSyncedAtMs")
            .remove("backgroundOverridesSyncedAtMs")
            .remove("backgroundCoursesSyncedAtMs")
            .remove("backgroundCoursesTerm")
            .remove("syncedCategories")
            .remove("assignmentMarksSyncedOnce")
            .remove("lastSeenCoursesResetAt")
            .remove("assignmentsUploadFingerprint")
            .remove("assignmentsUploadedAtMs")
            .removeCourseUploads(null)
            .apply()
        forgetLegacyLastCourseSyncAt()
        _schoolDataSyncedAtMs.value = 0L
    }

    /**
     * The assignment list the backend last accepted from this phone, as a
     * fingerprint, and when; see `push.UploadLedger`. Forgotten at sign-out
     * by [clearSyncStamps] and when a sync switch comes back on by
     * [forgetUploads].
     */
    var assignmentsUploadFingerprint: String?
        get() = prefs.getString("assignmentsUploadFingerprint", null)
        set(value) = prefs.edit().putString("assignmentsUploadFingerprint", value).apply()

    var assignmentsUploadedAtMs: Long
        get() = prefs.getLong("assignmentsUploadedAtMs", 0L)
        set(value) = prefs.edit().putLong("assignmentsUploadedAtMs", value).apply()

    /**
     * The course list of [semester] the backend last accepted from this
     * phone, as a fingerprint, and when; see `push.UploadLedger`. A slot per
     * term, unlike the assignments': a sync uploads every term it holds, and
     * in one slot each term's list would read as a change from the last
     * term's. Forgotten with the assignment list at sign-out
     * ([clearSyncStamps]) and when a sync switch comes back on
     * ([forgetUploads]), and for one term by [forgetCourseUploads] when this
     * phone resets it.
     */
    fun coursesUploadFingerprint(semester: String): String? =
        prefs.getString(COURSES_UPLOAD_FINGERPRINT + semester, null)

    fun coursesUploadedAtMs(semester: String): Long = prefs.getLong(COURSES_UPLOADED_AT + semester, 0L)

    fun recordCoursesUpload(semester: String, fingerprint: String, atMs: Long) {
        prefs.edit()
            .putString(COURSES_UPLOAD_FINGERPRINT + semester, fingerprint)
            .putLong(COURSES_UPLOADED_AT + semester, atMs)
            .apply()
    }

    /**
     * The course numbers of [semester]'s list [coursesUploadFingerprint] holds when it went as
     * the term's roster, null when it went as none; see `PushApiClient.serverDropped`. Recorded
     * with it, and forgotten with it.
     */
    fun coursesUploadRoster(semester: String): Set<String>? = prefs.getStringSet(COURSES_UPLOAD_ROSTER + semester, null)

    fun recordCoursesUploadRoster(semester: String, roster: Collection<String>?) {
        val key = COURSES_UPLOAD_ROSTER + semester
        prefs.edit().apply { if (roster == null) remove(key) else putStringSet(key, roster.toSet()) }.apply()
    }

    /**
     * Forgets the course list the backend last accepted for [semester], or
     * for every term when null: a reset took it off the server, and the
     * upload after the reset, usually that same list, has to go.
     */
    fun forgetCourseUploads(semester: String?) {
        prefs.edit().removeCourseUploads(semester).apply()
    }

    private fun SharedPreferences.Editor.removeCourseUploads(semester: String?): SharedPreferences.Editor {
        if (semester != null) {
            return remove(COURSES_UPLOAD_FINGERPRINT + semester).remove(COURSES_UPLOADED_AT + semester)
                .remove(COURSES_UPLOAD_ROSTER + semester)
        }
        // The terms are not listed anywhere else; the keys are the list.
        prefs.all.keys
            .filter { key -> listOf(COURSES_UPLOAD_FINGERPRINT, COURSES_UPLOADED_AT, COURSES_UPLOAD_ROSTER).any(key::startsWith) }
            .forEach { remove(it) }
        return this
    }

    /**
     * Forgets the lists the backend last accepted, so the next upload of
     * each goes whatever it holds: the backend may have dropped them while a
     * sync switch was off on every device.
     */
    fun forgetUploads() {
        prefs.edit()
            .remove("assignmentsUploadFingerprint")
            .remove("assignmentsUploadedAtMs")
            .removeCourseUploads(null)
            .apply()
    }

    /** When the background worker last synced with the backend, epoch ms. */
    var backgroundOverridesSyncedAtMs: Long
        get() = prefs.getLong("backgroundOverridesSyncedAtMs", 0L)
        set(value) = prefs.edit().putLong("backgroundOverridesSyncedAtMs", value).apply()

    /**
     * When the background worker last refreshed the timetable, epoch ms. Its
     * own clock, unlike assignments': only the worker reads it, and only if
     * the class table asks for background refreshes.
     */
    var backgroundCoursesSyncedAtMs: Long
        get() = prefs.getLong("backgroundCoursesSyncedAtMs", 0L)
        set(value) = prefs.edit().putLong("backgroundCoursesSyncedAtMs", value).apply()

    /**
     * The term the background worker last fetched courses in, by the app's clock; null before the
     * first. Its first run in another term fetches that term's courses.
     */
    var backgroundCoursesTerm: String?
        get() = prefs.getString("backgroundCoursesTerm", null)
        set(value) = prefs.edit().putString("backgroundCoursesTerm", value).apply()

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
     * Set once [org.ntust.app.tigerduck.notification.NotificationChannels.deleteLegacyChannels]
     * has run on this install. A flag rather than a [dataSchemaVersion] step:
     * bumping the schema would make a later downgrade show the reset prompt,
     * and a stale notification channel is not worth that.
     */
    var legacyNotificationChannelsDeleted: Boolean
        get() = prefs.getBoolean("legacyNotificationChannelsDeleted", false)
        set(value) = prefs.edit().putBoolean("legacyNotificationChannelsDeleted", value).apply()

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
        val accentBefore = watchAccent()
        val presetBefore = visualPreset
        prefs.edit().clear().apply()
        // The watch hears of the accent and the interface style only through
        // accentColorChanged and visualPresetChanged, and a reset while signed
        // out publishes nothing else.
        if (watchAccent() != accentBefore) _accentColorChanged.tryEmit(Unit)
        if (visualPreset != presetBefore) _visualPresetChanged.tryEmit(Unit)
        forgetLegacyLastCourseSyncAt()
        // `alwaysShowAllPeriods` and the class table's display switches are
        // read from in-memory mirrors rather than the file, so clearing the
        // file alone leaves the class table pinned — and the Settings rows
        // reading "on" — until the next process start.
        _alwaysShowAllPeriods.value = false
        _showClassroomInClassTable.value = true
        _pinTodayCoursesOnClassTable.value = false
        _outlineRequiredCourses.value = false
        _schoolDataSyncedAtMs.value = 0L
        _librarySyncedAtMs.value = 0L
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

    /** Semester the user last viewed in the class table. Null until first pick. */
    var classTableSelectedSemester: String?
        get() = prefs.getString("classTableSelectedSemester", null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove("classTableSelectedSemester")
            else editor.putString("classTableSelectedSemester", value)
            editor.apply()
        }

    /**
     * The building the empty-classroom finder last showed at the user's pick, by the booking
     * site's code (e.g. "TR"). Null until the first pick; cleared at sign-out, so the next
     * account opens on its own building.
     */
    var emptyClassroomBuilding: String?
        get() = prefs.getString("emptyClassroomBuilding", null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove("emptyClassroomBuilding")
            else editor.putString("emptyClassroomBuilding", value)
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
    /**
     * Holidays the user asked to keep receiving class reminders on.
     *
     * Written whether or not cloud sync is on — the holiday guard itself is
     * not a sync feature — and additionally uploaded when sync is enabled so
     * a user's devices agree. Stored as ids rather than dates because a
     * holiday's range can be edited by an operator after the user opted in,
     * and the opt-in should follow the holiday.
     */
    var holidayNotifyOverrides: Set<Int>
        get() = prefs.getStringSet("holidayNotifyOverrides", emptySet())
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()
            ?: emptySet()
        set(value) = prefs.edit()
            .putStringSet("holidayNotifyOverrides", value.map(Int::toString).toSet())
            .apply()

    /**
     * Holidays whose choice in [holidayNotifyOverrides] the backend has not
     * acknowledged yet, kept across a restart: the upload that never landed
     * is the one that outlives the process that made it. See
     * `PendingHolidayOverrides`; the iPhone's `holidayOverridesAwaitingUpload`.
     */
    var holidayOverridesAwaitingUpload: Set<Int>
        get() = prefs.getStringSet("holidayOverridesAwaitingUpload", emptySet())
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()
            ?: emptySet()
        set(value) = prefs.edit()
            .putStringSet("holidayOverridesAwaitingUpload", value.map(Int::toString).toSet())
            .apply()

    /**
     * At sign-out: both holiday sets are the departing account's. Left
     * behind, the next student would inherit their quiet days, and the
     * awaiting set would send those choices up as the new account. As the
     * iPhone's `forgetHolidayOverrides`.
     */
    fun forgetHolidayOverrides() {
        prefs.edit()
            .remove("holidayNotifyOverrides")
            .remove("holidayOverridesAwaitingUpload")
            .apply()
    }

    var semesterCatalogTerms: List<String>
        get() = prefs.getString("semesterCatalogTerms", null)
            ?.split(SEMESTER_LIST_DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) = prefs.edit()
            .putString("semesterCatalogTerms", value.joinToString(SEMESTER_LIST_DELIMITER))
            .apply()

    /**
     * The term the course selection system is currently open for (`LoginEnable`). Runs
     * weeks ahead of the term in session, so it is not interchangeable with
     * the term in session from the published academic calendar.
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
        private const val PREFS_FILE = "tigerduck_prefs"

        /**
         * [scale] as stored, read straight from the file: for
         * MainActivity.attachBaseContext, which sizes the Activity before Hilt
         * can hand it this class.
         */
        fun storedDisplayScale(context: Context, scale: DisplayScale): Float =
            scale.read(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        /**
         * [moodleOpenIn] as stored, read straight from the file: for `openMoodlePage`, which every
         * "Open in Moodle" calls with no more than a context.
         */
        fun storedMoodleOpenIn(context: Context): String =
            readMoodleOpenIn(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        private const val MOODLE_OPEN_IN_KEY = "moodleOpenIn"

        private fun readMoodleOpenIn(prefs: SharedPreferences): String =
            prefs.getString(MOODLE_OPEN_IN_KEY, null)?.takeIf { it == MOODLE_OPEN_IN_BROWSER } ?: MOODLE_OPEN_IN_APP

        /** The same key the iPhone app stores its interface style under. */
        private const val VISUAL_PRESET_KEY = "visualPreset"
        // The iPhone's UserDefaults key names, for whoever reads both apps.
        private const val PIN_TODAY_COURSES_ON_CLASS_TABLE = "pinTodayCoursesOnClassTable"
        private const val OUTLINE_REQUIRED_COURSES = "outlineRequiredCourses"

        /** The course upload ledger's keys, one set per term, the term after the colon; see [coursesUploadFingerprint]. */
        private const val COURSES_UPLOAD_FINGERPRINT = "coursesUploadFingerprint:"
        private const val COURSES_UPLOADED_AT = "coursesUploadedAtMs:"
        private const val COURSES_UPLOAD_ROSTER = "coursesUploadRoster:"

        const val WHATS_NEW_UNSET = -1
        const val WHATS_NEW_REPLAY = 0

        /**
         * Current wizard revision. Bump when the wizard gains a page existing
         * users have to see; every install below it walks the wizard once more.
         *
         * 1 was the pre-2.0.1 wizard. 2 adds the TigerSync page — cross-device
         * sync defaults to on to match Apple, so an upgrade that never showed
         * that page would opt the user in without ever asking.
         */
        const val ONBOARDING_VERSION = 2

        /** Comma is safe: NTUST semester codes are `[0-9]{3}[12H]`. */
        private const val SEMESTER_LIST_DELIMITER = ","

        /** The library opt-in switch's key, kept only to delete it once (see [forgetLegacyLibraryOptIn]). */
        private const val LEGACY_LIBRARY_OPT_IN = "libraryFeatureEnabled"

        private const val FLIP_TO_LIBRARY = "flipToLibraryEnabled"

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

        const val MOODLE_OPEN_IN_APP = "app"
        const val MOODLE_OPEN_IN_BROWSER = "browser"

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
