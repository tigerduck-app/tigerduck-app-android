package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.shared.Course
import java.security.MessageDigest

/**
 * How often an unchanged list goes to the server again. The backend upserts
 * every row of an upload, but an unchanged one moves nothing, so skipping it
 * loses only the server's bookkeeping: `last_seen_at`, a log row, and its
 * sync push-back, which defers the server's own Moodle sync by two hours per
 * upload (tigerduck-app-android#220). The iPhone app's
 * `PushCoordinator.unchangedUploadInterval`.
 */
internal const val UNCHANGED_UPLOAD_INTERVAL_MS = 6 * 60 * 60 * 1000L

/**
 * How long a list the server turned away, with an HTTP error, waits before it goes again. Not
 * recorded, it went again on every fetch, sync and push that came along, each answered the same
 * way. A list sent with `always` does not wait.
 */
internal const val REFUSED_UPLOAD_PAUSE_MS = 60_000L

/**
 * Whether a list with [fingerprint] goes up: a list other than the one the
 * server last accepted ([acceptedFingerprint], at [acceptedAtMs]), or the same
 * one once [UNCHANGED_UPLOAD_INTERVAL_MS] has passed, or a clock that moved
 * back past the acceptance.
 */
internal fun uploadIsDue(acceptedFingerprint: String?, acceptedAtMs: Long, fingerprint: String, nowMs: Long): Boolean {
    if (acceptedFingerprint != fingerprint) return true
    val age = nowMs - acceptedAtMs
    return age >= UNCHANGED_UPLOAD_INTERVAL_MS || age < 0
}

/**
 * A digest of [json], a list serialised in a fixed order, as sent to [backend] (the base URL):
 * the same list to the same backend gives the same fingerprint. The ledger remembers what one
 * backend accepted, and pointed at another (the debug endpoint override) the app owes it the
 * list again; an upload still out across the switch records the fingerprint of where it went.
 */
internal fun uploadFingerprint(backend: String, json: String): String =
    MessageDigest.getInstance("SHA-256").digest("$backend\n$json".toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * A term's course list as a fingerprint: the body [PushApiClient.uploadCourses] sends for it to
 * [backend], without force keys (a list with any goes whatever the ledger holds), the courses in
 * course-number order, so the roster as the class table holds it and as a sync holds it, the
 * same courses in another order, are one list. The term is the ledger's key, not part of this.
 *
 * [rosterTerms] is part of it: the same courses sent as the term's roster are another list, so
 * the first fetch after an update that sends rosters sends each term once as one, unchanged or
 * not, which drops what the student left since. As iOS `PushCoordinator.fingerprint`
 * (tigerduck-app#914).
 */
internal fun courseUploadFingerprint(
    backend: String,
    courses: List<Course>,
    semester: String,
    rosterTerms: List<String> = emptyList(),
): String =
    uploadFingerprint(
        backend,
        fingerprintGson.toJson(
            CourseUploadPayload.build(courses.sortedBy { it.courseNo }, semester, rosterTerms = rosterTerms.sorted()),
        ),
    )

private val fingerprintGson = Gson()

/** Where the list the server last accepted is kept; AppPreferences in the app. */
internal interface UploadLedgerStore {
    val fingerprint: String?
    val acceptedAtMs: Long
    fun record(fingerprint: String, atMs: Long)
}

/**
 * Sends a list only when [uploadIsDue] says so, one upload at a time, so the
 * next one's check sees what the last one left. Kept on disk, unlike the
 * iPhone app's in-memory ledger: the background worker runs in a process
 * Android starts afresh as often as not, which would send every run again.
 * Sign-out and a sync switch coming back on forget it
 * (`AppPreferences.forgetUploads`); a term reset forgets that term's course
 * list (`AppPreferences.forgetCourseUploads`), under [holdingUploads].
 */
internal class UploadLedger(
    private val store: UploadLedgerStore,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()

    /**
     * Under [mutex]: each list the server turned away in the last
     * [REFUSED_UPLOAD_PAUSE_MS], by the sign-in it was sent under and its
     * fingerprint, with when the answer came.
     */
    private val refused = HashMap<String, Long>()

    /**
     * Runs [send] for a list with [fingerprint] when it is due, or whatever
     * the ledger holds when [always] (a forced re-add, a term the server has
     * emptied), and once [send] returns records it as accepted through
     * [ifCurrent], which runs the record it is handed only while the sign-in
     * the upload began under still stands, and under the lock a sign-out
     * moves it under (`AuthTokenManager.runIfSignedIn`). Checking first and
     * recording after left a gap a sign-out fell into: its clearing of the
     * ledger landed between the two, and the departing account's list stayed
     * for the next account to skip. Returns whether it sent. A [send] that
     * throws records nothing; one the server turned away
     * ([PushApiException.statusCode]) holds the same list back for
     * [REFUSED_UPLOAD_PAUSE_MS] from the answer, unless [always], while
     * [signIn], the sign-in it was sent under, stands.
     */
    suspend fun sendIfDue(
        fingerprint: String,
        ifCurrent: (record: () -> Unit) -> Unit,
        always: Boolean = false,
        signIn: Int = 0,
        send: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            val now = clock()
            val refusalKey = "$signIn:$fingerprint"
            // Measured from the refusal, so a clock set back does not stretch the pause.
            refused.entries.removeAll { (_, at) -> now - at !in 0 until REFUSED_UPLOAD_PAUSE_MS }
            if (!always) {
                if (!uploadIsDue(store.fingerprint, store.acceptedAtMs, fingerprint, now)) return@withLock false
                if (refusalKey in refused) return@withLock false
            }
            try {
                send()
            } catch (e: PushApiException) {
                if (e.statusCode != null) refused[refusalKey] = clock()
                throw e
            }
            ifCurrent { store.record(fingerprint, now) }
            true
        }

    /**
     * Runs [block] with no upload of the list in flight, and none starting
     * until it returns. For a term reset, which forgets the list in it: an
     * upload out across the reset would record, after it, a list the reset
     * has just taken off the server, and the upload that follows the reset,
     * usually that same list, would then wait six hours while the server
     * holds the reset's tombstones. It forgets the lists the server turned
     * away, too, for the same reason.
     */
    suspend fun <T> holdingUploads(block: suspend () -> T): T = mutex.withLock {
        block().also { refused.clear() }
    }
}
