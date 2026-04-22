package org.ntust.app.tigerduck.data.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.ScoreReport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists network-fetched data to JSON files in cache dir.
 * Survives app restarts, cleared when system needs space.
 */
@Singleton
class DataCache @Inject constructor(@ApplicationContext context: Context) {

    private val cacheDir: File = File(context.cacheDir, "TigerDuckCache").also { it.mkdirs() }
    // User-generated state that has no remote source — stored in filesDir so the OS never evicts it.
    private val userDataDir: File = File(context.filesDir, "TigerDuckData").also { it.mkdirs() }
    private val cacheMutex = Mutex()
    private val userDataMutex = Mutex()
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()

    init {
        absorbLegacyCourseCache()
    }

    // MARK: - Courses (semester-scoped)

    /** Save courses for a specific semester. File: courses_<semester>.json */
    suspend fun saveCourses(courses: List<Course>, semester: String) =
        save(courses, coursesFilename(semester))

    /** Load courses for a specific semester. Returns empty list when not cached. */
    suspend fun loadCourses(semester: String): List<Course> {
        val type = object : TypeToken<List<Course>>() {}.type
        return load<List<Course>>(type, coursesFilename(semester)) ?: emptyList()
    }

    // MARK: - Courses (current-semester aliases)
    // Home, BackgroundSyncWorker, LiveActivity, etc. operate on "whatever
    // the user is studying right now" so we keep a no-arg convenience that
    // always maps to the actual current semester.

    suspend fun saveCourses(courses: List<Course>) =
        saveCourses(courses, currentSemesterCode())

    suspend fun loadCourses(): List<Course> =
        loadCourses(currentSemesterCode())

    private fun coursesFilename(semester: String): String = "courses_$semester.json"

    /**
     * Pre-semester-scoped format stored at `courses.json`. Move it into the
     * current-semester file on first launch so existing users don't lose
     * their timetable when they upgrade; matches iOS's one-shot migration.
     */
    private fun absorbLegacyCourseCache() {
        val legacy = File(cacheDir, "courses.json")
        if (!legacy.exists()) return
        val target = File(cacheDir, coursesFilename(currentSemesterCode()))
        runCatching {
            if (!target.exists()) legacy.copyTo(target, overwrite = false)
            legacy.delete()
        }
    }

    private fun currentSemesterCode(): String {
        val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val rocYear = year - 1911
        return when (month) {
            in 2..8 -> "${rocYear - 1}2"
            in 9..12 -> "${rocYear}1"
            else -> "${rocYear - 1}1"
        }
    }

    // MARK: - Assignments

    suspend fun saveAssignments(assignments: List<Assignment>) = save(assignments, "assignments.json")

    suspend fun loadAssignments(): List<Assignment> {
        val type = object : TypeToken<List<Assignment>>() {}.type
        return load(type, "assignments.json") ?: emptyList()
    }

    // MARK: - Skipped Dates (courseNo -> list of ISO date strings "yyyy-MM-dd")
    // Stored in filesDir — never cleared by the OS, unlike cacheDir.

    suspend fun saveSkippedDates(data: Map<String, List<String>>) = saveToUserData(data, "skipped_dates.json")

    suspend fun loadSkippedDates(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        return loadFromUserData(type, "skipped_dates.json") ?: emptyMap()
    }

    // MARK: - Ignored Assignments (set of assignmentIds)
    // Stored in filesDir so the user's ignore decisions survive OS cache eviction
    // and remote re-fetches, mirroring skipped_dates.json handling.

    suspend fun saveIgnoredAssignments(ids: Set<String>) =
        saveToUserData(ids.toList(), "ignored_assignments.json")

    suspend fun loadIgnoredAssignments(): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return loadFromUserData<List<String>>(type, "ignored_assignments.json")?.toSet() ?: emptySet()
    }

    // MARK: - Score Report (per studentId)

    data class ScoreReportSnapshot(val report: ScoreReport, val cachedAt: Date)

    suspend fun saveScoreReport(report: ScoreReport, studentId: String) =
        save(ScoreReportSnapshot(report, Date()), scoreReportFilename(studentId))

    suspend fun loadScoreReport(studentId: String): ScoreReportSnapshot? {
        val type = object : TypeToken<ScoreReportSnapshot>() {}.type
        return load(type, scoreReportFilename(studentId))
    }

    suspend fun invalidateScoreReport(studentId: String) = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { File(cacheDir, scoreReportFilename(studentId)).delete() }
            Unit
        }
    }

    private fun scoreReportFilename(studentId: String): String = "score_$studentId.json"

    // MARK: - Calendar Events

    suspend fun saveCalendarEvents(events: List<CalendarEvent>) = save(events, "calendar_events.json")

    suspend fun loadCalendarEvents(): List<CalendarEvent> {
        val type = object : TypeToken<List<CalendarEvent>>() {}.type
        return load(type, "calendar_events.json") ?: emptyList()
    }

    // MARK: - Logout cleanup

    /**
     * Wipe every piece of user-scoped data so the next login does not inherit
     * the previous user's courses, assignments, calendar events, or skip
     * marks on the UI. School-wide calendar events are rebuilt on the next
     * sync, so it is fine to drop them here too.
     */
    suspend fun clearAllUserData() {
        cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                listOf("courses.json", "assignments.json", "calendar_events.json").forEach { name ->
                    runCatching { File(cacheDir, name).delete() }
                }
                // Drop every per-semester course bucket so historical data
                // doesn't bleed across accounts on the same device.
                runCatching {
                    cacheDir.listFiles { _, name -> name.startsWith("courses_") && name.endsWith(".json") }
                        ?.forEach { it.delete() }
                }
                runCatching {
                    cacheDir.listFiles { _, name -> name.startsWith("score_") && name.endsWith(".json") }
                        ?.forEach { it.delete() }
                }
            }
        }
        userDataMutex.withLock {
            withContext(Dispatchers.IO) {
                listOf("skipped_dates.json", "ignored_assignments.json").forEach { name ->
                    runCatching { File(userDataDir, name).delete() }
                }
            }
        }
    }

    // MARK: - Private helpers

    private suspend fun <T> save(value: T, filename: String) = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                File(cacheDir, filename).writeText(gson.toJson(value))
            } catch (e: Exception) {
                // Ignore write errors
            }
        }
    }

    private suspend fun <T> load(type: java.lang.reflect.Type, filename: String): T? = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val file = File(cacheDir, filename)
                if (!file.exists()) return@withContext null
                gson.fromJson(file.readText(), type)
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun <T> saveToUserData(value: T, filename: String) = userDataMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                File(userDataDir, filename).writeText(gson.toJson(value))
            } catch (e: Exception) { }
        }
    }

    private suspend fun <T> loadFromUserData(type: java.lang.reflect.Type, filename: String): T? = userDataMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val file = File(userDataDir, filename)
                if (!file.exists()) return@withContext null
                gson.fromJson(file.readText(), type)
            } catch (e: Exception) {
                null
            }
        }
    }
}
