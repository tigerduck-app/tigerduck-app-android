// What one send of the course list's channels does: a term's upload of the adds asked
// (PendingCourseAdds) and one DELETE (PendingCourseDeletes), each run by its lane under the send
// lock (PendingCourseAdds.sendingOne), and what each answer leaves queued.

package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [CourseDeleter] with the backend's answer, and the delete's [kind], `/v4`'s `hide` or `remove`,
 * when it is known; see [org.ntust.app.tigerduck.push.PushApiClient.deleteCourseAnswered].
 */
internal fun interface AnsweredCourseDeleter {
    suspend fun delete(courseKey: String, signIn: SignIn, hiddenAt: Long, kind: String?): WriteAnswer
}

/**
 * What a course add's or delete's failed send comes to. Refused as ahead of the backend's clock
 * once more, after the client's one restamp (409 `clock_ahead`), it says nothing about the edit,
 * which goes again at the next sync ([SendResult.Retry]): [SendResult.afterFailure] would count
 * the 409 as one of its refusals. Any other failure is as that reads it.
 */
private fun afterSendFailure(failure: Exception): SendResult =
    if (failure.backendError()?.isClockAhead == true) SendResult.Retry else SendResult.afterFailure(failure)

/**
 * The course adds' sends. One send is a term's upload of the adds a caller asked for ([asking]):
 * the lane hands over the first of them still waiting ([sendRequested]), and that upload carries
 * them all; any other of them the lane hands over after it sends nothing more. [waiting] reads
 * the lane's adds, and [settle] changes them in one step with the lane's own writes.
 */
internal class CourseAddSends(
    private val dataCache: DataCache,
    private val uploader: CourseUploader,
    private val outbox: MembershipOutbox,
    private val waiting: suspend () -> List<CourseAdd>,
    private val settle: suspend (SignIn, (List<CourseAdd>) -> List<CourseAdd>) -> Boolean,
) {
    // The request the send under way is for, set under the send lock, so one at a time.
    private val request = AtomicReference<Request?>(null)

    /**
     * Runs [drain], the lane's send of [courseNos] of [semester], as one request: they go up with
     * [courses] when given, else as this phone holds them. True when an upload of them was answered.
     */
    suspend fun asking(semester: String, courseNos: List<String>, courses: List<Course>?, drain: suspend () -> Unit): Boolean {
        val asked = Request(semester, courseNos, courses)
        request.set(asked)
        try {
            drain()
        } finally {
            request.set(null)
        }
        return asked.landed
    }

    /** The lane's send of [add]: the request it is the first of, or nothing more. */
    suspend fun sendRequested(add: CourseAdd, signIn: SignIn): SendResult {
        val asked = request.getAndSet(null) ?: return SendResult.NotSent
        return uploadAsked(asked, signIn)[add] ?: SendResult.NotSent
    }

    /**
     * [asked]'s adds still waiting, read with their moments in one step under the lane's lock they
     * change under (a timed add read without its moment would go unstamped), checked right before
     * the request: an add whose course has left this phone's cache or been deleted since is let
     * go unsent. An add with its time goes under protocol 2, alone with the others timed: there
     * every other course of the upload counts as the school's roster. One an older install kept
     * has no time and goes as before. Returns what each add's send came to; those settled have
     * left the queue, unless added again meanwhile.
     */
    private suspend fun uploadAsked(asked: Request, signIn: SignIn): Map<CourseAdd, SendResult> {
        val semester = asked.semester
        val requested = waiting().filter { it.semester == semester && it.courseNo in asked.courseNos }
        val cached = dataCache.loadCourses(semester)
        val deleted = dataCache.loadDeletedCourseNos()
        val held = requested.filter { add ->
            cached.any { it.courseNo == add.courseNo } && !CourseTombstoneKeys.isHidden(add.courseNo, semester, deleted)
        }
        val (timed, untimed) = held.partition { it.addedAtMs != null }
        val source = asked.courses ?: cached
        val stamped = if (timed.isEmpty()) Taken() else {
            upload(source.filter { course -> timed.any { it.courseNo == course.courseNo } }, semester, timed, signIn)
        }
        val unstamped = if (untimed.isEmpty()) Taken() else {
            upload(asked.courses ?: cached.filter { course -> untimed.any { it.courseNo == course.courseNo } }, semester, untimed, signIn)
        }
        if (stamped.landed || unstamped.landed) asked.landed = true
        noteRefused(stamped.refused, semester, signIn)
        val results = LinkedHashMap<CourseAdd, SendResult>()
        // Let go unsent: its course has left this phone, or is hidden.
        for (add in requested) if (add !in held) results[add] = SendResult.Sent(null)
        for (taken in listOf(stamped, unstamped)) for (add in taken.adds) results[add] = taken.resultOf(add, outbox.keepsUnsent())
        val done = results.filterValues { it is SendResult.Sent }.keys
        // Acknowledged before the adds go, so a pull in between holds the course by one or the other.
        done.forEach { add -> (results[add] as SendResult.Sent).serverClockUs?.let { outbox.ack.acknowledged(add.field, it, signIn) } }
        // Only an add still as it was sent: one added again meanwhile has a newer moment, which
        // this answer, to the older add, does not settle.
        if (done.isNotEmpty()) settle(signIn) { adds -> adds.filterNot { it in done } }
        return results
    }

    /**
     * A timed add the backend refused, a hide or reset elsewhere being newer: the server has ruled
     * on the course, so a snapshot's tombstone may hide it, as it never does a course this phone
     * typed and the server has not seen. Not one it applied: a snapshot taken before the add landed
     * would then read its absence as a delete. On `/v4` the backend's answer stands only while no
     * newer add of the course waits (LWW D12, a write response's winner).
     */
    private suspend fun noteRefused(refused: List<CourseAdd>, semester: String, signIn: SignIn) {
        if (refused.isEmpty()) return
        val adds = if (outbox.lastWriterWins()) waiting() else emptyList()
        val ruled = refused.filter { add -> adds.none { it.field == add.field && it != add } }.map { it.courseNo }
        if (ruled.isEmpty()) return
        dataCache.updateServerKnownNos(signIn) { known ->
            known + (semester to (known[semester].orEmpty() + ruled).distinct().sorted())
        }
    }

    // One upload of [adds], each forced past a tombstone in the adds' order, and each timed one with
    // its moment, in the order the times file had them in the read the adds came from
    // ([CourseAdd.momentAt]), as `/v3`'s body always listed them.
    private suspend fun upload(courses: List<Course>, semester: String, adds: List<CourseAdd>, signIn: SignIn): Taken {
        val courseNos = adds.map { it.courseNo }
        val addedAt = adds.filter { it.addedAtMs != null }.sortedBy { it.momentAt }.associate { it.courseNo to it.addedAtMs!! }
        return try {
            val forceKeys = courseNos.map { PendingCourseAdds.forceKey(semester, it) }
            val answer = uploader.upload(courses, semester, forceKeys, signIn, addedAt) ?: return Taken(adds, unsent = true)
            val taken = Taken(adds, courseNos.filter { it in answer.accepted }, courseNos.filter { it in answer.refusedAdds }, serverClockUs = answer.serverClockUs)
            if (taken.accepted.size + taken.refusedNos.size < courseNos.size) Log.w(TAG, "course add not accepted, kept for the next sync")
            taken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course add upload failed, kept for the next sync: ${e.logSummary()}")
            Taken(adds, failure = e)
        }
    }

    // What one upload of [adds] came to: the courses the backend took ([accepted]) and the timed
    // adds it refused ([refusedNos]); [unsent] when nothing went, course sync being off; [failure]
    // when the request failed.
    private class Taken(
        val adds: List<CourseAdd> = emptyList(),
        val accepted: List<String> = emptyList(),
        val refusedNos: List<String> = emptyList(),
        val unsent: Boolean = false,
        val failure: Exception? = null,
        val serverClockUs: Long? = null,
    ) {
        val landed: Boolean get() = adds.isNotEmpty() && !unsent && failure == null
        val refused: List<CourseAdd> get() = adds.filter { it.courseNo in refusedNos }

        // A 200 alone is not acceptance (see PushApiClient.uploadCourses): an add it does not name
        // waits for the next sync. One sent while course sync is off is kept only where it is
        // kept until sync is on (MembershipOutbox.keepsUnsent), else let go, as before.
        fun resultOf(add: CourseAdd, keepsUnsent: Boolean): SendResult = when {
            failure != null -> afterSendFailure(failure)
            unsent -> if (keepsUnsent) SendResult.NotSent else SendResult.Sent(null)
            add.courseNo in accepted || add.courseNo in refusedNos -> SendResult.Sent(serverClockUs)
            else -> SendResult.Retry
        }
    }

    // The adds a send asks for: [courseNos] of [semester], going up with [courses] when given;
    // [landed] once an upload of them was answered.
    private class Request(val semester: String, val courseNos: List<String>, val courses: List<Course>?) {
        @Volatile var landed = false
    }

    private companion object {
        const val TAG = "PendingCourseAdds"
    }
}

/**
 * The course deletes' sends: one DELETE per hold of the send lock ([one]), with the delete's kind
 * when this process was told it ([told]). [waiting] reads the queue's deletes, and [underQueueLock]
 * runs a step under the queue's own lock, which every keep, send and forget of a delete takes.
 */
internal class CourseDeleteSends(
    private val dataCache: DataCache,
    private val deleter: AnsweredCourseDeleter,
    private val outbox: MembershipOutbox,
    private val waiting: suspend () -> List<CourseDelete>,
    private val underQueueLock: suspend (suspend () -> Unit) -> Unit,
) {
    // Each waiting delete's kind, by its field, as the class table told it when it kept it. In
    // memory: a delete read after a restart goes with none, and the backend decides from its row (X3).
    private val kinds = ConcurrentHashMap<String, String>()

    // Whether the send under the lock may still make its one request: a removal kept while it was
    // in flight goes at its own turn of the lock, as before, not on the back of this one.
    private val oneRequest = AtomicBoolean(false)

    /**
     * [field]'s delete, asked now, is of a course of [kind], null when its row is not known: the
     * newest ask is the one that goes, so a kind told for an older one does not outlive it.
     */
    fun told(field: String, kind: String?) {
        if (kind == null) kinds.remove(field) else kinds[field] = kind
    }

    /** Forgets every kind: at a sign-out. */
    fun forget() = kinds.clear()

    /** Runs [drain], the lane's send of one delete, letting it make one request at most. */
    suspend fun one(drain: suspend () -> Unit) {
        oneRequest.set(true)
        try {
            drain()
        } finally {
            oneRequest.set(false)
        }
    }

    /**
     * The lane's send of [delete], still queued as the lane read it under the adds lock: one a
     * re-add or a reset let go meanwhile does not go, as it would take the restored course off.
     */
    suspend fun sendOnce(delete: CourseDelete, signIn: SignIn): SendResult {
        if (!oneRequest.compareAndSet(true, false)) return SendResult.NotSent
        val answer = try {
            // The request itself carries [signIn]: a sign-out after the check sends it without a
            // token rather than with the next account's. With the moment it was asked, the backend
            // applies it only when later than the course's last add, hide and reset: a hide queued
            // offline loses to an add made since on another device, and a retry after a lost reply
            // changes nothing (#591).
            deleter.delete(PendingCourseAdds.forceKey(delete.semester, delete.courseNo), signIn, delete.queuedAt, kinds[delete.field])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course delete failed, kept for the next sync: ${e.logSummary()}")
            return afterSendFailure(e)
        }
        // Nothing went, course sync being off: kept where edits wait for it (/v4), let go on /v3.
        if (!answer.sent) return if (outbox.keepsUnsent()) SendResult.NotSent else SendResult.Sent(null)
        if (answer.applied?.isEmpty() == true && outbox.lastWriterWins()) takeBackendsAdd(delete, signIn)
        // The delete sent, and only that one: a removal asked during the request kept its own,
        // newer stamp on disk and is sent again.
        return SendResult.Sent(answer.serverClockUs)
    }

    // The backend refused [delete], an add of the course made since elsewhere being newer (LWW
    // D12, a write response's winner): this phone's hide of it goes, so the next pull shows the
    // course the backend lists, unless the course was deleted here again since. The check and the
    // lift are one step under the queue's lock: a newer delete is kept before the check, which then
    // leaves the hide alone, or after the lift, and its hide is marked again (the class table keeps
    // a delete before it marks its hide); never between the two, where its hide would go while its
    // DELETE waits, for a course selection answer to bring the course back.
    private suspend fun takeBackendsAdd(delete: CourseDelete, signIn: SignIn) = underQueueLock {
        if (waiting().none { it.field == delete.field && it != delete }) {
            val hides = setOf(CourseTombstoneKeys.key(delete.semester, delete.courseNo), delete.courseNo)
            dataCache.updateDeletedCourseNos(before = hides, after = emptySet(), signIn = signIn)
        }
    }

    companion object {
        private const val TAG = "PendingCourseDeletes"

        /**
         * A delete's kind on `/v4` (X3): `remove` for [course] added by hand ([Course.isManual]),
         * which takes back the add and leaves the course to the school's roster, else `hide`; none
         * when the row is not known, and the backend decides from its own.
         */
        fun kindOf(course: Course?): String? = when {
            course == null -> null
            course.isManual -> "remove"
            else -> "hide"
        }
    }
}
