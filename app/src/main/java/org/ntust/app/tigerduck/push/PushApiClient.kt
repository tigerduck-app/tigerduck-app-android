package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
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
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncPolicies
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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

    // Whether a write goes, as the switches stand at each read; the re-enable hold is the callers'.
    private val policies = SyncPolicies(prefs)

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

    // The sync writes, each in a file of its own beside this one.
    private val overrides = OverrideRequests(api, policies, { baseUrl }, ::authHeader)
    private val courseSync = CourseSyncRequests(api, uploads, prefs, policies, { baseUrl }, ::authHeader)

    /**
     * The Bearer Authorization header of a request of [signIn]'s, signing in
     * first when there is no session. Without one, or once [signIn] has ended,
     * it throws [NotSignedInException] and the request is not sent.
     */
    private suspend fun authHeader(signIn: SignIn): String = authTokenManager.requireAuthHeader(signIn)

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

    suspend fun register(req: DeviceRegisterRequest, signIn: SignIn): DeviceRegisterResponse =
        withContext(Dispatchers.IO) {
            val service = api.at(baseUrl)
            service.registeredDevice(authHeader(signIn), req).execute().use { response ->
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
     * setting may name another host by now. Throws [java.io.IOException] when no reply came back,
     * and [UnregisterHeldBack] when [mayDepart], asked right before the request goes, says no.
     */
    suspend fun deleteDevice(
        base: String,
        deviceId: String,
        accessToken: String,
        mayDepart: () -> Boolean = { true },
    ): Int = withContext(Dispatchers.IO) {
        if (!mayDepart()) throw UnregisterHeldBack()
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

    /** One holiday's choice, with the backend's answer; see [OverrideRequests.putHoliday]. */
    suspend fun putHolidayOverrideAnswered(holidayId: Int, notify: Boolean, signIn: SignIn, madeAtMs: Long?): WriteAnswer =
        overrides.putHoliday(holidayId, notify, signIn, madeAtMs)

    /** [putHolidayOverrideAnswered], saying whether it went; a refusal throws with no status, as it always has. */
    suspend fun putHolidayOverride(holidayId: Int, notify: Boolean, signIn: SignIn, madeAtMs: Long? = null): Boolean =
        refusedAsBefore(keepsStatus = false) { putHolidayOverrideAnswered(holidayId, notify, signIn, madeAtMs) }.sent

    suspend fun updateDevicePreferences(
        deviceId: String,
        signIn: SignIn,
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
        service.devicePreferences(authHeader(signIn), deviceId, payload).execute().use { response ->
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
        signIn: SignIn,
        moodlePrivateToken: String? = null,
    ) = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "moodle_token" to moodleToken,
            "moodle_private_token" to moodlePrivateToken.takeUnless { resolveAnnouncementEndpoint(prefs).overrideApplied },
        )
        val service = api.at(baseUrl)
        service.credentials(authHeader(signIn), payload).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("updateCredentials failed: HTTP ${response.code}")
            }
        }
    }

    /** One assignment's mark, with the backend's answer; see [OverrideRequests.patchMark]. */
    suspend fun patchAssignmentOverrideAnswered(assignmentId: Int, localStatus: String, signIn: SignIn, madeAtMs: Long?): WriteAnswer =
        overrides.patchMark(assignmentId, localStatus, signIn, madeAtMs)

    /** [patchAssignmentOverrideAnswered], saying whether it went. */
    suspend fun patchAssignmentOverride(assignmentId: Int, localStatus: String, signIn: SignIn, madeAtMs: Long? = null): Boolean =
        refusedAsBefore { patchAssignmentOverrideAnswered(assignmentId, localStatus, signIn, madeAtMs) }.sent

    /** One course's color, or its name in [locale], with the backend's answer; see [OverrideRequests.patchCourse]. */
    suspend fun patchCourseOverrideAnswered(
        target: CourseRef, signIn: SignIn, colorHex: String? = null, customName: String? = null, locale: String? = null,
        allowed: () -> Boolean = { true }, madeAtMs: Long? = null,
    ): WriteAnswer = overrides.patchCourse(target, signIn, colorHex, customName, locale, allowed, madeAtMs)

    /** [patchCourseOverrideAnswered] of the course whose Moodle id is [courseId], saying whether it went. */
    suspend fun patchCourseOverride(
        courseId: Any, signIn: SignIn, colorHex: String? = null, customName: String? = null, locale: String? = null,
        allowed: () -> Boolean = { true }, madeAtMs: Long? = null,
    ): Boolean = refusedAsBefore {
        patchCourseOverrideAnswered(CourseRef(courseId.toString(), null), signIn, colorHex, customName, locale, allowed, madeAtMs)
    }.sent

    /** Whether a course's assignments are ignored, with the backend's answer; see [OverrideRequests.patchIgnore]. */
    suspend fun patchCourseIgnoreAnswered(target: CourseRef, ignore: Boolean?, moodleCourseId: Int?, signIn: SignIn, madeAtMs: Long?): WriteAnswer =
        overrides.patchIgnore(target, ignore, moodleCourseId, signIn, madeAtMs)

    /**
     * [patchCourseIgnoreAnswered] of the course whose Moodle id is [moodleId]: null when nothing
     * went, else whether the backend stored it, which one that predates the field does not.
     */
    suspend fun patchCourseIgnore(moodleId: String, ignore: Boolean?, moodleCourseId: Int?, signIn: SignIn, madeAtMs: Long? = null): Boolean? =
        refusedAsBefore { patchCourseIgnoreAnswered(CourseRef(moodleId, null), ignore, moodleCourseId, signIn, madeAtMs) }
            .takeIf { it.sent }?.let { "ignore_assignments" in it.values }

    private val assignmentUploads = UploadLedger(
        store = object : UploadLedgerStore {
            override val fingerprint: String? get() = prefs.school.assignmentsUploadFingerprint.get()
            override val acceptedAtMs: Long get() = prefs.school.assignmentsUploadedAtMs.get()
        },
        clock = System::currentTimeMillis,
    )

    /**
     * Fire-and-forget upload of the user's assignment list so the backend can
     * persist it for cross-device sync. Callers wrap this in `runCatching`
     * — a failure here must never block the normal fetch/save flow.
     *
     * A list the backend already accepted goes again only once it changes,
     * or six hours on ([UploadLedger]); each upload defers the server's own
     * Moodle sync by two hours. Pinned to [signIn], the sign-in the list was made under.
     */
    suspend fun uploadAssignments(
        assignments: List<Assignment>,
        signIn: SignIn,
    ) = withContext(Dispatchers.IO) {
        if (!policies.current.syncs(SyncCategory.ASSIGNMENTS)) return@withContext
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
            // The pair in one step, so a sign-out's clear cannot fall between the check and the
            // record, or between the two; see UploadLedger.sendIfDue.
            record = { fingerprint, atMs ->
                signIn.ifCurrent {
                    prefs.school.assignmentsUploadFingerprint.set(fingerprint, signIn)
                    prefs.school.assignmentsUploadedAtMs.set(atMs, signIn)
                }
            },
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

    /** [uploadCoursesAnswered]'s course numbers settled: see [CourseSyncRequests.uploadCoursesAnswered]. */
    suspend fun uploadCourses(
        courses: List<Course>, semester: String, signIn: SignIn, forceKeys: List<String> = emptyList(), resend: Boolean = false,
        rosterTerms: List<String> = emptyList(), addedAt: Map<String, Long> = emptyMap(),
    ): List<String>? = uploadCoursesAnswered(courses, semester, signIn, forceKeys, resend, rosterTerms, addedAt)?.settled

    /** See [CourseSyncRequests.uploadCoursesAnswered]. */
    suspend fun uploadCoursesAnswered(
        courses: List<Course>, semester: String, signIn: SignIn, forceKeys: List<String> = emptyList(), resend: Boolean = false,
        rosterTerms: List<String> = emptyList(), addedAt: Map<String, Long> = emptyMap(),
    ): CourseUploadAnswer? = courseSync.uploadCoursesAnswered(courses, semester, signIn, forceKeys, resend, rosterTerms, addedAt)

    /** See [CourseSyncRequests.serverDropped]. */
    suspend fun serverDropped(semester: String, courseNos: Collection<String>) = courseSync.serverDropped(semester, courseNos)

    /** A course's delete, with the backend's answer; see [CourseSyncRequests.deleteCourse]. */
    suspend fun deleteCourseAnswered(courseKey: String, signIn: SignIn, hiddenAt: Long?, kind: String?): WriteAnswer =
        courseSync.deleteCourse(courseKey, signIn, hiddenAt, kind)

    /** [deleteCourseAnswered], with no kind. */
    suspend fun deleteCourse(courseKey: String, signIn: SignIn, hiddenAt: Long? = null) {
        refusedAsBefore { deleteCourseAnswered(courseKey, signIn, hiddenAt, kind = null) }
    }

    /** A term's reset, or every term's, with the backend's answer; see [CourseSyncRequests.deleteAllCourses]. */
    suspend fun deleteAllCoursesAnswered(semester: String?, signIn: SignIn, resetAtMs: Long?): WriteAnswer =
        courseSync.deleteAllCourses(semester, signIn, resetAtMs)

    /** [deleteAllCoursesAnswered]; a refusal throws with no status, as it always has. */
    suspend fun deleteAllCourses(semester: String?, signIn: SignIn) {
        refusedAsBefore(keepsStatus = false) { deleteAllCoursesAnswered(semester, signIn, resetAtMs = null) }
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

/** A sign-out's device unregister not sent, as its caller said right before it would go. */
class UnregisterHeldBack : java.io.IOException("unregister held back")
