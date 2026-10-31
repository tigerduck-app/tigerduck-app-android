package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import okhttp3.RequestBody.Companion.toRequestBody
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.EmptyBody
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.auth.guardedBy
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** [statusCode] is the HTTP status, where the caller tells refusals from outages by it. */
class PushApiException(message: String, val statusCode: Int? = null) : Exception(message)

@Singleton
class PushApiClient @Inject constructor(
    baseClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val authTokenManager: AuthTokenManager,
) {

    // prefs.cloudSyncEnabled already reads false on fdroid at its source
    // (AppPreferences.cloudSyncEnabled -> effectiveCloudSyncEnabled), so
    // ANDing a second flavor check here would only ever restate it.
    private val isSyncCapable: Boolean
        get() = prefs.cloudSyncEnabled

    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    // Without the session's guard: the sign-out's unregister names its backend
    // and token itself, from the debt.
    private val unguarded = baseClient.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
            chain.proceed(builder.build())
        }
        .build()

    private val client = unguarded.newBuilder()
        .guardedBy(authTokenManager)
        .build()

    // The endpoints, each sent through the client its call went through before Retrofit.
    private val api = BackendServices(PushApi::class.java, client)
    private val deletes by lazy { BackendServices(PushApi::class.java, deleteClient) }
    private val uploads = BackendServices(PushApi::class.java) { request -> uploadCall(request) }

    /**
     * The Bearer Authorization header, signing in first when there is no
     * session. Without one it throws [NotSignedInException] and the request
     * is not sent.
     */
    private suspend fun authHeader(signIn: Int? = null): String =
        if (signIn == null) authTokenManager.requireAuthHeader() else authTokenManager.requireAuthHeader(signIn)

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
            api.at(baseUrl).anonymousDevice(req).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException(
                        "anonymous register failed: HTTP ${response.code} ${response.body.string()}"
                    )
                }
            }
        }

    suspend fun register(req: DeviceRegisterRequest): DeviceRegisterResponse =
        withContext(Dispatchers.IO) {
            val service = api.at(baseUrl)
            service.registeredDevice(authHeader(), req).execute().use { response ->
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

    /**
     * Deletes this device's row as the account that signed out, with [accessToken] from the
     * session it left, at [base], the backend that issued it, and gives the HTTP status. The
     * sign-out's unregister runs after the tokens are wiped, and a session looked up now could be
     * the next account's, or its sign-in, whose row the DELETE would remove; and the endpoint
     * setting may name another host by now. Throws [java.io.IOException] when no reply came back.
     */
    suspend fun deleteDevice(
        base: String,
        deviceId: String,
        accessToken: String,
    ): Int = withContext(Dispatchers.IO) {
        // v3: DELETE /devices/{client_device_id}, scoped to the authed user
        // (matches iOS + the server). `deviceId` is PushIdentity.uuid().
        deletes.at(base).removeDevice("Bearer $accessToken", deviceId, EmptyBody).execute().use { response -> response.code }
    }

    // A sign-in of the same student waits for the sign-out's unregister, so its
    // DELETE gives up within a bound; and it is retried by its own debt, never
    // sent again by OkHttp on its own.
    internal val deleteClient: OkHttpClient by lazy {
        unguarded.newBuilder()
            .retryOnConnectionFailure(false)
            .callTimeout(DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** PATCH the user-facing server-push opt-out for this device. */
    /**
     * Upload one holiday exception so the user's other devices agree.
     *
     * A no-op when cloud sync is off or on fdroid: the local preference has
     * already been written by then, and this call is only about agreement
     * between devices, not about whether the guard works. With [signIn], sent
     * only as that sign-in: see `PendingHolidayOverrides`. Says whether it was
     * sent, so a skipped PUT is not taken for one that landed.
     */
    suspend fun putHolidayOverride(holidayId: Int, notify: Boolean, signIn: Int? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (!isSyncCapable) return@withContext false
            val service = api.at(baseUrl)
            service.holidayOverride(authHeader(signIn), holidayId, HolidayOverrideRequest(notify)).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException(
                        "holiday override failed: HTTP ${response.code}"
                    )
                }
                true
            }
        }

    suspend fun updateDevicePreferences(
        deviceId: String,
        serverPushEnabled: Boolean? = null,
        syncCourses: Boolean? = null,
        syncCourseColors: Boolean? = null,
        syncCourseNames: Boolean? = null,
        syncAssignments: Boolean? = null,
        syncAssignmentReminders: Boolean? = null,
        syncLiveActivity: Boolean? = null,
        cloudSyncEnabled: Boolean? = null,
        locale: String? = null,
    ): DevicePreferencesResponse = withContext(Dispatchers.IO) {
        val payload = UpdateDevicePreferencesRequest(
            serverPushEnabled = serverPushEnabled,
            syncCourses = syncCourses,
            syncCourseColors = syncCourseColors,
            syncCourseNames = syncCourseNames,
            syncAssignments = syncAssignments,
            syncAssignmentReminders = syncAssignmentReminders,
            syncLiveActivity = syncLiveActivity,
            cloudSyncEnabled = cloudSyncEnabled,
            locale = locale,
        )
        // Guards updateCloudSyncEnabled and updateSyncPreferences, which
        // have no fdroid check of their own — see preferencesPatchBlockedOnFdroid.
        // Never trips for the locale-only call (syncLocalePreference) or for
        // updateServerPushOptOut's PATCH, which already refuses itself
        // before ever reaching here.
        if (preferencesPatchBlockedOnFdroid(payload)) {
            throw PushApiException("updateDevicePreferences refused: fdroid cannot change sync or push preferences")
        }
        val service = api.at(baseUrl)
        service.devicePreferences(authHeader(), deviceId, payload).execute().use { response ->
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
        val service = api.at(baseUrl)
        service.credentials(authHeader(), payload).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("updateCredentials failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * [signIn], when given, pins the request to that sign-in
     * ([AuthTokenManager.signInGeneration]): after a sign-out it goes without
     * a token rather than with the next account's.
     */
    suspend fun patchAssignmentOverride(
        assignmentId: Int,
        localStatus: String,
        signIn: Int? = null,
    ) = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncAssignments) return@withContext
        val payload = mapOf("local_status" to localStatus)
        val service = api.at(baseUrl)
        service.assignmentOverride(authHeader(signIn), assignmentId, payload).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchAssignmentOverride failed: HTTP ${response.code}", response.code)
            }
        }
    }

    /**
     * PATCH the colour of the course [courseId], or its name in [locale].
     * Returns whether anything went out: false when TigerSync, or the switch
     * for colours or names, is off, or when [allowed] says no right before
     * the request goes, after the header is made. Throws when the request
     * fails. [signIn], when given, pins the request to that sign-in, as
     * [patchAssignmentOverride]'s is.
     */
    suspend fun patchCourseOverride(
        courseId: Any,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
        signIn: Int? = null,
        allowed: () -> Boolean = { true },
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isSyncCapable) return@withContext false
        if (colorHex != null && !prefs.syncCourseColors) return@withContext false
        if (customName != null && !prefs.syncCourseNames) return@withContext false
        val payload = mutableMapOf<String, Any?>()
        if (colorHex != null) payload["color_hex"] = colorHex
        if (customName != null) payload["custom_name"] = customName
        if (locale != null) payload["locale"] = locale
        val service = api.at(baseUrl)
        val call = service.courseOverride(authHeader(signIn), courseId.toString(), payload)
        // Read once the header is made, which can renew the session and so
        // suspend: a hold the course outbox put on the kind meanwhile must
        // stop the request, which the switches above alone would let through.
        if (!allowed()) return@withContext false
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchCourseOverride failed: HTTP ${response.code}", response.code)
            }
        }
        true
    }

    /**
     * PATCH whether every assignment of the course [moodleId] is ignored,
     * through the same override as its colour and name. [moodleCourseId] is
     * the course's numeric Moodle id, which the backend's reminder scan
     * matches the assignments it fetches itself by. Either may be null.
     *
     * Returns whether the backend stored it: false when the answer has no
     * `ignore_assignments`, as from a backend that predates the field and
     * drops it unread, and null when course or assignment sync is off and
     * nothing went out. Throws when the request fails.
     */
    suspend fun patchCourseIgnore(
        moodleId: String,
        ignore: Boolean?,
        moodleCourseId: Int?,
    ): Boolean? = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncAssignments || !prefs.syncCourses) return@withContext null
        val payload = mutableMapOf<String, Any>()
        if (ignore != null) payload["ignore_assignments"] = ignore
        if (moodleCourseId != null && moodleCourseId >= 1) payload["moodle_course_id"] = moodleCourseId
        if (payload.isEmpty()) return@withContext null
        val service = api.at(baseUrl)
        service.courseIgnore(authHeader(), moodleId, payload).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchCourseIgnore failed: HTTP ${response.code}", response.code)
            }
            runCatching { JSONObject(response.body.string()).has("ignore_assignments") }
                .getOrDefault(false)
        }
    }

    private val assignmentUploads = UploadLedger(
        store = object : UploadLedgerStore {
            override val fingerprint: String? get() = prefs.assignmentsUploadFingerprint
            override val acceptedAtMs: Long get() = prefs.assignmentsUploadedAtMs
            override fun record(fingerprint: String, atMs: Long) {
                prefs.assignmentsUploadFingerprint = fingerprint
                prefs.assignmentsUploadedAtMs = atMs
            }
        },
        clock = System::currentTimeMillis,
    )

    // One ledger per term, over the term's own slot in AppPreferences; see
    // AppPreferences.coursesUploadFingerprint for why not one slot for all.
    private val courseUploads = ConcurrentHashMap<String, UploadLedger>()

    private fun courseUploads(semester: String): UploadLedger = courseUploads.getOrPut(semester) {
        UploadLedger(
            store = object : UploadLedgerStore {
                override val fingerprint: String? get() = prefs.coursesUploadFingerprint(semester)
                override val acceptedAtMs: Long get() = prefs.coursesUploadedAtMs(semester)
                override fun record(fingerprint: String, atMs: Long) = prefs.recordCoursesUpload(semester, fingerprint, atMs)
            },
            clock = System::currentTimeMillis,
        )
    }

    /**
     * Fire-and-forget upload of the user's assignment list so the backend can
     * persist it for cross-device sync. Callers wrap this in `runCatching`
     * — a failure here must never block the normal fetch/save flow.
     *
     * A list the backend already accepted goes again only once it changes,
     * or six hours on ([UploadLedger]); each upload defers the server's own
     * Moodle sync by two hours. Pinned to the sign-in it starts under.
     */
    suspend fun uploadAssignments(
        assignments: List<Assignment>,
    ) = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncAssignments) return@withContext
        val signIn = authTokenManager.signInGeneration
        val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val items = assignments.sortedBy { it.assignmentId.toIntOrNull() ?: 0 }.map { a ->
            mapOf(
                "moodle_assignment_id" to (a.assignmentId.toIntOrNull() ?: 0),
                "course_no" to a.courseNo,
                "course_name" to a.courseName,
                "title" to a.title,
                // The date the student's work is due, a teacher's extension included: what iOS
                // uploads, and what the backend's own Moodle fetch writes (tigerduck-backend#82).
                "due_at" to iso8601.format(a.studentDueDate),
                "moodle_url" to a.moodleUrl,
                "is_submitted" to a.isCompleted,
                "grade" to null,
            )
        }
        val json = gson.toJson(mapOf("assignments" to items))
        // Resolved once, for the fingerprint and the request alike, so the
        // ledger remembers what this backend accepted; see uploadFingerprint.
        val base = baseUrl
        assignmentUploads.sendIfDue(
            fingerprint = uploadFingerprint(base, json),
            // Recorded under the sign-in lock, so a sign-out's clear cannot
            // fall between the check and the record; see UploadLedger.sendIfDue.
            ifCurrent = { record -> authTokenManager.runIfSignedIn(signIn, record) },
            signIn = signIn,
        ) {
            val service = api.at(base)
            service.assignmentsUpload(authHeader(signIn), json.toRequestBody(jsonType)).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException("uploadAssignments failed: HTTP ${response.code}", response.code)
                }
            }
        }
    }

    /**
     * The course upload's call, with a deadline for the whole of it. PendingCourseAdds holds its
     * send lock through the upload, and a course delete or term reset waits on that lock: the
     * client's per-read timeouts alone would let an answer trickled in byte by byte hold it.
     */
    internal fun uploadCall(request: Request): Call =
        client.newCall(request).apply { timeout().timeout(UPLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

    companion object {
        /** See [uploadCall]: longer than any upload of a term's courses takes. */
        const val UPLOAD_CALL_TIMEOUT_SECONDS = 60L
    }

    /**
     * Upload the user's course list so the backend can persist it for
     * cross-device sync. Callers wrap this in `runCatching` — a failure here
     * must never block the normal fetch/save flow.
     *
     * A term's list the backend already accepted goes again only once it
     * changes, or six hours on ([UploadLedger]), as assignments do; one with
     * [forceKeys] goes whatever the ledger holds, and so does one sent with
     * [resend], for a term the server has emptied since. Each upload defers
     * the server's own Moodle sync by two hours.
     *
     * Returns the course numbers the server actually accepted, or null when
     * nothing was sent (sync off). A 200 is not acceptance: the server skips
     * any course another device has tombstoned and says so in the body, and
     * a caller that took the status for "the server has seen these" would
     * retire a hand-typed course's protection on an upload that was refused.
     * A list skipped as unchanged answers with none: the server accepted
     * them lately, and said so then.
     *
     * [signIn], when given, pins the request to that sign-in, as
     * [patchAssignmentOverride]'s is.
     *
     * [rosterTerms] names the terms the list is the school's whole roster
     * of; see [CourseUpload.of], which decides it. A roster the backend asks
     * for (`roster_requests`) goes with [resend], past the ledger.
     */
    suspend fun uploadCourses(
        courses: List<Course>,
        semester: String,
        forceKeys: List<String> = emptyList(),
        signIn: Int? = null,
        resend: Boolean = false,
        rosterTerms: List<String> = emptyList(),
    ): List<String>? = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncCourses) return@withContext null
        val payload = CourseUploadPayload.build(courses, semester, forceKeys, rosterTerms)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        // Resolved once, for the fingerprint and the request alike, so the
        // ledger remembers what this backend accepted; see uploadFingerprint.
        val base = baseUrl
        // The sign-in the ledger's record is pinned to, as the assignment
        // upload's is; the request's own header is pinned only when the
        // caller pinned it ([signIn]), as before.
        val startedIn = signIn ?: authTokenManager.signInGeneration
        // What the accepted list enrolled as the term's roster, for serverDropped.
        val roster = if (semester in rosterTerms) courses.map { it.courseNo } else null
        var accepted: List<String> = emptyList()
        courseUploads(semester).sendIfDue(
            fingerprint = courseUploadFingerprint(base, courses, semester, rosterTerms),
            // Recorded under the sign-in lock, so a sign-out's clear cannot
            // fall between the check and the record; see UploadLedger.sendIfDue.
            ifCurrent = { record ->
                authTokenManager.runIfSignedIn(startedIn) {
                    record()
                    prefs.recordCoursesUploadRoster(semester, roster)
                }
            },
            always = resend || forceKeys.isNotEmpty(),
            signIn = startedIn,
        ) {
            // Through uploadCall, with its deadline for the whole call.
            val service = uploads.at(base)
            accepted = service.coursesUpload(authHeader(signIn), body).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException("uploadCourses failed: HTTP ${response.code}", response.code)
                }
                // A 2xx with an unreadable body (a captive portal, a proxy) is
                // still an upload that landed; it just cannot say what was accepted.
                val json = runCatching { JSONObject(response.body.string()) }.getOrNull()
                val keys = json?.optJSONArray("accepted_keys") ?: return@use emptyList()
                // "client:{semester}:{course_no}" — the shape CourseUploadPayload sends.
                (0 until keys.length()).map { keys.getString(it).substringAfterLast(':') }
            }
        }
        accepted
    }

    /**
     * A full sync found [courseNos] of [semester] dropped on the server, by another device's
     * roster. When the term's list the backend last accepted from here went as its roster and
     * listed one of them, the server no longer holds what it accepted: the ledger forgets it, and
     * the next roster goes, unchanged or not. Otherwise a course selection answer listing the
     * course again waited six hours, the course kept here and dropped on the server, without its
     * reminders. Only a roster's: an upload without one leaves a dropped row dropped, and
     * forgetting it would send it at every fetch to no effect.
     */
    suspend fun serverDropped(semester: String, courseNos: Collection<String>) {
        fun listsOne() = prefs.coursesUploadRoster(semester)?.any { it in courseNos } == true
        if (!listsOne()) return
        // Under the ledger: an upload out now would record its list after the forget.
        courseUploads(semester).holdingUploads { if (listsOne()) prefs.forgetCourseUploads(semester) }
    }

    /**
     * Deletes the course row [courseKey] names. [signIn] pins the request to
     * that sign-in ([AuthTokenManager.signInGeneration]), as [uploadCourses]
     * does: a delete queued as one account and sent after a sign-out and
     * another sign-in goes without a token, not with the next account's,
     * which could take that account's own course off.
     */
    suspend fun deleteCourse(courseKey: String, signIn: Int? = null) = withContext(Dispatchers.IO) {
        if (!isSyncCapable || !prefs.syncCourses) return@withContext
        val service = api.at(baseUrl)
        service.removeCourse(authHeader(signIn), courseKey, EmptyBody).execute().use { response ->
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
        val service = api.at(baseUrl)
        val call = service.removeCourses(
            authHeader(),
            semester?.let { java.net.URLEncoder.encode(it, "UTF-8") },
            EmptyBody,
        )
        val reset: suspend () -> Unit = {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException("deleteAllCourses failed: HTTP ${response.code}")
                }
            }
            // The upload after the reset, usually the list sent before it,
            // releases the reset's tombstones: it has to go.
            prefs.forgetCourseUploads(semester)
        }
        // Under the term's upload ledger, so no upload is out across the
        // reset; see UploadLedger.holdingUploads. Nothing resets every term
        // today, and that path holds no ledger.
        if (semester != null) courseUploads(semester).holdingUploads(reset) else reset()
    }
}

/**
 * Whether a `PATCH .../preferences` [request] must be refused before it ever
 * reaches the network, because [flavor] can never honor a sync- or
 * push-preference change regardless of what the caller believes.
 *
 * `locale` is deliberately excluded from the fields checked below:
 * [PushRegistrationService.syncLocalePreference] calls
 * [PushApiClient.updateDevicePreferences] with only that field set, on every
 * flavor including fdroid, and must keep working — a locale-only request
 * always reads as not blocked, regardless of [flavor].
 *
 * [flavor] defaults to [BuildConfig.FLAVOR] for the one production call
 * site, [PushApiClient.updateDevicePreferences]. Tests pass it explicitly,
 * for the same reason
 * [org.ntust.app.tigerduck.data.preferences.effectiveCloudSyncEnabled] does:
 * [PushApiClient] can't be constructed on the plain JVM this module's tests
 * run on (real OkHttp / [org.ntust.app.tigerduck.data.preferences.AppPreferences]
 * dependencies, and this module has neither Robolectric nor a mocking
 * library), so this pure function is what a unit test exercises.
 */
internal fun preferencesPatchBlockedOnFdroid(
    request: UpdateDevicePreferencesRequest,
    flavor: String = BuildConfig.FLAVOR,
): Boolean {
    val changesAPreference = request.serverPushEnabled != null ||
        request.cloudSyncEnabled != null ||
        request.syncCourses != null ||
        request.syncCourseColors != null ||
        request.syncCourseNames != null ||
        request.syncAssignments != null ||
        request.syncAssignmentReminders != null ||
        request.syncLiveActivity != null
    return changesAPreference && flavor.equals("fdroid", ignoreCase = true)
}

private const val DELETE_TIMEOUT_SECONDS = 20L
