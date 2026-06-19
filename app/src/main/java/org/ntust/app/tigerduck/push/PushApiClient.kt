package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

class PushApiException(message: String) : Exception(message)

@Singleton
class PushApiClient @Inject constructor(
    baseClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val authTokenManager: AuthTokenManager,
) {

    // Resolved per call so the debug API-endpoint override applies to push
    // immediately (no relaunch). Resolver name is historical — the override
    // governs both Announcement and Push API base URLs now. Release builds
    // never write the override, so the resolver collapses to the build's
    // default endpoint there.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
            chain.proceed(builder.build())
        }
        .build()

    /** Adds a Bearer Authorization header if a v3 token is available. */
    private suspend fun Request.Builder.addAuthHeader(): Request.Builder {
        val authHeader = authTokenManager.authHeader()
        return if (authHeader != null) header("Authorization", authHeader) else this
    }

    suspend fun register(req: DeviceRegisterRequest): DeviceRegisterResponse =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(req).toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/devices/register")
                .post(body)
                .addAuthHeader()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw PushApiException("register failed: HTTP ${response.code} $text")
                }
                // Gson throws JsonSyntaxException on "" instead of returning
                // null, so the null branch alone wouldn't surface our message.
                if (text.isBlank()) throw PushApiException("register: empty body")
                gson.fromJson(text, DeviceRegisterResponse::class.java)
                    ?: throw PushApiException("register: empty body")
            }
        }

    suspend fun unregister(deviceId: String) = withContext(Dispatchers.IO) {
        // v3: DELETE /devices/{client_device_id}, scoped to the authed user
        // (matches iOS + the server). `deviceId` is PushIdentity.uuid().
        val request = Request.Builder()
            .url("$baseUrl/devices/$deviceId")
            .delete()
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("unregister failed: HTTP ${response.code}")
            }
        }
    }

    /** PATCH the user-facing server-push opt-out for this device. */
    suspend fun updateDevicePreferences(
        deviceId: String,
        serverPushEnabled: Boolean,
    ): DevicePreferencesResponse = withContext(Dispatchers.IO) {
        val payload = UpdateDevicePreferencesRequest(serverPushEnabled = serverPushEnabled)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/devices/$deviceId/preferences")
            .patch(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw PushApiException("updateDevicePreferences failed: HTTP ${response.code} $text")
            }
            if (text.isBlank()) throw PushApiException("updateDevicePreferences: empty body")
            gson.fromJson(text, DevicePreferencesResponse::class.java)
                ?: throw PushApiException("updateDevicePreferences: empty body")
        }
    }

    suspend fun updateCredentials(
        moodleToken: String,
        moodlePrivateToken: String? = null,
    ) = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "moodle_token" to moodleToken,
            "moodle_private_token" to moodlePrivateToken,
        )
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/auth/credentials")
            .patch(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("updateCredentials failed: HTTP ${response.code}")
            }
        }
    }

    suspend fun patchAssignmentOverride(
        assignmentId: Int,
        localStatus: String,
    ) = withContext(Dispatchers.IO) {
        val payload = mapOf("local_status" to localStatus)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/sync/assignments/$assignmentId/override")
            .patch(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchAssignmentOverride failed: HTTP ${response.code}")
            }
        }
    }

    suspend fun patchCourseOverride(
        courseId: Int,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
    ) = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any?>()
        if (colorHex != null) payload["color_hex"] = colorHex
        if (customName != null) payload["custom_name"] = customName
        if (locale != null) payload["locale"] = locale
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/sync/courses/$courseId/override")
            .patch(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchCourseOverride failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Fire-and-forget upload of the user's assignment list so the backend can
     * persist it for cross-device sync. Callers wrap this in `runCatching`
     * — a failure here must never block the normal fetch/save flow.
     */
    suspend fun uploadAssignments(
        assignments: List<Assignment>,
    ) = withContext(Dispatchers.IO) {
        val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val items = assignments.map { a ->
            mapOf(
                "moodle_assignment_id" to (a.assignmentId.toIntOrNull() ?: 0),
                "course_no" to a.courseNo,
                "course_name" to a.courseName,
                "title" to a.title,
                "due_at" to iso8601.format(a.dueDate),
                "moodle_url" to a.moodleUrl,
                "is_submitted" to a.isCompleted,
                "grade" to null,
            )
        }
        val payload = mapOf("assignments" to items)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/sync/assignments/upload")
            .post(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("uploadAssignments failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Fire-and-forget upload of the user's enrolled course list so the backend
     * can persist it for cross-device sync. Callers wrap this in `runCatching`
     * — a failure here must never block the normal fetch/save flow.
     */
    suspend fun uploadCourses(
        courses: List<Course>,
        semester: String,
    ) = withContext(Dispatchers.IO) {
        val items = courses.map { c ->
            mapOf(
                "semester" to semester,
                "course_no" to c.courseNo,
                "course_name" to c.displayName,
                "course_name_en" to null,
                "moodle_id" to c.moodleIdNumber,
                "credits" to c.credits.toDouble(),
                "classroom" to c.classroom,
                "instructors" to c.instructor
                    .split(",", "，", "、")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }
        val payload = mapOf("courses" to items)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/sync/courses/upload")
            .post(body)
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("uploadCourses failed: HTTP ${response.code}")
            }
        }
    }
}
