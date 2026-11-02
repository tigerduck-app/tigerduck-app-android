// What one send of the course list's channels does: a term's upload of the adds asked
// (PendingCourseAdds), run by its lane under the send lock (PendingCourseAdds.sendingOne), and what
// each answer leaves queued.

package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicReference

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

    // One upload of [adds], each forced past a tombstone and each timed one with its moment.
    private suspend fun upload(courses: List<Course>, semester: String, adds: List<CourseAdd>, signIn: SignIn): Taken {
        val courseNos = adds.map { it.courseNo }
        val addedAt = adds.mapNotNull { add -> add.addedAtMs?.let { add.courseNo to it } }.toMap()
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
            failure != null -> SendResult.afterFailure(failure)
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
