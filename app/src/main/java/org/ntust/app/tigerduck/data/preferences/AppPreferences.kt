package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.data.model.HomeSection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {

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

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("hasCompletedOnboarding", false)
        set(value) = prefs.edit().putBoolean("hasCompletedOnboarding", value).apply()

    var accentColorHex: Int
        get() = prefs.getInt("accentColorHex", 0x007AFF)
        set(value) = prefs.edit().putInt("accentColorHex", value).apply()

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

    var libraryFeatureEnabled: Boolean
        get() = prefs.getBoolean("libraryFeatureEnabled", false)
        set(value) = prefs.edit().putBoolean("libraryFeatureEnabled", value).apply()

    var notifyAssignments: Boolean
        get() = prefs.getBoolean("notifyAssignments", true)
        set(value) = prefs.edit().putBoolean("notifyAssignments", value).apply()

    var homeSections: List<HomeSection>
        get() {
            val json = prefs.getString("homeSections", null) ?: return HomeSection.defaults()
            return try {
                val type = object : TypeToken<List<HomeSection>>() {}.type
                @Suppress("DEPRECATION")
                gson.fromJson<List<HomeSection>>(json, type)
                    ?.filter { it.type != HomeSection.HomeSectionType.QUICK_WIDGETS }
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

    /**
     * Read [key], or generate-and-persist a value via [factory] if absent.
     * Used by [org.ntust.app.tigerduck.push.PushIdentity] to mint a stable
     * device id on first launch.
     */
    @Synchronized
    fun getOrCreateString(key: String, factory: () -> String): String {
        prefs.getString(key, null)?.let { return it }
        val created = factory()
        prefs.edit().putString(key, created).commit()
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

    companion object {
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
