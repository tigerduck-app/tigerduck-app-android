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

    private val isSyncCapable: Boolean
        get() = prefs.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)

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

    /**
     * Register the device with no account attached.
     *
     * Unauthenticated by design — there is no session yet. Deliberately does
     * not send an Authorization header even if one happens to exist: the
     * signed-in path is [register], and mixing the two would create a second,
     * unlinked row for a device that already has one.
     */
    suspend fun registerAnonymous(req: AnonymousDeviceRequest): Unit =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(req).toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/devices/anonymous")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException(
                        "anonymous register failed: HTTP ${response.code} ${response.body.string()}"
                    )
                }
            }
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

    suspend fun unregister(
        deviceId: String,
        authHeaderOverride: String? = null,
    ) = withContext(Dispatchers.IO) {
        // v3: DELETE /devices/{client_device_id}, scoped to the authed user
        // (matches iOS + the server). `deviceId` is PushIdentity.uuid().
        // On logout the caller passes a pre-captured header because the tokens
        // are wiped before this fire-and-forget call runs — without it the
        // DELETE goes out unauthenticated, 401s, and the device row leaks.
        val builder = Request.Builder()
            .url("$baseUrl/devices/$deviceId")
            .delete()
        val request = (
            if (authHeaderOverride != null) builder.header("Authorization", authHeaderOverride)
            else builder.addAuthHeader()
            ).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("unregister failed: HTTP ${response.code}")
            }
        }
    }

    /** PATCH the user-facing server-push opt-out for this device. */
    /**
     * Upload one holiday exception so the user's other devices agree.
     *
     * A no-op when cloud sync is off or on fdroid: the local preference has
     * already been written by then, and this call is only about agreement
     * between devices, not about whether the guard works.
     */
    suspend fun putHolidayOverride(holidayId: Int, notify: Boolean): Unit =
        withContext(Dispatchers.IO) {
            if (!isSyncCapable) return@withContext
            val body = gson.toJson(HolidayOverrideRequest(notify)).toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/sync/holiday-overrides/$holidayId")
                .put(body)
                .addAuthHeader()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException(
                        "holiday override failed: HTTP ${response.code}"
                    )
                }
            }
        }

    suspend fun updateDevicePreferences(
        deviceId: String,
        serverPushEnabled: Boolean? = null,
        syncCourses: Boolean? = null,
        syncCourseColors: Boolean? = null,
        syncCourseNames: Boolean? = null,
        syncAssignments: Boolean? = null,
        cloudSyncEnabled: Boolean? = null,
    ): DevicePreferencesResponse = withContext(Dispatchers.IO) {
        val payload = UpdateDevicePreferencesRequest(
            serverPushEnabled = serverPushEnabled,
            syncCourses = syncCourses,
            syncCourseColors = syncCourseColors,
            syncCourseNames = syncCourseNames,
            syncAssignments = syncAssignments,
            cloudSyncEnabled = cloudSyncEnabled,
        )
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
        if (!isSyncCapable || !prefs.syncAssignments) return@withContext
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
        courseId: Any,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
    ) = withContext(Dispatchers.IO) {
        if (!isSyncCapable) return@withContext
        if (colorHex != null && !prefs.syncCourseColors) return@withContext
        if (customName != null && !prefs.syncCourseNames) return@withContext
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
        if (!isSyncCapable || !prefs.syncAssignments) return@withContext
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
        forceKeys: List<String> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncCourses) return@withContext
        val payload = CourseUploadPayload.build(courses, semester, forceKeys)
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

    suspend fun deleteCourse(courseKey: String) = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncCourses) return@withContext
        val request = Request.Builder()
            .url("$baseUrl/sync/courses/$courseKey")
            .delete()
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("deleteCourse failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Reset the user's courses on the backend.
     *
     * [semester] scopes the reset to one term. The backend leaves
     * `courses_reset_at` alone for a scoped reset, so other devices do not
     * wipe every term's local overlay — an unscoped reset still stamps it and
     * still means "wipe everything". Reset is offered per term because the
     * timetable is now per term; see `DELETE /sync/courses` in the backend's
     * `routes/sync/courses.py`.
     */
    suspend fun deleteAllCourses(semester: String? = null) = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncCourses) return@withContext
        val url = "$baseUrl/sync/courses".let {
            if (semester != null) "$it?semester=${java.net.URLEncoder.encode(semester, "UTF-8")}" else it
        }
        val request = Request.Builder()
            .url(url)
            .delete()
            .addAuthHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("deleteAllCourses failed: HTTP ${response.code}")
            }
        }
    }
}
