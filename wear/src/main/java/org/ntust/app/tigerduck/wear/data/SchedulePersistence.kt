package org.ntust.app.tigerduck.wear.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.ntust.app.tigerduck.shared.Course
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private val Context.scheduleDataStore by preferencesDataStore(name = "wear_schedule")

class SchedulePersistence(private val context: Context) {

    private val gson = Gson()

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

    val flow: Flow<WatchSnapshot> = context.scheduleDataStore.data.map { prefs -> readSnapshot(prefs) }

    suspend fun write(
        coursesGzipped: ByteArray,
        accentHex: String,
        syncedAtMs: Long,
        loggedIn: Boolean,
        languageTag: String?,
    ) {
        val coursesJson = decompress(coursesGzipped)
        context.scheduleDataStore.edit { prefs ->
            prefs[KEY_COURSES_JSON] = coursesJson
            prefs[KEY_ACCENT_HEX] = accentHex
            prefs[KEY_SYNCED_AT] = syncedAtMs
            prefs[KEY_LOGGED_IN] = loggedIn
            if (languageTag != null) prefs[KEY_LANGUAGE] = languageTag
        }
        if (languageTag != null) {
            bootstrapPrefs.edit().putString(BOOTSTRAP_KEY_LANGUAGE, languageTag).apply()
        }
    }

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

    private fun readSnapshot(prefs: Preferences): WatchSnapshot {
        val json = prefs[KEY_COURSES_JSON]
        val courses: List<Course> = if (json.isNullOrBlank()) emptyList() else parseCourses(json)
        return WatchSnapshot(
            courses = courses,
            accentHex = prefs[KEY_ACCENT_HEX] ?: DEFAULT_ACCENT,
            // Distinguish "key absent" (never synced) from "key present, value 0".
            // Preferences DataStore returns null for unset keys via the indexed accessor.
            syncedAtMs = prefs[KEY_SYNCED_AT],
            loggedIn = prefs[KEY_LOGGED_IN] ?: false,
            languageTag = prefs[KEY_LANGUAGE],
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

    private fun decompress(gz: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gz)).bufferedReader().use { it.readText() }

    /** Mirrors `WearScheduleBridge.CourseDto` on the wire. */
    private data class CourseWire(
        val courseNo: String,
        val courseName: String,
        val instructor: String,
        val credits: Int,
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
            credits = credits,
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
        // Sentinel for detecting un-obfuscated wire format; see parseCourses.
        // Trailing `:` proves this is an object key rather than a string
        // value that incidentally spells `courseNo` (Gson default emits
        // compact JSON with no whitespace, and any `"` inside a string
        // value is escaped as `\"`, so the unescaped quote+colon pair
        // cannot collide with payload content).
        private const val COURSE_NO_TOKEN = "\"courseNo\":"
        private val KEY_COURSES_JSON = stringPreferencesKey("courses_json")
        private val KEY_ACCENT_HEX = stringPreferencesKey("accent_hex")
        private val KEY_SYNCED_AT = longPreferencesKey("synced_at_ms")
        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
        private val KEY_LANGUAGE = stringPreferencesKey("language_tag")
        private val KEY_PADDING_DP = androidx.datastore.preferences.core.intPreferencesKey("padding_dp")
        private const val BOOTSTRAP_KEY_LANGUAGE = "language_tag"
    }
}

data class WatchSnapshot(
    val courses: List<Course>,
    val accentHex: String,
    val syncedAtMs: Long?,
    val loggedIn: Boolean,
    val languageTag: String?,
)
