// What one send of the course colors and names' channel (PendingCourseOverrides) does: the PATCH
// of one waiting change, run by its lane, what its answer or its failure leaves queued, and the
// backend's value taken where it kept a later one.

package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.push.CourseKeyUnknownException
import org.ntust.app.tigerduck.push.CourseRef
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.time.AcademicTerm
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.EntryFloors
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OnDiskAttempts
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.sync.outbox.UntimedEntries
import org.ntust.app.tigerduck.util.logSummary

/**
 * Sends [change], a course's color or its name in one locale, to the course [target] names, as
 * sign-in [signIn], and says what the backend answered; see
 * [org.ntust.app.tigerduck.push.PushApiClient.patchCourseOverrideAnswered]. [allowed] is read once
 * more right before the request goes, after whatever suspends in between. [madeAt] is the time the
 * change's age is counted from.
 */
internal fun interface CourseOverrideWriter {
    suspend fun write(change: PendingCourseOverride, target: CourseRef, signIn: SignIn, allowed: () -> Boolean, madeAt: EditTime?): WriteAnswer
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
 * parked at [maxAttempts]; and one an older build wrote with no time given the old-entry time
 * [oldEntryTimeMs] reads (X1).
 */
internal fun courseOverridesPolicy(
    maxAttempts: Int,
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
        timeOf = { change -> change.madeAtMs },
        withTime = { change, atMs -> change.copy(madeAtMs = atMs) },
        oldEntryTimeMs = oldEntryTimeMs,
    ),
    floors = EntryFloors(PendingCourseOverride::madeAt, { it.madeAtClocks?.floor?.time }, PendingCourseOverride::withFloor),
)

/** Where a change's course is on this phone: its term and number. */
internal data class CoursePlace(val semester: String, val courseNo: String) {
    /** The key the backend knows the course by. */
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
 * The current term's courses by their Moodle id, read once: where a change made now that names no
 * term is, as the class table holds the course now. The first course with an id, as a lookup finds it.
 */
internal suspend fun currentTermPlaces(dataCache: DataCache): Map<String, CoursePlace> {
    val semester = dataCache.currentSemesterCode()
    return placesByMoodleId(mapOf(semester to dataCache.loadCourses(semester))).mapValues { (_, places) -> places.first() }
}

/**
 * Every course of [everyTerm] (each term's courses, as `DataCache.loadEveryTermCourses` reads them)
 * that has a Moodle id, by that id, each with its term: [current]'s first, then the other terms
 * newest first. Two terms' courses can share an id, and a change an older build kept with no term
 * names its course by that id alone; its first place is where it goes, as iOS looks such a change
 * up in its own term and then in the catalogue's, newest first (`OutboxSender.courseKey`). The one
 * lookup of an id's courses: the send resolves a key by it, and a pull's held names hold every
 * course it gives ([org.ntust.app.tigerduck.ui.screen.home.StampedCourseOverrides.heldNames]).
 */
internal fun placesByMoodleId(everyTerm: Map<String, List<Course>>, current: String? = null): Map<String, List<CoursePlace>> {
    val order = listOfNotNull(current?.takeIf(everyTerm::containsKey)) +
        everyTerm.keys.filter { it != current }.sortedWith(AcademicTerm.newestFirstStable)
    val places = LinkedHashMap<String, MutableList<CoursePlace>>()
    for (semester in order) {
        for (course in everyTerm.getValue(semester)) {
            val moodleId = course.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: continue
            val place = CoursePlace(semester, course.courseNo)
            val known = places.getOrPut(moodleId) { mutableListOf() }
            if (place !in known) known += place
        }
    }
    return places
}

/**
 * Where a change an older build kept with no term goes, in iOS's order
 * (`OutboxSender.courseKey`): the first course with [moodleId] in the terms this phone caches
 * ([placesByMoodleId], [current]'s first); with none, the term and number the id itself names
 * ([termAndNumber]) among [current] and then [catalogue]'s terms, the catalogue's order, newest
 * first. A term this phone never cached names a course too, once it is the current term or the
 * catalogue's. Null when neither does: the change parks, for an upload that brings its course.
 */
internal fun legacyPlaceOf(moodleId: String, everyTerm: Map<String, List<Course>>, current: String?, catalogue: List<String>): CoursePlace? =
    legacyPlacesOf(moodleId, everyTerm, current, catalogue).firstOrNull()

/**
 * Every place [legacyPlaceOf] reads [moodleId] as, in its order, the first the one it sends to: each
 * cached course with the id, else the one the id itself names, else none. A pull holds the change
 * by each of them ([org.ntust.app.tigerduck.ui.screen.home.StampedCourseOverrides.heldNames]), so it
 * holds what the send goes to, as holding one too many is safe and losing a waiting edit is not.
 */
internal fun legacyPlacesOf(moodleId: String, everyTerm: Map<String, List<Course>>, current: String?, catalogue: List<String>): List<CoursePlace> =
    placesByMoodleId(everyTerm, current)[moodleId]
        ?: listOfNotNull(termAndNumber(moodleId, listOfNotNull(current) + catalogue.filter { it != current }))

/**
 * iOS's `OutboxSender.termAndNumber`: [moodleId] read as a term code followed by a course number,
 * the first of [terms] that it starts with and is longer than, the rest its course number. Nothing
 * else is checked, as on iOS: an id no term of [terms] begins, or one that is only a term, names
 * none. A blank term is none of [terms], where iOS's `hasPrefix("")` would take it and name the
 * whole id as the course; no term this phone offers is blank.
 */
internal fun termAndNumber(moodleId: String, terms: List<String>): CoursePlace? {
    val term = terms.firstOrNull { it.isNotBlank() && moodleId.startsWith(it) && moodleId.length > it.length } ?: return null
    return CoursePlace(term, moodleId.substring(term.length))
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
 * [waiting] reads the lane's changes, and [landed] runs once the backend has a change, before the
 * lane lets it go and hands over the next.
 */
internal class CourseOverrideSends(
    private val dataCache: DataCache,
    private val writer: CourseOverrideWriter,
    private val sends: (color: Boolean) -> Boolean,
    // The catalogue's terms, newest first, for an older build's change by its Moodle id.
    private val catalogue: () -> List<String>,
    private val waiting: suspend () -> List<PendingCourseOverride>,
    private val landed: suspend () -> Unit,
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
        val place = placeFor(change)
        val answer = try {
            // The request carries [signIn], so a sign-out after the check sends it without a token
            // rather than with the next account's.
            writer.write(change, CourseRef(place?.key), signIn, allowed, change.madeAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course override PATCH failed, kept for the next sync: ${e.logSummary()}")
            return afterFailure(change, e)
        }
        if (!answer.sent) return SendResult.NotSent
        if (change.key != null) landed()
        if (place != null) takeWinner(change, place, answer, signIn)
        return SendResult.Sent(answer.serverClockUs)
    }

    /**
     * What [change]'s failed PATCH comes to. A 404 for a course no
     * longer cached in the term it was made in lets it go at once: the row went with the course,
     * no upload brings it back, and kept it would be sent, and refused, after every one. A course
     * still on this phone that the backend does not have yet (404 `course_not_found`, as a rename
     * made before its course's upload landed gets, or a key that cannot be built) parks it until a
     * course upload lands (spec §5.3 race 3, refactor-v4 R3). Any other failure is as
     * [SendResult.afterFailure] reads it: a refusal counts, and parks it at
     * [PendingCourseOverrides.MAX_ATTEMPTS], never lets it go.
     */
    private suspend fun afterFailure(change: PendingCourseOverride, e: Exception): SendResult {
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

    // A row an older build wrote with no term: where its Moodle id leads (legacyPlaceOf), as the
    // backend names a course by its key. Null when nothing does: the row parks (CourseKeyUnknownException),
    // and each later try is this lookup again, on this phone's files, with no request, as the key is
    // built before the request is.
    /** Where [change] goes, by its term and number, or by where an older build's Moodle id leads. */
    suspend fun placeFor(change: PendingCourseOverride): CoursePlace? = placeOf(change) ?: located(change)

    private suspend fun located(change: PendingCourseOverride): CoursePlace? {
        val moodleId = change.moodleId ?: return null
        return legacyPlaceOf(moodleId, dataCache.loadEveryTermCourses(), dataCache.currentSemesterCode(), catalogue())
    }

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
