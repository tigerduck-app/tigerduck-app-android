package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.CourseSearchRequest
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed class CourseServiceError : Exception() {
    object NotAuthenticated : CourseServiceError()
    object RedirectedToSSO : CourseServiceError()
    object NoCourseData : CourseServiceError()
    data class NetworkError(val cause_: Exception) : CourseServiceError()
}

@Singleton
class CourseService @Inject constructor(
    @ApplicationContext private val context: Context,
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
    @Volatile private var lookupCacheLoaded = false
    @Volatile private var abbreviationCacheLoaded = false
    @Volatile private var courseNameAbbr: Map<String, String> = emptyMap()
    @Volatile private var classroomNameAbbr: Map<String, String> = emptyMap()

    suspend fun fetchEnrolledCourseNos(studentId: String, password: String): List<String> =
        withContext(Dispatchers.IO) {
            val loggedIn = ssoLoginService.ensureServiceLogin(courseSelectionRoot, studentId, password)
            if (!loggedIn) throw CourseServiceError.NotAuthenticated

            val request = Request.Builder().url(courseListUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (response.request.url.host.contains("ssoam2.ntust.edu.tw")) {
                    throw CourseServiceError.RedirectedToSSO
                }
                val html = response.body?.string() ?: throw CourseServiceError.NoCourseData

                val pattern = Regex("<tr>\\s*<td>\\s*(3?[A-Z]{2}[A-Z0-9]{6,7})\\s*</td>")
                pattern.findAll(html).map { it.groupValues[1] }.toList()
            }
        }

    suspend fun lookupCourse(semester: String, courseNo: String): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            ensureLookupCacheLoaded()
            val language = preferredCourseApiLanguage()
            val key = "${semester}_${courseNo}_$language"
            lookupCache[key]?.takeIf {
                System.currentTimeMillis() - it.cachedAt < LOOKUP_TTL_MS
            }?.let { return@withContext applyAbbreviations(it.results, language) }

            val requestBody = gson.toJson(CourseSearchRequest.forCourseNo(courseNo, semester, language))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl(language))
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            val fresh = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                gson.fromJson<List<CourseSearchResult>?>(body, type) ?: emptyList()
            }
            val normalized = applyAbbreviations(fresh, language)

            if (normalized.isNotEmpty()) {
                lookupCache[key] = DataCache.CourseLookupEntry(normalized, System.currentTimeMillis())
                persistLookupCache()
            }
            normalized
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

    suspend fun searchCourses(semester: String, courseName: String): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            val language = preferredCourseApiLanguage()
            val requestBody = gson.toJson(CourseSearchRequest.forCourseName(courseName, semester, language))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl(language))
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                val parsed: List<CourseSearchResult> = gson.fromJson(body, type) ?: emptyList()
                applyAbbreviations(parsed, language)
            }
        }

    suspend fun searchByTeacher(semester: String, teacher: String): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            val language = preferredCourseApiLanguage()
            val requestBody = gson.toJson(CourseSearchRequest.forCourseTeacher(teacher, semester, language))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl(language))
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
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

    fun currentSemesterCode(): String {
        val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val rocYear = year - 1911
        return when (month) {
            in 2..8 -> "${rocYear - 1}2"   // Spring semester
            in 9..12 -> "${rocYear}1"       // Fall semester
            else -> "${rocYear - 1}1"       // January: still in prior fall semester
        }
    }

    companion object {
        // Course metadata (name, instructor, schedule, caps) is stable within
        // a term; only ChooseStudent drifts. 30 min staleness on the enrolment
        // count is acceptable given the surrounding fields all update live
        // (assignments, Moodle enrolment list, etc.) on every refresh.
        private const val LOOKUP_TTL_MS = 30L * 60L * 1000L
    }

    private fun preferredCourseApiLanguage(): String {
        val configured = AppLanguageManager.normalize(appPreferences.appLanguage)
        return when (configured) {
            AppLanguageManager.ENGLISH -> "en"
            AppLanguageManager.SYSTEM -> if (Locale.getDefault().language.equals("en", ignoreCase = true)) "en" else "zh"
            else -> "zh"
        }
    }

    private fun courseSearchApiUrl(language: String): String {
        return if (language == "en") "$courseSearchApiBaseUrl?lang=en" else courseSearchApiBaseUrl
    }

    private fun applyAbbreviations(results: List<CourseSearchResult>, language: String): List<CourseSearchResult> {
        if (language != "en" || results.isEmpty() || !appPreferences.useEnglishCourseAbbreviation) return results
        ensureAbbreviationCacheLoaded()
        if (courseNameAbbr.isEmpty() && classroomNameAbbr.isEmpty()) return results

        return results.map { result ->
            val shortenedCourseName = courseNameAbbr[result.courseName] ?: result.courseName
            val shortenedClassroom = result.classRoomNo?.let { abbreviateClassroomNames(it) }
            if (shortenedCourseName == result.courseName && shortenedClassroom == result.classRoomNo) {
                result
            } else {
                result.copy(
                    courseName = shortenedCourseName,
                    classRoomNo = shortenedClassroom
                )
            }
        }
    }

    private fun abbreviateClassroomNames(raw: String): String {
        if (raw.isBlank()) return raw
        return raw.split(",")
            .map { part ->
                val trimmed = part.trim()
                classroomNameAbbr[trimmed] ?: trimmed
            }
            .joinToString(", ")
    }

    private fun ensureAbbreviationCacheLoaded() {
        if (abbreviationCacheLoaded) return
        synchronized(this) {
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

    private fun loadClassroomNameAbbr(): Map<String, String> {
        return runCatching {
            context.assets.open("classroom-name-abbr.json").use { stream ->
                val type = object : TypeToken<Map<String, ClassroomAbbrEntry>>() {}.type
                val raw = gson.fromJson<Map<String, ClassroomAbbrEntry>>(stream.reader(Charsets.UTF_8), type).orEmpty()
                raw.mapNotNull { (name, entry) ->
                    val short = entry.shortenedName?.trim().orEmpty()
                    if (short.isEmpty()) null else name to short
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private data class ClassroomAbbrEntry(
        @SerializedName("shortened_name") val shortenedName: String? = null
    )
}
