package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.cache.DataCache
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
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val dataCache: DataCache,
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()

    private val courseSelectionRoot = "https://courseselection.ntust.edu.tw/"
    private val courseListUrl = "https://courseselection.ntust.edu.tw/ChooseList/D01/D01"
    private val courseSearchApiUrl = "https://querycourse.ntust.edu.tw/QueryCourse/api/courses"

    // Per-course metadata cache — see DataCache.CourseLookupEntry. Entries
    // are loaded from disk once on first use and written through on every
    // successful network fetch so a repeat open of 課表/Home skips the
    // per-course fan-out to querycourse.ntust.edu.tw entirely.
    private val lookupCache = ConcurrentHashMap<String, DataCache.CourseLookupEntry>()
    private val lookupCacheMutex = Mutex()
    @Volatile private var lookupCacheLoaded = false

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
            val key = "${semester}_${courseNo}"
            lookupCache[key]?.takeIf {
                System.currentTimeMillis() - it.cachedAt < LOOKUP_TTL_MS
            }?.let { return@withContext it.results }

            val requestBody = gson.toJson(CourseSearchRequest.forCourseNo(courseNo, semester))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl)
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            val fresh = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                gson.fromJson<List<CourseSearchResult>?>(body, type) ?: emptyList()
            }

            if (fresh.isNotEmpty()) {
                lookupCache[key] = DataCache.CourseLookupEntry(fresh, System.currentTimeMillis())
                persistLookupCache()
            }
            fresh
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
            val requestBody = gson.toJson(CourseSearchRequest.forCourseName(courseName, semester))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl)
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                gson.fromJson(body, type) ?: emptyList()
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
}
