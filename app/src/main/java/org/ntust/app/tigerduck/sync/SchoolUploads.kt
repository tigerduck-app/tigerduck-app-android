package org.ntust.app.tigerduck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.CourseUpload
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What goes up from a school or Moodle fetch: a term's courses, and the assignments. Each waits
 * for this process's first pull under its sign-in ([FirstPullGate]), so a roster or a list fetched
 * before the pull is not sent ahead of what the backend holds (`sync-order`, "A refresh reads the
 * backend before it uploads school data"; refactor-v4 D13; iOS `SchoolDataUploader`). A local edit
 * never comes here: it goes at once, through its channel's outbox, and so does the full sync's own
 * resend of a term.
 *
 * Asked for once the gate is open for its sign-in, an upload goes at once, in its caller, as it
 * always did. Asked for before, it goes on the application scope once the gate opens, built then
 * from what this phone holds, in place of an older one of its kind still waiting; it is dropped
 * when its sign-in ends, and as the `UploadLedger` never recorded it, the next fetch sends it.
 */
@Singleton
class SchoolUploads internal constructor(
    /** Null for none: every upload at once, as before the gate (see [atOnce]). */
    private val gate: FirstPullGate?,
    private val scope: CoroutineScope,
    private val dataCache: DataCache,
    private val sendAssignments: suspend (List<Assignment>, SignIn) -> Unit,
) {
    @Inject constructor(
        gate: FirstPullGate,
        @ApplicationScope scope: CoroutineScope,
        dataCache: DataCache,
        pushApiClient: PushApiClient,
    ) : this(gate, scope, dataCache, { assignments, signIn -> pushApiClient.uploadAssignments(assignments, signIn) })

    private class Later(val signIn: SignIn)

    private val lock = Any()

    // Under [lock], by what each sends: the upload waiting for the first pull, the newest asked for.
    private val waiting = HashMap<String, Later>()

    /** How many uploads wait for the first pull, for a test. */
    internal val waitingUploads: Int get() = synchronized(lock) { waiting.size }

    /**
     * Runs [now] at once, in the caller, when this process has pulled under [signIn]: a caller's
     * cancellation stops it, as it always did. Otherwise [later] runs on the application scope once
     * a pull under [signIn] lands, which a cancelled caller no longer stops: it is not the caller's
     * any more. It does not run once [signIn] has ended, nor once a newer upload of the same [what]
     * was asked for, waiting or sent at once, as the newest has what it would send.
     */
    suspend fun afterFirstPull(signIn: SignIn, what: String, now: suspend () -> Unit, later: suspend (SignIn) -> Unit) {
        if (gate == null || gate.hasPulled(signIn)) {
            synchronized(lock) { waiting.remove(what) }
            return now()
        }
        if (!signIn.isCurrent) return
        val mine = Later(signIn)
        synchronized(lock) { waiting[what] = mine }
        scope.launch {
            val pulled = gate.awaitPull(signIn)
            // Taken under the lock: only the newest of its kind goes, and only once.
            val newest = synchronized(lock) { (waiting[what] === mine).also { if (it) waiting.remove(what) } }
            if (!pulled || !newest) return@launch
            try {
                later(signIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what after the first pull failed (non-fatal): ${e.logSummary()}")
            }
        }
    }

    /**
     * The assignments a Moodle round or an assignment's page wrote, [written] as written, under
     * [signIn]. Once the gate is open they go at once. Before, what goes once it opens is the
     * assignments as stored then: the newest written, as the uploads of two writes may have
     * waited in either order. [failed] hears a send that failed, now or then; a cancellation of
     * the caller is no failure, and goes on up.
     */
    suspend fun assignments(written: List<Assignment>, signIn: SignIn, failed: (Exception) -> Unit = {}) = afterFirstPull(
        signIn, ASSIGNMENTS,
        now = { reporting(failed) { sendAssignments(written, signIn) } },
        later = { pinned ->
            val stored = dataCache.loadAssignments()
            // Signed out while the file was read: what it held is not this sign-in's.
            if (pinned.isCurrent) reporting(failed) { sendAssignments(stored, pinned) }
        },
    )

    /**
     * A school fetch's upload of [semester]'s courses under [signIn], asked for inside the
     * caller's [uploading] block that committed them. Once the gate is open, [send] is handed
     * [committed], the courses as the fetch saved them, as [CourseUpload.of] makes them with
     * [roster], at once. Before, once it opens, it is handed [semester]'s courses as this phone
     * holds them then, the pull's changes included, built under [uploading] again so a reset's
     * DELETE orders with it as with the fetch's own; and only when [stillWanted] says so there,
     * as an answer a newer one replaced, or a term reset since, has nothing to send. [requested]
     * marks the backend's own request for the roster, which waits apart from a fetch's upload.
     */
    internal suspend fun term(
        signIn: SignIn,
        semester: String,
        committed: List<Course>,
        roster: Collection<String>?,
        uploading: suspend (suspend () -> Unit) -> Unit,
        stillWanted: suspend () -> Boolean,
        requested: Boolean = false,
        send: suspend (CourseUpload) -> Unit,
    ) = afterFirstPull(
        signIn, "$COURSES$semester" + if (requested) REQUESTED else "",
        now = { send(upload(committed, semester, roster)) },
        later = { pinned ->
            uploading {
                if (!pinned.isCurrent || !stillWanted()) return@uploading
                val held = dataCache.loadCourses(semester)
                if (held.isNotEmpty() && pinned.isCurrent) send(upload(held, semester, roster))
            }
        },
    )

    private suspend fun upload(courses: List<Course>, semester: String, roster: Collection<String>?): CourseUpload =
        CourseUpload.of(courses, semester, roster, hidden = CourseTombstoneKeys.hiddenIn(semester, dataCache.loadDeletedCourseNos()))

    private suspend fun reporting(failed: (Exception) -> Unit, send: suspend () -> Unit) {
        try {
            send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e)
        }
    }

    internal companion object {
        private const val TAG = "SchoolUploads"
        private const val ASSIGNMENTS = "assignments"
        private const val COURSES = "courses/"
        private const val REQUESTED = "/requested"

        /**
         * Every upload at once, as before the first-pull gate: the default of a constructor
         * parameter added with it, so a caller built by hand keeps today's timing.
         */
        fun atOnce(dataCache: DataCache, pushApiClient: PushApiClient? = null) =
            SchoolUploads(null, CoroutineScope(EmptyCoroutineContext), dataCache) { assignments, signIn ->
                pushApiClient?.uploadAssignments(assignments, signIn)
            }
    }
}
