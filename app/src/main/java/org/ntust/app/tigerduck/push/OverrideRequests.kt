package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncPolicies

/**
 * The four edits of one field each that go up one at a time: a holiday's choice, an assignment's
 * mark, a course's color or name, and whether a course's assignments are ignored. For
 * [PushApiClient], which keeps each as a method of its own under its own name: the callers that
 * must go through an outbox are found by those names. Each is sent with [authHeader]'s header for
 * its sign-in, to the endpoint [baseUrl] names at the time.
 */
internal class OverrideRequests(
    private val api: BackendServices<PushApi>,
    private val policies: SyncPolicies,
    private val baseUrl: () -> String,
    private val authHeader: suspend (SignIn) -> String,
) {
    /**
     * Upload one holiday exception so the user's other devices agree.
     *
     * A no-op when cloud sync is off or on fdroid: the local preference has
     * already been written by then, and this call is only about agreement
     * between devices, not about whether the guard works. Sent only as
     * [signIn]: see `PendingHolidayOverrides`. Says whether it was sent, so a
     * skipped PUT is not taken for one that landed. [madeAtMs] is sent as `hlc`.
     */
    suspend fun putHoliday(holidayId: Int, notify: Boolean, signIn: SignIn, madeAtMs: Long? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (!policies.current.tigerSyncOn) return@withContext false
            val service = api.at(baseUrl())
            val header = authHeader(signIn)
            executeStamped(madeAtMs, { policies.current.tigerSyncOn }, { hlc -> service.holidayOverride(header, holidayId, HolidayOverrideRequest(notify, hlc)) }) { response ->
                if (!response.isSuccessful) {
                    throw PushApiException(
                        "holiday override failed: HTTP ${response.code}"
                    )
                }
                true
            } ?: false
        }

    /**
     * [signIn] pins the request to that sign-in: after a sign-out it goes unsent
     * rather than with the next account's token. [madeAtMs] is sent as `hlc`.
     * Says whether it went out: false when the switch stopped it, so the mark waits.
     */
    suspend fun patchMark(
        assignmentId: Int,
        localStatus: String,
        signIn: SignIn,
        madeAtMs: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val syncs = { policies.current.syncs(SyncCategory.ASSIGNMENTS) }
        if (!syncs()) return@withContext false
        val payload = mapOf("local_status" to localStatus)
        val service = api.at(baseUrl())
        val header = authHeader(signIn)
        executeStamped(madeAtMs, syncs, { hlc -> service.assignmentOverride(header, assignmentId, payload.stampedWith(hlc)) }) { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchAssignmentOverride failed: HTTP ${response.code}", response.code)
            }
            true
        } ?: false
    }

    /**
     * PATCH the colour of the course [courseId], or its name in [locale].
     * Returns whether anything went out: false when TigerSync, or the switch
     * for colours or names, is off, or when [allowed] says no right before
     * the request goes, after the header is made. Throws when the request
     * fails. [signIn] pins the request to that sign-in, as
     * [patchMark]'s is. [madeAtMs] is sent as `hlc`.
     */
    suspend fun patchCourse(
        courseId: Any,
        signIn: SignIn,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
        allowed: () -> Boolean = { true },
        madeAtMs: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val switchesOn = { policies.current.syncsOverride(color = colorHex != null, name = customName != null) }
        if (!switchesOn()) return@withContext false
        val payload = listOfNotNull(colorHex?.let { "color_hex" to it }, customName?.let { "custom_name" to it }, locale?.let { "locale" to it })
            .toMap()
        val service = api.at(baseUrl())
        val header = authHeader(signIn)
        // Read once the header is made, which can renew the session and so
        // suspend: a hold the course outbox put on the kind meanwhile must
        // stop the request, which the switches above alone would let through.
        if (!allowed()) return@withContext false
        // The resend passes the same gate as the first request: the hold can go on meanwhile.
        val call = { hlc: Long? -> service.courseOverride(header, courseId.toString(), payload.stampedWith(hlc)) }
        executeStamped(madeAtMs, { switchesOn() && allowed() }, call) { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchCourseOverride failed: HTTP ${response.code}", response.code)
            }
            true
        } ?: false
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
     * nothing went out. Throws when the request fails, and refuses once [signIn]
     * has ended. [madeAtMs] is sent as `hlc`.
     */
    suspend fun patchIgnore(
        moodleId: String,
        ignore: Boolean?,
        moodleCourseId: Int?,
        signIn: SignIn,
        madeAtMs: Long? = null,
    ): Boolean? = withContext(Dispatchers.IO) {
        val syncs = { policies.current.sendsCourseIgnores }
        if (!syncs()) return@withContext null
        val payload = mutableMapOf<String, Any>()
        if (ignore != null) payload["ignore_assignments"] = ignore
        if (moodleCourseId != null && moodleCourseId >= 1) payload["moodle_course_id"] = moodleCourseId
        if (payload.isEmpty()) return@withContext null
        val service = api.at(baseUrl())
        val header = authHeader(signIn)
        executeStamped(madeAtMs, syncs, { hlc -> service.courseIgnore(header, moodleId, payload.stampedWith(hlc)) }) { response ->
            if (!response.isSuccessful) {
                throw PushApiException("patchCourseIgnore failed: HTTP ${response.code}", response.code)
            }
            runCatching { JSONObject(response.body.string()).has("ignore_assignments") }
                .getOrDefault(false)
        }
    }
}
