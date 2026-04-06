package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.network.model.CourseSearchRequest
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import android.util.Log
import com.tigerduck.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.Calendar
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
    private val ssoLoginService: SsoLoginService
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()
    private val plainSearchClient: OkHttpClient by lazy {
        val interceptor = HttpLoggingInterceptor { message ->
            Log.d("TigerDuck-HTTP", message)
        }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private val courseSelectionRoot = "https://courseselection.ntust.edu.tw/"
    private val courseListUrl = "https://courseselection.ntust.edu.tw/ChooseList/D01/D01"
    private val courseSearchApiUrl = "https://querycourse.ntust.edu.tw/QueryCourse/api/courses"

    suspend fun fetchEnrolledCourseNos(studentId: String, password: String): List<String> =
        withContext(Dispatchers.IO) {
            val loggedIn = ssoLoginService.ensureServiceLogin(courseSelectionRoot, studentId, password)
            if (!loggedIn) throw CourseServiceError.NotAuthenticated

            val request = Request.Builder().url(courseListUrl).get().build()
            client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: throw CourseServiceError.NoCourseData

                if (response.request.url.host.contains("ssoam2.ntust.edu.tw")) {
                    throw CourseServiceError.RedirectedToSSO
                }

                val pattern = Regex("<tr>\\s*<td>\\s*(3?[A-Z]{2}[A-Z0-9]{6,7})\\s*</td>")
                pattern.findAll(html).map { it.groupValues[1] }.toList()
            }
        }

    suspend fun lookupCourse(semester: String, courseNo: String): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            val requestBody = gson.toJson(CourseSearchRequest.forCourseNo(courseNo, semester))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl)
                .post(requestBody)
                .build()

            val plainClient = plainSearchClient
            plainClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val type = object : TypeToken<List<CourseSearchResult>>() {}.type
                gson.fromJson(body, type) ?: emptyList()
            }
        }

    suspend fun searchCourses(semester: String, courseName: String): List<CourseSearchResult> =
        withContext(Dispatchers.IO) {
            val requestBody = gson.toJson(CourseSearchRequest.forCourseName(courseName, semester))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(courseSearchApiUrl)
                .post(requestBody)
                .build()

            val plainClient = plainSearchClient
            plainClient.newCall(request).execute().use { response ->
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
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val rocYear = year - 1911
        return if (month in 2..8) {
            "${rocYear - 1}2"
        } else {
            val academicYear = if (month >= 9) rocYear else rocYear - 1
            "${academicYear}1"
        }
    }
}
