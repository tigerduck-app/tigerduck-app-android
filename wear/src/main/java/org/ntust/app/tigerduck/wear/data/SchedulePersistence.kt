package org.ntust.app.tigerduck.wear.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.TodayClassEntry
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import org.ntust.app.tigerduck.shared.WearProtocol
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

private val Context.scheduleDataStore by preferencesDataStore(name = "wear_schedule")

class SchedulePersistence(private val context: Context) {

    /**
     * Tiny SharedPreferences shadow for values that need a *synchronous*
     * cold-start read (currently just the language tag, applied in
     * `attachBaseContext` before the first frame). Using SharedPreferences
     * here avoids `runBlocking` on the DataStore from the main thread.
     * DataStore remains the source of truth for the reactive [flow].
     */
    private val bootstrapPrefs by lazy {
        context.getSharedPreferences("wear_schedule_bootstrap", Context.MODE_PRIVATE)
    }

    val flow: Flow<WatchSnapshot> =
        context.scheduleDataStore.data.map { prefs -> readSnapshot(prefs) }

    /**
     * Dedicated flow for the screen-capture override so `LibraryQRScreen.
     * SecureScreen` doesn't have to depend on the full snapshot (and
     * recompose on every accent / course list change just to read a bool).
     */
    val disableScreenCaptureProtectionFlow: Flow<Boolean> =
        context.scheduleDataStore.data.map { prefs ->
            prefs[KEY_DISABLE_SCREEN_CAPTURE_PROTECTION] ?: false
        }

    suspend fun write(
        coursesGzipped: ByteArray,
        accentHex: String,
        accentEndHex: String?,
        accentDirectionCode: Int?,
        syncedAtMs: Long,
        loggedIn: Boolean,
        languageTag: String?,
        disableScreenCaptureProtection: Boolean,
        quietDays: List<String>?,
        visualPreset: String?,
        courseChanges: String?,
    ) {
        val coursesJson = decompress(coursesGzipped)
        // Under the lock with the start-up copy, so [syncLanguageTag] never writes between the two.
        languageLock.withLock {
            context.scheduleDataStore.edit { prefs ->
                writeSnapshot(
                    prefs, coursesJson, accentHex, accentEndHex, accentDirectionCode, syncedAtMs,
                    loggedIn, languageTag, disableScreenCaptureProtection, quietDays, visualPreset,
                    courseChanges,
                )
            }
            if (languageTag != null) commitLanguageTag(languageTag)
        }
    }

    /**
     * Brings the start-up copy of the language up to [languageTag] before the activity
     * recreates for it, if the snapshot still says [languageTag]: a newer sync may have
     * changed it since, and its copy must not be overwritten. True when the copy now says
     * [languageTag].
     */
    suspend fun syncLanguageTag(languageTag: String): Boolean = languageLock.withLock {
        val current = context.scheduleDataStore.data.first()[KEY_LANGUAGE]
        if (current != languageTag) return@withLock false
        commitLanguageTag(languageTag)
        true
    }

    /** Written synchronously: the next `attachBaseContext` must read it, even if the process dies right after. */
    private suspend fun commitLanguageTag(languageTag: String) {
        withContext(Dispatchers.IO) {
            bootstrapPrefs.edit().putString(BOOTSTRAP_KEY_LANGUAGE, languageTag).commit()
        }
    }

    private val languageLock = Mutex()

    /** Synchronous cold-start read used by Activity `attachBaseContext` to
     *  apply the cached language before the first frame is composed. Reads
     *  from a SharedPreferences shadow rather than blocking on DataStore so
     *  it's safe to call from the main thread. */
    fun readLanguageTagBlocking(): String? =
        bootstrapPrefs.getString(BOOTSTRAP_KEY_LANGUAGE, null)

    suspend fun writePaddingDp(value: Int) {
        context.scheduleDataStore.edit { prefs -> prefs[KEY_PADDING_DP] = value }
    }

    val paddingDpFlow: Flow<Int> =
        context.scheduleDataStore.data.map { prefs -> prefs[KEY_PADDING_DP] ?: DEFAULT_PADDING_DP }

    suspend fun writeQrPaddingDp(value: Int) {
        context.scheduleDataStore.edit { prefs -> prefs[KEY_QR_PADDING_DP] = value }
    }

    val qrPaddingDpFlow: Flow<Int> =
        context.scheduleDataStore.data.map { prefs ->
            prefs[KEY_QR_PADDING_DP] ?: DEFAULT_QR_PADDING_DP
        }

    private fun decompress(gz: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gz)).bufferedReader().use { it.readText() }

    /** Mirrors `WearScheduleBridge.CourseDto` on the wire. */
    private data class CourseWire(
        val courseNo: String,
        val courseName: String,
        val instructor: String,
        /** Whole credits. An older phone sends only this; see [creditsExact]. */
        val credits: Int,
        /**
         * The real, possibly fractional count. Nullable because a phone that
         * predates half-credit support omits the key entirely, and Gson's
         * Unsafe path leaves it null rather than applying the default.
         */
        val creditsExact: Float? = null,
        val classroom: String,
        val scheduleJson: String,
        // Older phones (pre-classroomMap) won't include this field; Gson
        // returns "" → fall back to "{}" so [Course.classroomMap] decodes
        // to an empty map rather than throwing.
        val classroomMapJson: String? = null,
        val moodleIdNumber: String?,
        val customColorHex: String?,
        val isManual: Boolean,
    ) {
        fun toCourse(): Course = Course(
            courseNo = courseNo,
            courseName = courseName,
            instructor = instructor,
            credits = creditsExact ?: credits.toFloat(),
            classroom = classroom,
            scheduleJson = scheduleJson,
            classroomMapJson = classroomMapJson?.takeIf { it.isNotEmpty() } ?: "{}",
            moodleIdNumber = moodleIdNumber,
            customColorHex = customColorHex,
            isManual = isManual,
        )
    }

    companion object {
        const val DEFAULT_ACCENT = "#007AFF"
        const val DEFAULT_PADDING_DP = 12
        const val MIN_PADDING_DP = 0
        const val MAX_PADDING_DP = 24

        // QR padding is its own setting (fullscreen library QR only). Default
        // 0 — the QR fills the chassis-inscribed limit unless the user pulls
        // it in. Range matches the screen padding for UX consistency.
        const val DEFAULT_QR_PADDING_DP = 0
        const val MIN_QR_PADDING_DP = 0
        const val MAX_QR_PADDING_DP = 24

        // Sentinel for detecting un-obfuscated wire format; see parseCourses.
        // Trailing `:` proves this is an object key rather than a string
        // value that incidentally spells `courseNo` (Gson default emits
        // compact JSON with no whitespace, and any `"` inside a string
        // value is escaped as `\"`, so the unescaped quote+colon pair
        // cannot collide with payload content).
        private const val COURSE_NO_TOKEN = "\"courseNo\":"
        private val KEY_COURSES_JSON = stringPreferencesKey("courses_json")
        private val KEY_ACCENT_HEX = stringPreferencesKey("accent_hex")
        private val KEY_ACCENT_END_HEX = stringPreferencesKey("accent_end_hex")
        private val KEY_ACCENT_DIRECTION = intPreferencesKey("accent_direction")
        private val KEY_SYNCED_AT = longPreferencesKey("synced_at_ms")
        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
        private val KEY_LANGUAGE = stringPreferencesKey("language_tag")
        private val KEY_PADDING_DP =
            androidx.datastore.preferences.core.intPreferencesKey("padding_dp")
        private val KEY_QR_PADDING_DP =
            androidx.datastore.preferences.core.intPreferencesKey("qr_padding_dp")
        private val KEY_DISABLE_SCREEN_CAPTURE_PROTECTION =
            booleanPreferencesKey("disable_screen_capture_protection")
        private val KEY_QUIET_DAYS = stringSetPreferencesKey("quiet_days")
        private val KEY_VISUAL_PRESET = stringPreferencesKey("visual_preset")
        private val KEY_COURSE_CHANGES = stringPreferencesKey("course_changes")
        private const val BOOTSTRAP_KEY_LANGUAGE = "language_tag"

        private val gson = Gson()

        /** Stores one schedule payload; [write] decompresses the courses first. */
        internal fun writeSnapshot(
            prefs: MutablePreferences,
            coursesJson: String,
            accentHex: String,
            accentEndHex: String?,
            accentDirectionCode: Int?,
            syncedAtMs: Long,
            loggedIn: Boolean,
            languageTag: String?,
            disableScreenCaptureProtection: Boolean,
            quietDays: List<String>?,
            visualPreset: String? = null,
            courseChanges: String? = null,
        ) {
            prefs[KEY_COURSES_JSON] = coursesJson
            prefs[KEY_ACCENT_HEX] = accentHex
            // Replaced on every write: a single color, and a phone that
            // predates gradients, send no end, and the accent then draws alone.
            if (accentEndHex != null) {
                prefs[KEY_ACCENT_END_HEX] = accentEndHex
            } else {
                prefs.remove(KEY_ACCENT_END_HEX)
            }
            if (accentEndHex != null && accentDirectionCode != null) {
                prefs[KEY_ACCENT_DIRECTION] = accentDirectionCode
            } else {
                prefs.remove(KEY_ACCENT_DIRECTION)
            }
            prefs[KEY_SYNCED_AT] = syncedAtMs
            prefs[KEY_LOGGED_IN] = loggedIn
            if (languageTag != null) prefs[KEY_LANGUAGE] = languageTag
            prefs[KEY_DISABLE_SCREEN_CAPTURE_PROTECTION] = disableScreenCaptureProtection
            // Replaced on every write, not kept like the language tag: a phone
            // that predates quiet days sends none, and the watch must then show
            // every class rather than the days an earlier phone sent.
            prefs[KEY_QUIET_DAYS] = quietDays.orEmpty().toSet()
            // Replaced on every write too: a phone that predates the interface style
            // sends none, and the watch then draws TigerDuck's cards.
            if (visualPreset != null) prefs[KEY_VISUAL_PRESET] = visualPreset else prefs.remove(KEY_VISUAL_PRESET)
            // Replaced on every write too, as the phone sends the term's every change each time: a
            // phone that predates them sends none, and every class then meets as the timetable has
            // it. Kept as sent and read leniently in readSnapshot, so a malformed one costs only the
            // changes.
            if (courseChanges != null) prefs[KEY_COURSE_CHANGES] = courseChanges else prefs.remove(KEY_COURSE_CHANGES)
        }

        internal fun readSnapshot(prefs: Preferences): WatchSnapshot {
            val json = prefs[KEY_COURSES_JSON]
            val courses: List<Course> = if (json.isNullOrBlank()) emptyList() else parseCourses(json)
            val accentEndHex = prefs[KEY_ACCENT_END_HEX]
            return WatchSnapshot(
                courses = courses,
                accentHex = prefs[KEY_ACCENT_HEX] ?: DEFAULT_ACCENT,
                // Distinguish "key absent" (never synced) from "key present, value 0".
                // Preferences DataStore returns null for unset keys via the indexed accessor.
                syncedAtMs = prefs[KEY_SYNCED_AT],
                loggedIn = prefs[KEY_LOGGED_IN] ?: false,
                languageTag = prefs[KEY_LANGUAGE],
                // Absent from a snapshot an older watch build stored.
                quietDays = WearProtocol.Schedule.decodeQuietDays(prefs[KEY_QUIET_DAYS]),
                // Absent too from an older build's: the accent draws alone.
                accentEndHex = accentEndHex,
                accentDirection = accentEndHex?.let {
                    prefs[KEY_ACCENT_DIRECTION]?.let(AccentGradientDirection::fromCode)
                        ?: AccentGradientDirection.DEFAULT
                },
                // Absent from an older phone's or watch build's: TigerDuck's cards.
                visualPreset = prefs[KEY_VISUAL_PRESET],
                // Absent from an older phone's or watch build's, and dropped when malformed: no changes.
                courseChanges = WearProtocol.Schedule.decodeCourseChanges(prefs[KEY_COURSE_CHANGES]),
            )
        }

        private fun parseCourses(json: String): List<Course> {
            // v1.4.0 phone shipped without a keep rule for CourseDto, so its
            // wire JSON used R8-obfuscated keys (e.g. `{"a":"..."}`). v1.4.1
            // wear expects `"courseNo"` etc. — if the DataStore snapshot was
            // written by v1.4.0, Gson populates CourseWire with null Strings
            // and `toCourse()` NPEs through Course's non-null constructor.
            // The watch's launch-time sync request will replace this snapshot
            // shortly, so dropping it on the floor is the safest fallback.
            // Sentinel requires the trailing `:` so a value that happens to
            // contain the substring `courseNo` cannot fake an object key.
            // Parse is also try/caught: a truncated or otherwise malformed
            // payload that still contains the token must not bubble an
            // exception up through the snapshot flow.
            if (!json.contains(COURSE_NO_TOKEN)) return emptyList()
            return try {
                val type = object : TypeToken<List<CourseWire>>() {}.type
                val wire: List<CourseWire> = gson.fromJson(json, type) ?: emptyList()
                wire.map { it.toCourse() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

data class WatchSnapshot(
    val courses: List<Course>,
    /** The accent; with a gradient, its start. */
    val accentHex: String,
    val syncedAtMs: Long?,
    val loggedIn: Boolean,
    val languageTag: String?,
    /** Days the phone says no class meets for this user; empty from an older phone. */
    val quietDays: Set<LocalDate>,
    /** The end of the accent gradient, or null for a single color, as from an older phone or watch build. */
    val accentEndHex: String? = null,
    /** The way the accent gradient runs, set exactly when [accentEndHex] is. */
    val accentDirection: AccentGradientDirection? = null,
    /**
     * The phone's interface style as it stores it ("default", "tat"), or null from an
     * older phone or watch build; [org.ntust.app.tigerduck.wear.ui.theme.WatchCardStyle]
     * reads it.
     */
    val visualPreset: String? = null,
    /** The classes the school changed, by course and day; empty from an older phone or watch build. */
    val courseChanges: WatchCourseChanges = WatchCourseChanges.EMPTY,
) {
    /**
     * Now and next at [minuteOfDay] on [today], leaving out the classes of quiet days, and with
     * each day's changed classes in place of their blocks, the cancelled ones left out.
     */
    fun nextClass(today: LocalDate, minuteOfDay: Int): NextClassResult =
        NextClassResolver.resolve(
            courses,
            today.dayOfWeek.value,
            minuteOfDay,
            changedClasses = { courseNo, daysAhead -> courseChanges.on(courseNo, today.plusDays(daysAhead.toLong())) },
        ) { daysAhead ->
            today.plusDays(daysAhead.toLong()) in quietDays
        }

    /** Today's classes as the school changed them, none when [today] is a quiet day. */
    fun todaysClasses(today: LocalDate, minuteOfDay: Int): List<TodayClassEntry> =
        NextClassResolver.todaysClasses(
            courses, today.dayOfWeek.value, minuteOfDay, quietToday = today in quietDays,
            changedClasses = { courseChanges.on(it, today) },
        )
}
