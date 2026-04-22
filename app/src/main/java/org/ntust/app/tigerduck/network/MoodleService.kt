package org.ntust.app.tigerduck.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleCalendarRequest
import org.ntust.app.tigerduck.network.model.MoodleCalendarWrapper
import org.ntust.app.tigerduck.network.model.MoodleEnrolRequest
import org.ntust.app.tigerduck.network.model.MoodleEnrolWrapper
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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
    private val ssoLoginService: SsoLoginService,
    private val tokenService: MoodleTokenService,
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()
    private val webserviceUrl = "https://moodle2.ntust.edu.tw/webservice/rest/server.php"
    @Volatile private var cachedUserId: Int? = null

    private val moodleLoginUrl = "https://moodle2.ntust.edu.tw/login/index.php"
    private val moodleDashboardUrl = "https://moodle2.ntust.edu.tw/my/"
    private val moodleAjaxTemplate = "https://moodle2.ntust.edu.tw/lib/ajax/service.php?sesskey=%s&info=%s"

    private data class MoodleSession(val sesskey: String, val userId: Int)

    // Cache the scraped session so concurrent callers don't stampede
    // NetScaler with repeated dashboard GETs (which gets them throttled
    // with a `NS_CSM_*` challenge page). TTL is conservative; Moodle's
    // real sesskey is tied to the PHP session cookie, which typically
    // lives for the full browser session.
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var cachedSession: MoodleSession? = null
    @Volatile private var cachedSessionAt: Long = 0L
    private val sessionTtlMs = 5 * 60_000L

    private suspend fun getOrEstablishSession(studentId: String, password: String): MoodleSession {
        cachedSession?.let { cached ->
            if (System.currentTimeMillis() - cachedSessionAt < sessionTtlMs) return cached
        }
        return sessionMutex.withLock {
            // Re-check after acquiring the lock — another caller may have
            // populated the cache while we waited.
            cachedSession?.let { cached ->
                if (System.currentTimeMillis() - cachedSessionAt < sessionTtlMs) return@withLock cached
            }
            val fresh = establishSession(studentId, password)
            cachedSession = fresh
            cachedSessionAt = System.currentTimeMillis()
            fresh
        }
    }

    private suspend fun establishSession(studentId: String, password: String): MoodleSession {
        // Run the SSO chain against the dashboard URL directly so any OIDC
        // bridge forms specific to /my/ get resolved before we parse the
        // page — using the bare login URL sometimes leaves /my/ still
        // responding with a bridge form on the very first cold hit.
        val loggedIn = ssoLoginService.ensureServiceLogin(moodleDashboardUrl, studentId, password)
        if (!loggedIn) throw MoodleServiceError.NotAuthenticated

        // Up to 6 attempts with graduated back-off. The NTUST edge
        // (Citrix NetScaler) throws a JS-based anti-bot challenge page at
        // the first few dashboard hits after a cold SSO handshake; it
        // lets subsequent requests through once enough of them land in
        // quick succession. Running through multiple polls with
        // increasing gaps usually clears the challenge.
        var lastHtml = ""
        var lastCode = -1
        var lastHeaders = ""
        val maxAttempts = 6
        repeat(maxAttempts) { attempt ->
            val req = Request.Builder().url(moodleDashboardUrl).get().build()
            val html = client.newCall(req).execute().use { response ->
                lastCode = response.code
                lastHeaders = response.headers.names()
                    .filter { it.lowercase().let { n -> n == "set-cookie" || n.startsWith("ns-") || n == "content-type" || n == "location" } }
                    .joinToString(", ") { "$it=${response.headers.values(it).joinToString(";")}" }
                if (!response.isSuccessful) return@use null
                response.body?.string()
            } ?: run {
                Log.w("MoodleService", "dashboard fetch failed code=$lastCode headers=$lastHeaders (attempt=${attempt + 1})")
                if (attempt < maxAttempts - 1) kotlinx.coroutines.delay(800L * (attempt + 1))
                return@repeat
            }
            lastHtml = html
            val looksLikeNetScalerChallenge = html.contains("NS_CSM_", ignoreCase = false)

            val sesskey = extractSesskey(html)
            val userId = extractUserId(html)
            if (sesskey != null && userId != null) {
                Log.i(
                    "MoodleService",
                    "session established: userId=$userId sesskeyLen=${sesskey.length} (attempt=${attempt + 1})"
                )
                return MoodleSession(sesskey, userId)
            }

            Log.w(
                "MoodleService",
                "sesskey/userId missing (attempt=${attempt + 1}) netscaler=$looksLikeNetScalerChallenge bodyLen=${html.length} headers=$lastHeaders"
            )
            if (attempt < maxAttempts - 1) kotlinx.coroutines.delay(800L * (attempt + 1))
        }

        Log.w(
            "MoodleService",
            "sesskey/userId not found after $maxAttempts attempts; htmlSample=${lastHtml.take(800).replace("\n", " ")}"
        )
        throw MoodleServiceError.SesskeyNotFound
    }

    private fun extractSesskey(html: String): String? {
        val patterns = listOf(
            Regex("\"sesskey\":\"([^\"]+)\""),
            Regex("sesskey\\s*:\\s*[\"']([^\"']+)[\"']"),
            Regex("name=\"sesskey\"\\s+value=\"([^\"]+)\""),
            Regex("sesskey=([A-Za-z0-9]+)"),
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun extractUserId(html: String): Int? {
        val patterns = listOf(
            Regex("\"userId\":(\\d+)"),
            Regex("\"userid\":(\\d+)"),
            Regex("user\\s*id[\"']?\\s*[:=]\\s*[\"']?(\\d+)"),
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    /**
     * Fetch the user's enrolled Moodle courses across all semesters using
     * the long-lived Moodle Mobile wstoken. Matches iOS: calls the REST
     * webservice directly so we don't depend on the sesskey / `/my/` path,
     * which NTUST's edge (Citrix NetScaler) tends to challenge.
     */
    suspend fun fetchEnrolledCourses(studentId: String, password: String): List<MoodleEnrolledCourse> =
        withContext(Dispatchers.IO) {
            attemptWithTokenRetry { token ->
                val userId = getSiteInfoUserId(token)
                callEnrolledCourses(token, userId)
            }
        }

    /** Run [block] with current token; on `invalidtoken`, refresh once and retry. */
    private suspend inline fun <T> attemptWithTokenRetry(block: (String) -> T): T {
        val token = tokenService.currentToken() ?: tokenService.refreshToken()
        return try {
            block(token)
        } catch (e: MoodleWebserviceError.InvalidToken) {
            Log.w("MoodleService", "wstoken rejected, refreshing once")
            tokenService.clearToken()
            cachedUserId = null
            val fresh = tokenService.refreshToken()
            block(fresh)
        }
    }

    private fun getSiteInfoUserId(token: String): Int {
        cachedUserId?.let { return it }
        val url = "$webserviceUrl?moodlewsrestformat=json&wsfunction=core_webservice_get_site_info&wstoken=$token"
        val req = Request.Builder().url(url).post(FormBody.Builder().build()).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body?.string() ?: throw MoodleWebserviceError.MalformedResponse("site_info empty body")
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        val parsed = try {
            gson.fromJson(body, Map::class.java)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("site_info not JSON: ${e.message}")
        }
        val rawUserId = parsed?.get("userid")
            ?: throw MoodleWebserviceError.MalformedResponse("userid missing from site_info")
        val userId = (rawUserId as? Number)?.toInt()
            ?: throw MoodleWebserviceError.MalformedResponse("userid has unexpected type: $rawUserId")
        cachedUserId = userId
        Log.i("MoodleService", "site_info userId=$userId")
        return userId
    }

    private fun callEnrolledCourses(token: String, userId: Int): List<MoodleEnrolledCourse> {
        val url = "$webserviceUrl?moodlewsrestformat=json&wsfunction=core_enrol_get_users_courses&wstoken=$token"
        val form = FormBody.Builder().add("userid", userId.toString()).build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body?.string() ?: throw MoodleWebserviceError.MalformedResponse("enrolled empty body")
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        val type = object : TypeToken<List<MoodleEnrolledCourse>>() {}.type
        val courses: List<MoodleEnrolledCourse> = try {
            gson.fromJson(body, type)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("enrolled response not decodable: ${e.message}")
        } ?: emptyList()
        Log.i("MoodleService", "enrolled courses fetched: ${courses.size}")
        return courses
    }

    suspend fun fetchAssignments(studentId: String, password: String): List<Assignment> =
        withContext(Dispatchers.IO) {
            val (sesskey, _) = getOrEstablishSession(studentId, password)

            val cal = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfToday = cal.timeInMillis / 1000

            val apiUrl = moodleAjaxTemplate.format(sesskey, "core_calendar_get_action_events_by_timesort")
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
