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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.ntust.app.tigerduck.shared.Course
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private val Context.scheduleDataStore by preferencesDataStore(name = "wear_schedule")

class SchedulePersistence(private val context: Context) {

    private val gson = Gson()

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
    }

    /** Synchronous one-shot read used by Activity onCreate to apply the
     *  cached language before the first frame is composed. Acceptable
     *  bootstrap pattern; not for hot paths. */
    fun readLanguageTagBlocking(): String? = runCatching {
        runBlocking { context.scheduleDataStore.data.first()[KEY_LANGUAGE] }
    }.getOrNull()

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
        val type = object : TypeToken<List<CourseWire>>() {}.type
        val wire: List<CourseWire> = gson.fromJson(json, type) ?: emptyList()
        return wire.map { it.toCourse() }
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
        private val KEY_COURSES_JSON = stringPreferencesKey("courses_json")
        private val KEY_ACCENT_HEX = stringPreferencesKey("accent_hex")
        private val KEY_SYNCED_AT = longPreferencesKey("synced_at_ms")
        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
        private val KEY_LANGUAGE = stringPreferencesKey("language_tag")
        private val KEY_PADDING_DP = androidx.datastore.preferences.core.intPreferencesKey("padding_dp")
    }
}

data class WatchSnapshot(
    val courses: List<Course>,
    val accentHex: String,
    val syncedAtMs: Long?,
    val loggedIn: Boolean,
    val languageTag: String?,
)
