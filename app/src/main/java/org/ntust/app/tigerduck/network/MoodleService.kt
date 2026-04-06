package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleCalendarRequest
import org.ntust.app.tigerduck.network.model.MoodleCalendarWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

sealed class MoodleServiceError : Exception() {
    object NotAuthenticated : MoodleServiceError()
    object SesskeyNotFound : MoodleServiceError()
    object InvalidResponse : MoodleServiceError()
}

@Singleton
class MoodleService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()

    private val moodleLoginUrl = "https://moodle2.ntust.edu.tw/login/index.php"
    private val moodleApiTemplate = "https://moodle2.ntust.edu.tw/lib/ajax/service.php?sesskey=%s&info=core_calendar_get_action_events_by_timesort"

    suspend fun fetchAssignments(studentId: String, password: String): List<Assignment> =
        withContext(Dispatchers.IO) {
            // Step 1: Login to Moodle via SSO
            val loggedIn = ssoLoginService.ensureServiceLogin(moodleLoginUrl, studentId, password)
            if (!loggedIn) throw MoodleServiceError.NotAuthenticated

            // Step 2: Visit Moodle to get sesskey
            val pageRequest = Request.Builder().url(moodleLoginUrl).get().build()
            val pageHTML = client.newCall(pageRequest).execute().use { response ->
                response.body?.string() ?: throw MoodleServiceError.InvalidResponse
            }

            // Extract sesskey
            val sesskeyRegex = Regex("\"sesskey\":\"([^\"]+)\"")
            val sesskey = sesskeyRegex.find(pageHTML)?.groupValues?.getOrNull(1)
                ?: throw MoodleServiceError.SesskeyNotFound

            // Step 3: Call Moodle calendar API
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfToday = cal.timeInMillis / 1000

            val apiUrl = moodleApiTemplate.format(sesskey)
            val payload = gson.toJson(listOf(MoodleCalendarRequest.upcoming(startOfToday)))
            val requestBody = payload.toRequestBody("application/json".toMediaType())

            val apiRequest = Request.Builder().url(apiUrl).post(requestBody).build()
            val responseBody = client.newCall(apiRequest).execute().use { response ->
                response.body?.string() ?: throw MoodleServiceError.InvalidResponse
            }

            val wrapperType = object : TypeToken<List<MoodleCalendarWrapper>>() {}.type
            val wrappers: List<MoodleCalendarWrapper> = gson.fromJson(responseBody, wrapperType)
                ?: throw MoodleServiceError.InvalidResponse

            val first = wrappers.firstOrNull()
            if (first == null || first.error || first.data == null) {
                throw MoodleServiceError.InvalidResponse
            }

            // Step 4: Map events to Assignment
            first.data.events.mapNotNull { event ->
                if (event.modulename != "assign") return@mapNotNull null

                val courseNo = event.course?.idnumber
                    ?.takeIf { it.length > 4 }
                    ?.drop(4) ?: ""

                val courseName = event.course?.fullname?.let { fullname ->
                    val parts = fullname.split(" ")
                    val courseNoRegex = Regex("3?[A-Z]{2}[A-Z0-9]{6,7}")
                    val idx = parts.indexOfFirst { courseNoRegex.containsMatchIn(it) }
                    if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else fullname
                } ?: ""

                Assignment(
                    assignmentId = "${event.instance ?: event.id}",
                    courseNo = courseNo,
                    courseName = courseName,
                    title = event.activityname ?: event.name,
                    dueDate = Date(event.timestart * 1000),
                    isCompleted = false,
                    moodleUrl = event.url
                )
            }
        }
}
