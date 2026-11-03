// What one send of the course colors and names' channel (PendingCourseOverrides) does: the PATCH
// of one waiting change, run by its lane, what its answer or its failure leaves queued, and on
// /v4 the backend's value taken where it kept a later one.

package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.push.CourseKeyUnknownException
import org.ntust.app.tigerduck.push.CourseRef
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OnDiskAttempts
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.sync.outbox.UntimedEntries
import org.ntust.app.tigerduck.util.logSummary

/**
 * Sends one course's colour, or its name in one locale, as sign-in [signIn], and says only whether
 * it went out; for a test that sends through one. [stillAllowed] is read once more right before
 * the request goes, after whatever suspends in between.
 */
internal fun interface CourseOverrideSender {
    suspend fun send(
        moodleId: String,
        colorHex: String?,
        customName: String?,
        locale: String?,
        signIn: SignIn,
        stillAllowed: () -> Boolean,
        madeAtMs: Long?,
    ): Boolean
}

// A sender that says only whether a change went: an answer with nothing more in it.
internal fun CourseOverrideSender.answering() = CourseOverrideWriter { change, _, signIn, allowed ->
    val moodleId = change.moodleId ?: return@CourseOverrideWriter WriteAnswer.NOT_SENT
    if (send(moodleId, change.colorHex, change.customName, change.locale, signIn, allowed, change.madeAtMs)) {
        WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap())
    } else {
        WriteAnswer.NOT_SENT
    }
}

/**
 * Sends [change], a course's color or its name in one locale, to the course [target] names, as
 * sign-in [signIn], and says what the backend answered; see
 * [org.ntust.app.tigerduck.push.PushApiClient.patchCourseOverrideAnswered]. [allowed] is read once
 * more right before the request goes, after whatever suspends in between.
 */
internal fun interface CourseOverrideWriter {
    suspend fun write(change: PendingCourseOverride, target: CourseRef, signIn: SignIn, allowed: () -> Boolean): WriteAnswer
}

/**
 * The lane's store: `pending_course_overrides.json`, read by key in the order the changes were
 * made, as the queue has always read it, so a key written twice reads as its last change.
 */
internal class CourseOverridesStore(private val dataCache: DataCache) : LaneStore<PendingCourseOverride> {
    override suspend fun load(): List<PendingCourseOverride> =
        dataCache.loadPendingCourseOverrides().associateByTo(LinkedHashMap()) { it.key.orEmpty() }.values.toList()

    override suspend fun save(entries: List<PendingCourseOverride>, signIn: SignIn): Boolean =
        dataCache.savePendingCourseOverrides(entries, signIn)
}

/**
 * The lane's rules: one change per field ([fieldOf]), its refusals counted in the change itself,
 * parked at [maxAttempts]; and, on `/v4` ([lastWriterWins]), one an older build wrote with no time
 * given the old-entry time [oldEntryTimeMs] reads (X1). On `/v3` such a change goes without one,
 * as it always has, so it is given none.
 */
internal fun courseOverridesPolicy(
    maxAttempts: Int,
    lastWriterWins: () -> Boolean,
    oldEntryTimeMs: suspend () -> Long,
) = LanePolicy(
    field = ::fieldOf,
    maxAttempts = maxAttempts,
    attempts = OnDiskAttempts<PendingCourseOverride>(
        attempts = { it.attempts },
        counted = { change, attempts, _ -> change.copy(attempts = attempts) },
        // The whole change, so the field too: the lane asks it of changes of any field.
        sameEdit = { a, b -> a.isSameChange(b) },
    ),
    untimed = UntimedEntries(
        timeOf = { change -> change.madeAtMs ?: if (lastWriterWins()) null else SENT_WITHOUT_TIME },
        withTime = { change, atMs -> change.copy(madeAtMs = atMs) },
        oldEntryTimeMs = oldEntryTimeMs,
    ),
)

// What a change with no time reads as on /v3, where it goes without one: a time it needs none of.
private const val SENT_WITHOUT_TIME = 0L

/** Where a change's course is on this phone: its term and number. */
internal data class CoursePlace(val semester: String, val courseNo: String) {
    /** The key the backend knows the course by on `/v4`. */
    val key: String get() = SyncField.courseKey(semester, courseNo)
}

/** [row]'s course as the row names it, when it names its term and number. */
internal fun placeOf(row: PendingCourseOverride): CoursePlace? {
    val semester = row.semester?.takeIf { it.isNotBlank() } ?: return null
    val courseNo = row.courseNo?.takeIf { it.isNotBlank() } ?: return null
    return CoursePlace(semester, courseNo)
}

/** Whether [this] waits for a course upload, or for its one more try after its refusals: parked. */
internal val PendingCourseOverride.isParked: Boolean
    get() = attempts >= PendingCourseOverrides.MAX_ATTEMPTS

/**
 * The current term's courses by their Moodle id, read once: where a change that names no term is,
 * as the class table holds the course now. The first course with an id, as a lookup finds it.
 */
internal suspend fun currentTermPlaces(dataCache: DataCache): Map<String, CoursePlace> {
    val semester = dataCache.currentSemesterCode()
    val places = LinkedHashMap<String, CoursePlace>()
    for (course in dataCache.loadCourses(semester)) {
        val moodleId = course.moodleIdNumber ?: continue
        places.putIfAbsent(moodleId, CoursePlace(semester, course.courseNo))
    }
    return places
}

/**
 * The field ([SyncField]) [row] edits: its course's color, or its name in its locale, by the
 * course's key where the row names its term, else by its Moodle id, which no snapshot row names.
 * Read from the row alone, so one row always edits one field.
 */
internal fun fieldOf(row: PendingCourseOverride): String {
    val course = placeOf(row)?.key ?: row.moodleId.orEmpty()
    return if (row.colorHex != null) SyncField.color(course) else SyncField.name(course, row.locale.orEmpty())
}

/**
 * The course channel's sends, one waiting change at a time as its lane hands it over, read as the
 * request is about to go: its kind's gate ([sends]), the PATCH ([writer]), and what became of it.
 * [waiting] reads the lane's changes, [confirmed] notes a change the backend has, before the lane
 * lets it go, and [lastWriterWins] says the backend settles each field by its stamp, as `/v4` does.
 */
internal class CourseOverrideSends(
    private val dataCache: DataCache,
    private val writer: CourseOverrideWriter,
    private val sends: (color: Boolean) -> Boolean,
    private val lastWriterWins: () -> Boolean,
    private val waiting: suspend () -> List<PendingCourseOverride>,
    private val confirmed: suspend (key: String, signIn: SignIn) -> Unit,
) {
    /**
     * The lane's send of [change] under [signIn]. Not sent while its kind does not go, or once
     * [signIn] has ended, which the writer reads again after the header is made: the checks before
     * suspend, and sync switched off and on again meanwhile puts the kind on hold.
     */
    suspend fun send(change: PendingCourseOverride, signIn: SignIn): SendResult {
        val color = change.colorHex != null
        val allowed = { sends(color) && signIn.isCurrent }
        if (!allowed()) return SendResult.NotSent
        val place = placeOf(change) ?: if (lastWriterWins()) located(change) else null
        val answer = try {
            // The request carries [signIn], so a sign-out after the check sends it without a token
            // rather than with the next account's.
            writer.write(change, CourseRef(change.moodleId, place?.key), signIn, allowed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course override PATCH failed, kept for the next sync: ${e.logSummary()}")
            return afterFailure(change, e)
        }
        if (!answer.sent) return SendResult.NotSent
        change.key?.let { confirmed(it, signIn) }
        if (lastWriterWins() && place != null) takeWinner(change, place, answer, signIn)
        return SendResult.Sent(answer.serverClockUs)
    }

    /**
     * What [change]'s failed PATCH comes to. Refused as ahead of the backend's clock once more,
     * after the client's one restamp, it says nothing about the change. A 404 for a course no
     * longer cached in the term it was made in lets it go at once: the row went with the course,
     * no upload brings it back, and kept it would be sent, and refused, after every one. A course
     * still on this phone that the backend does not have yet (404 `course_not_found`, as a rename
     * made before its course's upload landed gets, or a key that cannot be built) parks it until a
     * course upload lands (spec §5.3 race 3, refactor-v4 R3). Any other failure is as
     * [SendResult.afterFailure] reads it: a refusal counts, and parks it at
     * [PendingCourseOverrides.MAX_ATTEMPTS], never lets it go.
     */
    private suspend fun afterFailure(change: PendingCourseOverride, e: Exception): SendResult {
        if (e.backendError()?.isClockAhead == true) return SendResult.Retry
        if (courseGone(e, change)) {
            Log.w(TAG, "course override let go: its course has left this phone")
            return SendResult.Sent(null)
        }
        if (waitsForUpload(e)) return SendResult.WaitsForUpload
        return SendResult.afterFailure(e)
    }

    // Whether [e] says the backend has no row for the course yet, which a course upload makes.
    private fun waitsForUpload(e: Exception): Boolean =
        e is CourseKeyUnknownException || e.backendError()?.let { it.status == HTTP_NOT_FOUND && it.code == COURSE_NOT_FOUND } == true

    // Whether [e] is a 404 for a course no longer cached in the term [change] was made in: deleted
    // on another device, with the backend's row, and pruned here by a sync. Not known without the term.
    private suspend fun courseGone(e: Exception, change: PendingCourseOverride): Boolean {
        if (e.backendError()?.status != HTTP_NOT_FOUND) return false
        val place = placeOf(change) ?: return false
        return dataCache.loadCourses(place.semester).none { it.courseNo == place.courseNo }
    }

    // A row an older build wrote with no term: the current term's course with its Moodle id, as
    // `/v4` names a course by its key. Null when no such course is cached.
    private suspend fun located(change: PendingCourseOverride): CoursePlace? =
        change.moodleId?.let { currentTermPlaces(dataCache)[it] }

    /**
     * LWW D12, a write response's winner: when the backend kept a later value of [change]'s field,
     * that value goes on this phone, unless a newer change of the field waits, whose own answer
     * settles it. Taken only while this phone's value is still the one the reply answered: the
     * class table saves a pick and the queue keeps it in two steps, and a newer pick's value is
     * never the one answered. Decided and written in one step with every save of the courses or
     * the names, the waiting changes read inside it, so a pick saved or queued meanwhile stays.
     */
    private suspend fun takeWinner(change: PendingCourseOverride, place: CoursePlace, answer: WriteAnswer, signIn: SignIn) {
        val applied = answer.applied ?: return
        val key = change.key ?: return
        // An ended sign-in's reply reads nothing of the next student's to decide it.
        suspend fun newerWaits() = !signIn.isCurrent || waiting().any { it.key == key && !it.isSameChange(change) }
        val answeredColor = change.colorHex
        if (answeredColor != null) {
            if (COLOR in applied) return
            val winner = answer.values[COLOR] as? String ?: return
            dataCache.updateCoursesIfChanged(place.semester, signIn) { courses ->
                if (newerWaits() || courses.none { it.courseNo == place.courseNo && it.customColorHex == answeredColor }) return@updateCoursesIfChanged courses
                courses.map { if (it.courseNo == place.courseNo) it.copy(customColorHex = winner) else it }
            }
            return
        }
        val locale = change.locale ?: return
        val answeredName = change.customName ?: return
        if (NAME in applied) return
        val winner = (answer.values[NAMES] as? Map<*, *>)?.get(locale) as? String ?: return
        dataCache.updateCourseCustomNames(signIn) { names ->
            if (newerWaits()) return@updateCourseCustomNames names
            val ofCourse = names[place.courseNo].orEmpty()
            if (ofCourse[locale].orEmpty() != answeredName) return@updateCourseCustomNames names
            // An empty name is one cleared, which the names leave out, as a snapshot's is.
            val named = if (winner.isEmpty()) ofCourse - locale else ofCourse + (locale to winner)
            if (named.isEmpty()) names - place.courseNo else names + (place.courseNo to named)
        }
    }

    private companion object {
        const val TAG = "PendingCourseOverrides"
        const val HTTP_NOT_FOUND = 404
        const val COURSE_NOT_FOUND = "course_not_found"

        // The reply's fields (`docs/api-v4.md`, "Course override"), and its names of them in `applied`.
        const val COLOR = "color_hex"
        const val NAME = "custom_name"
        const val NAMES = "custom_names"
    }
}
