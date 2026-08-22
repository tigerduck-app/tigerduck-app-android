package org.ntust.app.tigerduck.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.CourseSearchRequest
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed class CourseServiceError : Exception() {
    class NotAuthenticated : CourseServiceError()
    class RedirectedToSSO : CourseServiceError()
    class NoCourseData : CourseServiceError()
    data class NetworkError(val cause_: Exception) : CourseServiceError()
}

@Singleton
class CourseService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val dataCache: DataCache,
    private val appPreferences: AppPreferences,
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()

    private val courseSelectionRoot = "https://courseselection.ntust.edu.tw/"
    private val courseListUrl = "https://courseselection.ntust.edu.tw/ChooseList/D01/D01"
    private val courseSearchApiBaseUrl = "https://querycourse.ntust.edu.tw/QueryCourse/api/courses"

    // Per-course metadata cache — see DataCache.CourseLookupEntry. Entries
    // are loaded from disk once on first use and written through on every
    // successful network fetch so a repeat open of 課表/Home skips the
    // per-course fan-out to querycourse.ntust.edu.tw entirely.
    private val lookupCache = ConcurrentHashMap<String, DataCache.CourseLookupEntry>()
    private val lookupCacheMutex = Mutex()
    private val abbreviationCacheMutex = Mutex()

    @Volatile
    private var lookupCacheLoaded = false

    @Volatile
    private var abbreviationCacheLoaded = false

    @Volatile
    private var courseNameAbbr: Map<String, String> = emptyMap()

    @Volatile
    private var classroomNameAbbr: Map<String, ClassroomAbbrEntry> = emptyMap()

    suspend fun fetchEnrolledCourseNos(studentId: String, password: String): List<String> =
        withContext(Dispatchers.IO) {
            val loggedIn =
                ssoLoginService.ensureServiceLogin(courseSelectionRoot, studentId, password)
            if (!loggedIn) throw CourseServiceError.NotAuthenticated()

            val request = Request.Builder().url(courseListUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (response.request.url.host.contains("ssoam2.ntust.edu.tw")) {
                    throw CourseServiceError.RedirectedToSSO()
                }
                val html = response.body.string()

                val pattern = Regex("<tr>\\s*<td>\\s*(3?[A-Z]{2}[A-Z0-9]{6,7})\\s*</td>")
                pattern.findAll(html).map { it.groupValues[1] }.toList()
            }
        }

    suspend fun lookupCourse(
        semester: String,
        courseNo: String,
        lang: String = preferredCourseApiLanguage(),
    ): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            ensureLookupCacheLoaded()
            val language = lang
            // Cached results are always the raw API payload (full names);
            // applyAbbreviations runs at access time, so toggling the abbr
            // setting reuses the same cache entry and never refetches.
            val key = "${semester}_${courseNo}_${language}"
            lookupCache[key]?.takeIf {
                System.currentTimeMillis() - it.cachedAt < LOOKUP_TTL_MS
            }?.let { return@withContext applyAbbreviations(it.results, language) }

            val requestBody =
                gson.toJson(CourseSearchRequest.forCourseNo(courseNo, semester, language))
                    .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl(language))
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            val fresh = client.newCall(request).execute().use { response ->
                val body = response.body.string()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                gson.fromJson<List<CourseSearchResult>?>(body, type) ?: emptyList()
            }

            if (fresh.isNotEmpty()) {
                lookupCache[key] = DataCache.CourseLookupEntry(fresh, System.currentTimeMillis())
                persistLookupCache()
            }
            applyAbbreviations(fresh, language)
        }

    private suspend fun ensureLookupCacheLoaded() {
        if (lookupCacheLoaded) return
        lookupCacheMutex.withLock {
            if (lookupCacheLoaded) return
            lookupCache.putAll(dataCache.loadCourseLookups())
            lookupCacheLoaded = true
        }
    }

    private suspend fun persistLookupCache() = lookupCacheMutex.withLock {
        dataCache.saveCourseLookups(lookupCache.toMap())
    }

    suspend fun searchCourses(
        semester: String,
        courseName: String,
        lang: String = preferredCourseApiLanguage(),
    ): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            val language = lang
            val requestBody =
                gson.toJson(CourseSearchRequest.forCourseName(courseName, semester, language))
                    .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl(language))
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                val parsed: List<CourseSearchResult> = gson.fromJson(body, type) ?: emptyList()
                applyAbbreviations(parsed, language)
            }
        }

    suspend fun searchByTeacher(
        semester: String,
        teacher: String,
        lang: String = preferredCourseApiLanguage(),
    ): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            val language = lang
            val requestBody =
                gson.toJson(CourseSearchRequest.forCourseTeacher(teacher, semester, language))
                    .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl(language))
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                val parsed: List<CourseSearchResult> = gson.fromJson(body, type) ?: emptyList()
                applyAbbreviations(parsed, language)
            }
        }

    fun parseNodeToSchedule(node: String?): Map<Int, List<String>> {
        if (node.isNullOrEmpty()) return emptyMap()
        val dayMap = mapOf(
            'M' to 1, 'T' to 2, 'W' to 3, 'R' to 4, 'F' to 5, 'S' to 6, 'U' to 7
        )
        val schedule = mutableMapOf<Int, MutableList<String>>()
        node.split(",").forEach { item ->
            val trimmed = item.trim()
            val first = trimmed.firstOrNull() ?: return@forEach
            val day = dayMap[first] ?: return@forEach
            val periodId = trimmed.drop(1)
            if (periodId.isNotEmpty()) {
                schedule.getOrPut(day) { mutableListOf() }.add(periodId)
            }
        }
        return schedule
    }

    fun mergeSchedules(vararg nodes: String?): Map<Int, List<String>> {
        val merged = mutableMapOf<Int, MutableList<String>>()
        for (node in nodes) {
            parseNodeToSchedule(node).forEach { (day, periods) ->
                merged.getOrPut(day) { mutableListOf() }.addAll(periods)
            }
        }
        return merged
    }

    /**
     * Build a per-(weekday, period) classroom map from the rows the API
     * returned for a course. When a course meets in different rooms on
     * different days, the API returns one row per (room × day-set), each
     * with its own [CourseSearchResult.node] and [CourseSearchResult.classRoomNo].
     * The map lets [Course.classroom] resolve the right room for a given day.
     */
    fun buildClassroomMap(results: List<CourseSearchResult>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (row in results) {
            val room = (row.classRoomNo ?: "").trim()
            if (room.isEmpty()) continue
            val deduped = Course.dedupRooms(room)
            parseNodeToSchedule(row.node).forEach { (day, periods) ->
                for (period in periods) {
                    map["$day-$period"] = deduped
                }
            }
        }
        return map
    }

    /**
     * Resolve one course number into a [Course], from QueryCourse if it knows
     * the course and from Moodle enrolment metadata if it does not.
     *
     * A course legitimately meets in several rooms across several days, and
     * QueryCourse returns one row per (room x day-set) rather than one row per
     * course. So the rows are folded together here: schedules merge, the
     * per-slot classroom map is built across all of them, and [Course.classroom]
     * carries the de-duplicated union for surfaces that show a single string.
     * Everything else is read off the first row, which repeats the
     * course-level fields.
     *
     * Returns null only when both sources come up empty. A lookup that throws
     * degrades to the Moodle fallback rather than failing the whole roster —
     * one unreachable course must not blank out a timetable.
     */
    suspend fun lookupOrFallback(
        semester: String,
        courseNo: String,
        moodle: MoodleEnrolledCourse?,
    ): Course? = try {
        val results = lookupCourse(semester, courseNo)
        if (results.isEmpty()) {
            fallbackCourseFromMoodle(courseNo, moodle)
        } else {
            val first = results.first()
            val allRooms = LinkedHashSet<String>().apply {
                for (row in results) {
                    Course.splitRooms(row.classRoomNo ?: "").forEach { add(it) }
                }
            }
            Course.fromSchedule(
                courseNo = first.courseNo,
                courseName = first.courseName,
                instructor = first.courseTeacher,
                credits = first.creditPoint.toIntOrNull() ?: 0,
                classroom = allRooms.joinToString(", "),
                enrolledCount = first.chooseStudent ?: 0,
                maxCount = first.maxEnrollment,
                schedule = mergeSchedules(*results.map { it.node }.toTypedArray()),
                classroomMap = buildClassroomMap(results),
                moodleIdNumber = moodle?.idnumber ?: "${first.semester}${first.courseNo}",
                moodleNumericCourseId = moodle?.id,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG_LOOKUP, "Failed to lookup course $courseNo", e)
        fallbackCourseFromMoodle(courseNo, moodle)
    }

    /**
     * The term in session right now — what "today's courses" means for the
     * widget, the Wear tile, Home's carousel and every current-semester cache
     * key.
     *
     * Pinned to [org.ntust.app.tigerduck.AppConstants.CurrentTerm]. The month
     * heuristic this replaced still says 114-2 through August, which mislabels
     * the 115-1 term the school opened early. Swap the body for
     * [heuristicSemesterCode] to hand control back — it is left intact, so
     * lifting the pin is a one-line change.
     *
     * Not to be confused with [SemesterCatalog.selectionSemesterCode] — 選課
     * opens the *next* term weeks before this one ends.
     */
    fun currentSemesterCode(): String = org.ntust.app.tigerduck.AppConstants.CurrentTerm.CODE

    /**
     * The month-based guess [currentSemesterCode] used before it was pinned.
     * Kept so lifting the pin is a one-line change.
     */
    fun heuristicSemesterCode(): String = SemesterCodes.heuristic()

    companion object {
        // Kept as "HomeViewModel" so the existing logcat filter for a failed
        // course lookup still matches after the lookup moved here.
        private const val TAG_LOOKUP = "HomeViewModel"

        // Course metadata (name, instructor, schedule, caps) is stable within
        // a term; only ChooseStudent drifts. 30 min staleness on the enrolment
        // count is acceptable given the surrounding fields all update live
        // (assignments, Moodle enrolment list, etc.) on every refresh.
        private const val LOOKUP_TTL_MS = 30L * 60L * 1000L

        /**
         * Build a stub [Course] from Moodle enrolment metadata when QueryCourse
         * has no record (typically historical terms). Returns null when the
         * Moodle enrolment is itself missing.
         */
        fun fallbackCourseFromMoodle(courseNo: String, moodle: MoodleEnrolledCourse?): Course? {
            moodle ?: return null
            return Course.fromSchedule(
                courseNo = courseNo,
                courseName = (moodle.fullname ?: courseNo).decodeHtmlEntities(),
                moodleIdNumber = moodle.idnumber,
                moodleNumericCourseId = moodle.id,
            )
        }
    }

    fun preferredCourseApiLanguage(): String {
        return AppLanguageManager.resolvedCourseApiLanguage(appPreferences.appLanguage)
    }

    private fun courseSearchApiUrl(language: String): String {
        return if (language == "en") "$courseSearchApiBaseUrl?lang=en" else courseSearchApiBaseUrl
    }

    /**
     * Drops every in-memory lookup entry. Called when the API language
     * flips so the per-language keys ("..._zh", "..._en") don't accumulate
     * across a session. Persisted disk state catches up on the next
     * successful fetch — `lookupCourse` writes the whole map via
     * [persistLookupCache] after each new entry.
     */
    fun clearInMemoryLookupCache() {
        lookupCache.clear()
    }

    /**
     * Returns the full (un-abbreviated) course name from the in-memory
     * lookup cache, or null when no lookup entry exists (manual entries,
     * Moodle-only fallbacks, or cache not yet hydrated). Used by the
     * class-table popup so it always shows the full name regardless of
     * the abbreviation toggle.
     */
    fun cachedFullCourseName(semester: String, courseNo: String): String? {
        val language = preferredCourseApiLanguage()
        return lookupCache["${semester}_${courseNo}_$language"]
            ?.results
            ?.firstOrNull()
            ?.courseName
    }

    /**
     * Re-derive courseName / classroom for the given courses using whatever
     * is already in the lookup cache and the current abbreviation toggle.
     * Used when the user flips "Use English course abbreviations" so the
     * class table updates instantly without hitting the network. Courses
     * with no cached lookup (manual entries, Moodle-only fallbacks) are
     * returned unchanged.
     */
    suspend fun relabelCoursesForCurrentAbbrSetting(
        semester: String,
        courses: List<Course>
    ): List<Course> {
        if (courses.isEmpty()) return courses
        ensureLookupCacheLoaded()
        val language = preferredCourseApiLanguage()
        return courses.map { course ->
            val cached = lookupCache["${semester}_${course.courseNo}_$language"]
                ?.results
                ?.takeIf { it.isNotEmpty() }
                ?: return@map course
            val updated = applyAbbreviations(cached, language)
            val first = updated.first()
            val allRooms = LinkedHashSet<String>().apply {
                for (row in updated) {
                    Course.splitRooms(row.classRoomNo ?: "").forEach { add(it) }
                }
            }
            val flatClassroom = if (allRooms.isEmpty()) course.classroom
            else allRooms.joinToString(", ")
            val mapJson = Gson().toJson(buildClassroomMap(updated))
            course.copy(
                courseName = first.courseName,
                classroom = flatClassroom,
                classroomMapJson = mapJson,
            )
        }
    }

    private suspend fun applyAbbreviations(
        results: List<CourseSearchResult>,
        language: String
    ): List<CourseSearchResult> {
        if (language != "en" || results.isEmpty()) return results
        val courseToggle = appPreferences.useEnglishCourseAbbreviation
        val classroomToggle = appPreferences.useEnglishClassroomAbbreviation
        if (!courseToggle && !classroomToggle) return results
        ensureAbbreviationCacheLoaded()
        if (courseNameAbbr.isEmpty() && classroomNameAbbr.isEmpty()) return results

        val mandarinDisplay = appPreferences.classroomMandarinDisplay
        return results.map { result ->
            val newName = if (courseToggle) {
                courseNameAbbr[result.courseName] ?: result.courseName
            } else result.courseName
            val newRoom = if (classroomToggle) {
                result.classRoomNo?.let { abbreviateClassroomNames(it, mandarinDisplay) }
            } else result.classRoomNo
            if (newName == result.courseName && newRoom == result.classRoomNo) {
                result
            } else {
                result.copy(courseName = newName, classRoomNo = newRoom)
            }
        }
    }

    private fun abbreviateClassroomNames(raw: String, mandarinDisplay: String): String {
        if (raw.isBlank()) return raw
        return raw.split(",")
            .map { part ->
                val trimmed = part.trim()
                val entry = classroomNameAbbr[trimmed] ?: return@map trimmed
                val short = entry.shortenedName?.trim().orEmpty()
                val pinyin = entry.pinyin?.trim().orEmpty()
                val translated = entry.translated?.trim().orEmpty()
                val fallback = short.ifEmpty { trimmed }
                when (mandarinDisplay) {
                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_PINYIN ->
                        pinyin.ifEmpty { fallback }

                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_TRANSLATED ->
                        translated.ifEmpty { fallback }

                    else -> fallback
                }
            }
            .joinToString(", ")
    }

    private suspend fun ensureAbbreviationCacheLoaded() {
        if (abbreviationCacheLoaded) return
        abbreviationCacheMutex.withLock {
            if (abbreviationCacheLoaded) return
            courseNameAbbr = loadCourseNameAbbr()
            classroomNameAbbr = loadClassroomNameAbbr()
            abbreviationCacheLoaded = true
        }
    }

    private fun loadCourseNameAbbr(): Map<String, String> {
        return runCatching {
            context.assets.open("class-name-abbr.json").use { stream ->
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson<Map<String, String>>(stream.reader(Charsets.UTF_8), type).orEmpty()
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadClassroomNameAbbr(): Map<String, ClassroomAbbrEntry> {
        return runCatching {
            context.assets.open("classroom-name-abbr.json").use { stream ->
                val type = object : TypeToken<Map<String, ClassroomAbbrEntry>>() {}.type
                gson.fromJson<Map<String, ClassroomAbbrEntry>>(stream.reader(Charsets.UTF_8), type)
                    .orEmpty()
            }
        }.getOrDefault(emptyMap())
    }

    private data class ClassroomAbbrEntry(
        @SerializedName("shortened_name") val shortenedName: String? = null,
        @SerializedName("pinyin") val pinyin: String? = null,
        @SerializedName("translated") val translated: String? = null,
    )
}
