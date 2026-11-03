package org.ntust.app.tigerduck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.SelectionAnswer
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.CourseUpload
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What goes up from a school or Moodle fetch: a term's courses, and the assignments. On `/v4` each
 * waits for this process's first pull under its sign-in ([FirstPullGate]), so a roster or a list
 * fetched before the pull is not sent ahead of what the backend holds (`sync-order`, "A refresh
 * reads the backend before it uploads school data"; refactor-v4 D13; iOS `SchoolDataUploader`). On
 * `/v3` none waits: it goes at once, in its caller, as it always did, as `/v3`'s pull hides a
 * fetched school course the backend does not list, so an upload held past the pull would lose it
 * (the coordinator's ruling, PR R fix round 3). The version is read as each upload decides. A
 * local edit never comes here: it goes at once, through its channel's outbox, and so does the
 * full sync's own resend of a term.
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
    /** The API the backend's base speaks now; only `/v4`'s uploads wait for the first pull. */
    private val api: () -> BackendApi,
    private val sendAssignments: suspend (List<Assignment>, SignIn) -> Unit,
) {
    @Inject constructor(
        gate: FirstPullGate,
        @ApplicationScope scope: CoroutineScope,
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
    ) : this(
        gate, scope, dataCache, { BackendApi.of(resolveAnnouncementEndpoint(prefs).url.trimEnd('/')) },
        { assignments, signIn -> pushApiClient.uploadAssignments(assignments, signIn) },
    )

    /** An upload asked for before the first pull, and for a term's, [semester] and the course numbers it [holds]. */
    private class Later(val signIn: SignIn, val semester: String? = null, val holds: Set<String> = emptySet())

    private val lock = Any()

    // Under [lock], by what each sends: the upload waiting for the first pull, the newest asked for,
    // and the one released by it, until it is sent.
    private val waiting = HashMap<String, Later>()
    private val sending = HashMap<String, Later>()

    // Under [lock], by term: the course selection answer the term was last committed with in this
    // process, by a fetch under the sign-in held with it (null for a term committed with none).
    private val committedWith = HashMap<String, Pair<SignIn, SelectionAnswer?>>()

    /**
     * [semester]'s term has just been committed by a fetch of course selection's [answer], or of
     * none, under [signIn]: a waiting term goes with that answer's roster (see [term]). Home's fetch,
     * which uploads no term, tells it here; a fetch that uploads tells it by asking for the upload.
     */
    fun committedFor(signIn: SignIn, semester: String, answer: SelectionAnswer?) = synchronized(lock) {
        committedWith[semester] = signIn to answer
    }

    /** How many uploads wait for the first pull, for a test. */
    internal val waitingUploads: Int get() = synchronized(lock) { waiting.size }

    /**
     * The course numbers of [semester] a fetch committed whose upload waits for the first pull, or
     * goes now it has landed: the backend may not list them yet, so a pull's silence about them is
     * no delete made elsewhere (`CourseSyncReconciler.reconcileSemester`). Read in the term's step.
     */
    fun waitingIn(semester: String): Set<String> = synchronized(lock) {
        (waiting.values + sending.values).filter { it.semester == semester }.flatMapTo(mutableSetOf()) { it.holds }
    }

    /**
     * Runs [now] at once, in the caller, on `/v3`, or when this process has pulled under [signIn]: a
     * caller's cancellation stops it, as it always did. Otherwise [later] runs on the application scope once
     * a pull under [signIn] lands, which a cancelled caller no longer stops: it is not the caller's
     * any more. It does not run once [signIn] has ended, nor once a newer upload of the same [what]
     * was asked for, waiting or sent at once, as the newest has what it would send.
     */
    suspend fun afterFirstPull(signIn: SignIn, what: String, now: suspend () -> Unit, later: suspend (SignIn) -> Unit) =
        afterFirstPull(signIn, what, null, emptySet(), now, later)

    private suspend fun afterFirstPull(
        signIn: SignIn,
        what: String,
        semester: String?,
        holds: Set<String>,
        now: suspend () -> Unit,
        later: suspend (SignIn) -> Unit,
    ) {
        if (gate == null || api() != BackendApi.V4 || gate.hasPulled(signIn)) {
            synchronized(lock) { waiting.remove(what) }
            return now()
        }
        if (!signIn.isCurrent) return
        val mine = Later(signIn, semester, holds)
        synchronized(lock) { waiting[what] = mine }
        scope.launch {
            val pulled = gate.awaitPull(signIn)
            // Taken under the lock: only the newest of its kind goes, and only once.
            val newest = synchronized(lock) {
                val newest = waiting[what] === mine
                if (newest) waiting.remove(what)
                if (newest && pulled) sending[what] = mine
                newest
            }
            if (!pulled || !newest) return@launch
            try {
                later(signIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what after the first pull failed (non-fatal): ${e.logSummary()}")
            } finally {
                synchronized(lock) { if (sending[what] === mine) sending.remove(what) }
            }
        }
    }

    // One send of the assignments at a time, at once or released: see [assignments].
    private val assignmentSends = Mutex()

    /**
     * The assignments a Moodle round or an assignment's page wrote, [written] as written, under
     * [signIn]. Once the gate is open they go at once. Before, what goes once it opens is the
     * assignments as stored then: the newest written, as the uploads of two writes may have
     * waited in either order. Each send, at once or released, holds [assignmentSends], and the
     * released one reads the store under it, so a newer list sent at once never lands before an
     * older one read before it (the term's sends are ordered by the caller's `uploading` the
     * same way). [failed] hears a send that failed, now or then; a cancellation of the caller is
     * no failure, and goes on up.
     */
    suspend fun assignments(written: List<Assignment>, signIn: SignIn, failed: (Exception) -> Unit = {}) = afterFirstPull(
        signIn, ASSIGNMENTS,
        now = { assignmentSends.withLock { reporting(failed) { sendAssignments(written, signIn) } } },
        later = { pinned ->
            assignmentSends.withLock {
                val stored = dataCache.loadAssignments()
                // Signed out while the file was read: what it held is not this sign-in's.
                if (pinned.isCurrent) reporting(failed) { sendAssignments(stored, pinned) }
            }
        },
    )

    /**
     * A school fetch's upload of [semester]'s courses under [signIn], asked for inside the
     * caller's [uploading] block that committed them. Once the gate is open, [send] is handed
     * [committed], the courses as the fetch saved them, as [CourseUpload.of] makes them with
     * [roster], at once. Before, once it opens, it is handed [semester]'s courses as this phone
     * holds them then, the pull's changes included, built under [uploading] again so a reset's
     * DELETE orders with it as with the fetch's own; and only when [stillWanted] says so there (a
     * warm superseded since has nothing to send). A [roster] that is a course selection [answer]'s
     * then goes as the roster of the answer the term was last committed with: this fetch's, unless
     * a fetch that uploads none, such as Home's, committed the term since ([committedFor]). A newer
     * answer whose fetch has not committed yet is no roster of this term, which goes as on dev,
     * where it went at its commit; a later fetch that does upload takes this one's place.
     * [requested] marks the backend's own request for the roster, which waits apart from a fetch's.
     */
    internal suspend fun term(
        signIn: SignIn,
        semester: String,
        committed: List<Course>,
        roster: Collection<String>?,
        uploading: suspend (suspend () -> Unit) -> Unit,
        stillWanted: () -> Boolean = { true },
        requested: Boolean = false,
        answer: SelectionAnswer? = null,
        send: suspend (CourseUpload) -> Unit,
    ) {
        committedFor(signIn, semester, answer)
        afterFirstPull(
            signIn, "$COURSES$semester" + if (requested) REQUESTED else "", semester, committed.mapTo(mutableSetOf()) { it.courseNo },
            now = { send(upload(committed, semester, roster)) },
            later = { pinned ->
                uploading {
                    if (!pinned.isCurrent || !stillWanted()) return@uploading
                    val held = dataCache.loadCourses(semester)
                    val last = synchronized(lock) { committedWith[semester]?.takeIf { it.first === pinned } }
                    val paired = if (answer == null || last == null) roster else last.second?.courseNos
                    if (held.isNotEmpty() && pinned.isCurrent) send(upload(held, semester, paired))
                }
            },
        )
    }

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
            SchoolUploads(null, CoroutineScope(EmptyCoroutineContext), dataCache, { BackendApi.V3 }) { assignments, signIn ->
                pushApiClient?.uploadAssignments(assignments, signIn)
            }
    }
}
