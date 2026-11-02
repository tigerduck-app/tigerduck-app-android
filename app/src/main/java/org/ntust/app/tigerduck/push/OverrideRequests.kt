package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncPolicies

/**
 * The four edits of one field each that go up one at a time: a holiday's choice, an assignment's
 * mark, a course's color or name, and whether a course's assignments are ignored. For
 * [PushApiClient], which keeps each as a method of its own under its own name: the callers that
 * must go through an outbox are found by those names. Each is sent with [authHeader]'s header for
 * its sign-in, to the endpoint [baseUrl] names at the time, and answers with the backend's verdict
 * ([WriteAnswer]): [WriteAnswer.NOT_SENT] when its gate stopped it, and a refusal thrown with the
 * backend's code kept. [madeAtMs], the time the edit was made, goes as its `hlc`, as [sendStamped]
 * sends it for the API the endpoint speaks: on `/v4` always, the clock's stamp at its send when
 * there is none, and the clock values its answer holds heard.
 */
internal class OverrideRequests(
    private val api: BackendServices<PushApi>,
    private val policies: SyncPolicies,
    private val baseUrl: () -> String,
    private val authHeader: suspend (SignIn) -> String,
    private val clock: WriteClock,
) {
    /**
     * Upload one holiday exception so the user's other devices agree.
     *
     * Not sent when cloud sync is off or on fdroid: the local preference has
     * already been written by then, and this call is only about agreement
     * between devices, not about whether the guard works. Sent only as
     * [signIn]: see `PendingHolidayOverrides`.
     */
    suspend fun putHoliday(holidayId: Int, notify: Boolean, signIn: SignIn, madeAtMs: Long?): WriteAnswer =
        withContext(Dispatchers.IO) {
            if (!policies.current.tigerSyncOn) return@withContext WriteAnswer.NOT_SENT
            val base = baseUrl()
            val version = BackendApi.of(base)
            val service = api.at(base)
            val header = authHeader(signIn)
            sendStamped(version, madeAtMs, clock, { policies.current.tigerSyncOn }, { hlc -> service.holidayOverride(header, holidayId, HolidayOverrideRequest(notify, hlc)) }) { response ->
                clock.answer(response, version, "holiday override", WriteAnswer.NOTIFY)
            } ?: WriteAnswer.NOT_SENT
        }

    /**
     * [signIn] pins the request to that sign-in: after a sign-out it goes unsent
     * rather than with the next account's token. Not sent with the switch off, so the mark waits.
     */
    suspend fun patchMark(assignmentId: Int, localStatus: String, signIn: SignIn, madeAtMs: Long?): WriteAnswer =
        withContext(Dispatchers.IO) {
            val syncs = { policies.current.syncs(SyncCategory.ASSIGNMENTS) }
            if (!syncs()) return@withContext WriteAnswer.NOT_SENT
            val payload = mapOf("local_status" to localStatus)
            val base = baseUrl()
            val version = BackendApi.of(base)
            val service = api.at(base)
            val header = authHeader(signIn)
            sendStamped(version, madeAtMs, clock, syncs, { hlc -> service.assignmentOverride(header, assignmentId, payload.stampedWith(hlc)) }) { response ->
                clock.answer(response, version, "patchAssignmentOverride", WriteAnswer.LOCAL_STATUS)
            } ?: WriteAnswer.NOT_SENT
        }

    /**
     * PATCH the colour of the course [target], or its name in [locale].
     * Not sent when TigerSync, or the switch for colours or names, is off, or
     * when [allowed] says no right before the request goes, after the header
     * is made. [signIn] pins the request to that sign-in, as [patchMark]'s is.
     */
    suspend fun patchCourse(
        target: CourseRef,
        signIn: SignIn,
        colorHex: String?,
        customName: String?,
        locale: String?,
        allowed: () -> Boolean,
        madeAtMs: Long?,
    ): WriteAnswer = withContext(Dispatchers.IO) {
        val switchesOn = { policies.current.syncsOverride(color = colorHex != null, name = customName != null) }
        if (!switchesOn()) return@withContext WriteAnswer.NOT_SENT
        val base = baseUrl()
        val version = BackendApi.of(base)
        val course = target.named(version, "a course override")
        val payload = listOfNotNull(colorHex?.let { "color_hex" to it }, customName?.let { "custom_name" to it }, locale?.let { "locale" to it })
            .toMap()
        val service = api.at(base)
        val header = authHeader(signIn)
        // Read once the header is made, which can renew the session and so
        // suspend: a hold the course outbox put on the kind meanwhile must
        // stop the request, which the switches above alone would let through.
        if (!allowed()) return@withContext WriteAnswer.NOT_SENT
        // The resend passes the same gate as the first request: the hold can go on meanwhile.
        val call = { hlc: Long? -> service.courseOverride(header, course, payload.stampedWith(hlc)) }
        sendStamped(version, madeAtMs, clock, { switchesOn() && allowed() }, call) { response ->
            clock.answer(response, version, "patchCourseOverride")
        } ?: WriteAnswer.NOT_SENT
    }

    /**
     * PATCH whether every assignment of the course [target] is ignored,
     * through the same override as its colour and name. [moodleCourseId] is
     * the course's numeric Moodle id, which the backend's reminder scan
     * matches the assignments it fetches itself by. Either may be null, and
     * with neither nothing is sent; nor is it with course or assignment sync
     * off. [values][WriteAnswer.values] holds `ignore_assignments` when the
     * backend stored it, and not from a backend that predates the field and
     * drops it unread. Refused once [signIn] has ended.
     */
    suspend fun patchIgnore(target: CourseRef, ignore: Boolean?, moodleCourseId: Int?, signIn: SignIn, madeAtMs: Long?): WriteAnswer =
        withContext(Dispatchers.IO) {
            val syncs = { policies.current.sendsCourseIgnores }
            if (!syncs()) return@withContext WriteAnswer.NOT_SENT
            val payload = mutableMapOf<String, Any>()
            if (ignore != null) payload["ignore_assignments"] = ignore
            if (moodleCourseId != null && moodleCourseId >= 1) payload["moodle_course_id"] = moodleCourseId
            if (payload.isEmpty()) return@withContext WriteAnswer.NOT_SENT
            val base = baseUrl()
            val version = BackendApi.of(base)
            val course = target.named(version, "a course ignore")
            val service = api.at(base)
            val header = authHeader(signIn)
            sendStamped(version, madeAtMs, clock, syncs, { hlc -> service.courseIgnore(header, course, payload.stampedWith(hlc)) }) { response ->
                clock.answer(response, version, "patchCourseIgnore")
            } ?: WriteAnswer.NOT_SENT
        }
}
