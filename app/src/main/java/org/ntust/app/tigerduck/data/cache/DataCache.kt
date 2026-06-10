package org.ntust.app.tigerduck.data.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.model.CourseSearchResult
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

    // One-shot legacy cache migration is deferred to the first course op so
    // the file I/O never lands on the main thread via Hilt-driven singleton
    // construction (would trip StrictMode and risk ANR on slow storage).
    @Volatile
    private var legacyCourseCacheAbsorbed = false

    @Volatile
    private var onCoursesSaved: (() -> Unit)? = null

    fun setOnCoursesSavedListener(listener: () -> Unit) {
        onCoursesSaved = listener
    }

    // MARK: - Courses (semester-scoped)

    /**
     * Save courses for a specific semester. Splits the list: remote-fetched
     * courses go to `cacheDir/courses_<semester>.json` (evictable), manual
     * courses go to `userDataDir/manual_courses_<semester>.json` (durable).
     * Manual courses have no remote source — if the cache is evicted they
     * can only be recovered from userDataDir.
     */
    suspend fun saveCourses(courses: List<Course>, semester: String) {
        absorbLegacyCourseCache()
        val (manual, remote) = courses.partition { it.isManual }
        save(remote, coursesFilename(semester))
        saveToUserData(manual, manualCoursesFilename(semester))
        onCoursesSaved?.invoke()
    }

    /**
     * Load courses for a specific semester. Merges remote-fetched (cacheDir)
     * and manual (userDataDir) files; manual courses win on courseNo collision
     * so the user's isManual flag is preserved even if the remote list was
     * evicted and a re-fetch has not yet repopulated it.
     */
    suspend fun loadCourses(semester: String): List<Course> {
        absorbLegacyCourseCache()
        migrateManualCoursesToUserData(semester)
        val type = object : TypeToken<List<Course>>() {}.type
        // requireContent: v1.4.0 shipped without a keep rule for the moved
        // shared.Course, so cache files written by that build have R8-
        // obfuscated keys. Deserializing them returns Course objects with
        // null-typed-as-non-null Strings — the first caller that reads
        // `courseNo` / `displayName` NPEs. DataMigration sweeps these
        // files on first injection of AppState, but Application.onCreate
        // launches wearBridge.publish() on Dispatchers.IO and can race
        // ahead of MainActivity-triggered migration. Guard at every read.
        val remote = load<List<Course>>(
            type,
            coursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        ) ?: emptyList()
        val manual = loadFromUserData<List<Course>>(
            type,
            manualCoursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        ) ?: emptyList()
        if (manual.isEmpty()) return remote
        val manualNos = manual.map { it.courseNo }.toSet()
        return remote.filter { it.courseNo !in manualNos } + manual
    }

    private fun manualCoursesFilename(semester: String): String = "manual_courses_$semester.json"

    /**
     * One-shot migration for installs that were already writing manual
     * courses to cacheDir. If the cache still contains isManual entries and
     * the durable file is empty, copy them across.
     *
     * Note: bare file-existence is insufficient — saveCourses always writes
     * the user-data file (even with `[]` for users with zero manual courses
     * at the time), which would otherwise mask a later legacy import that
     * does contain manual entries.
     */
    private suspend fun migrateManualCoursesToUserData(semester: String) {
        val type = object : TypeToken<List<Course>>() {}.type
        val existing = loadFromUserData<List<Course>>(
            type,
            manualCoursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        )
        if (!existing.isNullOrEmpty()) return
        val cached = load<List<Course>>(
            type,
            coursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        ) ?: return
        val manual = cached.filter { it.isManual }
        if (manual.isEmpty()) return
        saveToUserData(manual, manualCoursesFilename(semester))
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
    private suspend fun absorbLegacyCourseCache() {
        if (legacyCourseCacheAbsorbed) return
        cacheMutex.withLock {
            if (legacyCourseCacheAbsorbed) return@withLock
            withContext(Dispatchers.IO) {
                val legacy = File(cacheDir, "courses.json")
                if (legacy.exists()) {
                    val target = File(cacheDir, coursesFilename(currentSemesterCode()))
                    runCatching {
                        if (!target.exists()) legacy.copyTo(target, overwrite = false)
                        legacy.delete()
                    }
                }
                legacyCourseCacheAbsorbed = true
            }
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

    suspend fun saveAssignments(assignments: List<Assignment>) =
        save(assignments, "assignments.json")

    suspend fun loadAssignments(): List<Assignment> {
        val type = object : TypeToken<List<Assignment>>() {}.type
        return load(type, "assignments.json") ?: emptyList()
    }

    // MARK: - Skipped Dates (courseNo -> list of ISO date strings "yyyy-MM-dd")
    // Stored in filesDir — never cleared by the OS, unlike cacheDir.

    suspend fun saveSkippedDates(data: Map<String, List<String>>) =
        saveToUserData(data, "skipped_dates.json")

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
        return loadFromUserData<List<String>>(type, "ignored_assignments.json")?.toSet()
            ?: emptySet()
    }

    // MARK: - Marked-Completed Assignments (set of assignmentIds)
    // Independent from Moodle's `isCompleted`: lets the user manually flag a
    // homework as done from the swipe gesture even when Moodle hasn't (or
    // won't) record a submission. Persisted in filesDir alongside the ignore
    // list so it survives remote re-fetches.

    suspend fun saveMarkedCompletedAssignments(ids: Set<String>) =
        saveToUserData(ids.toList(), "marked_completed_assignments.json")

    suspend fun loadMarkedCompletedAssignments(): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return loadFromUserData<List<String>>(type, "marked_completed_assignments.json")?.toSet()
            ?: emptySet()
    }

    // MARK: - Score Report (per studentId)

    /**
     * Fields are nullable as Gson-Unsafe defense (see CLAUDE.md): this class
     * is deserialized from score_<id>.json across upgrades, and Gson bypasses
     * the constructor, so a cache file written before a future field/shape
     * change would leave non-null fields silently null. Readers must
     * null-check both fields.
     */
    data class ScoreReportSnapshot(val report: ScoreReport?, val cachedAt: Date?)

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

    // MARK: - Course lookup (querycourse.ntust.edu.tw responses)
    // Course metadata (name, instructor, schedule, caps) is static within a
    // semester; only ChooseStudent drifts, and drifting by a few minutes is
    // tolerable for a pull-to-refresh UX. Caching the raw CourseSearchResult
    // lets a repeat "課表" or Home load skip the per-course fan-out entirely.

    data class CourseLookupEntry(
        val results: List<CourseSearchResult>,
        val cachedAt: Long,
    )

    suspend fun saveCourseLookups(map: Map<String, CourseLookupEntry>) =
        save(map, "course_lookups.json")

    suspend fun loadCourseLookups(): Map<String, CourseLookupEntry> {
        val type = object : TypeToken<Map<String, CourseLookupEntry>>() {}.type
        return load<Map<String, CourseLookupEntry>>(type, "course_lookups.json") ?: emptyMap()
    }

    // MARK: - Moodle course id map (idnumber → numeric id)
    // Powers the course-detail "Open in Moodle" deep link, which needs the
    // numeric id (Moodle's web endpoint won't accept the idnumber). The map
    // is harvested from `fetchEnrolledCourses` across ALL semesters so
    // historical terms keep working too. Cached so a transient Moodle
    // failure doesn't make the button vanish for the rest of the session;
    // wiped on logout via [clearAllUserData] so it can't survive an
    // account switch.

    suspend fun saveMoodleCourseIds(map: Map<String, Int>) =
        save(map, "moodle_course_ids.json")

    suspend fun loadMoodleCourseIds(): Map<String, Int> {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return load<Map<String, Int>>(type, "moodle_course_ids.json") ?: emptyMap()
    }

    // MARK: - Calendar Events

    suspend fun saveCalendarEvents(events: List<CalendarEvent>) =
        save(events, "calendar_events.json")

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
                listOf(
                    "courses.json",
                    "assignments.json",
                    "calendar_events.json",
                    "course_lookups.json",
                    "moodle_course_ids.json",
                ).forEach { name ->
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
                listOf(
                    "skipped_dates.json",
                    "ignored_assignments.json",
                    "marked_completed_assignments.json",
                ).forEach { name ->
                    runCatching { File(userDataDir, name).delete() }
                }
                runCatching {
                    userDataDir.listFiles { _, name ->
                        name.startsWith("manual_courses_") && name.endsWith(
                            ".json"
                        )
                    }
                        ?.forEach { it.delete() }
                }
            }
        }
    }

    // MARK: - Private helpers

    private suspend fun <T> save(value: T, filename: String) = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            atomicWrite(File(cacheDir, filename), gson.toJson(value))
        }
    }

    private suspend fun <T> load(
        type: java.lang.reflect.Type,
        filename: String,
        requireContent: String? = null,
    ): T? =
        cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(cacheDir, filename)
                    if (!file.exists()) return@withContext null
                    val text = file.readText()
                    if (requireContent != null && !text.contains(requireContent)) {
                        return@withContext null
                    }
                    gson.fromJson(text, type)
                } catch (e: Exception) {
                    null
                }
            }
        }

    private suspend fun <T> saveToUserData(value: T, filename: String) = userDataMutex.withLock {
        withContext(Dispatchers.IO) {
            atomicWrite(File(userDataDir, filename), gson.toJson(value))
        }
    }

    /**
     * Write-to-temp + rename so a crash or full disk mid-write can't leave a
     * truncated JSON file behind. This matters most for userDataDir: a
     * truncated `manual_courses_*.json` loses data that has no remote source
     * to re-fetch from. rename() within the same directory is atomic on the
     * filesystems Android uses. Failures are swallowed (callers can't do
     * anything useful with them) but logged — durable user data silently
     * failing to persist is otherwise undiagnosable in the field.
     */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // Rename-over-existing can fail on some filesystems; retry
                // after deleting the target.
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw java.io.IOException("rename ${tmp.name} -> ${target.name} failed")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to write ${target.name}", e)
            runCatching { tmp.delete() }
        }
    }

    private suspend fun <T> loadFromUserData(
        type: java.lang.reflect.Type,
        filename: String,
        requireContent: String? = null,
    ): T? =
        userDataMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(userDataDir, filename)
                    if (!file.exists()) return@withContext null
                    val text = file.readText()
                    if (requireContent != null && !text.contains(requireContent)) {
                        return@withContext null
                    }
                    gson.fromJson(text, type)
                } catch (e: Exception) {
                    null
                }
            }
        }

    private companion object {
        private const val TAG = "DataCache"

        // Sentinel literal present in any course JSON written by a build
        // with un-obfuscated field names. Used by loadCourses to reject
        // R8-obfuscated v1.4.0 cache files before they reach Gson.
        // Trailing `:` keeps this anchored to an object key — a payload
        // string value that contains the substring `courseNo` would not
        // include an unescaped quote+colon pair, so it can't satisfy the
        // check and slip past as if the keys were un-obfuscated.
        const val COURSE_NO_TOKEN = "\"courseNo\":"
    }
}
