package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicBoolean

/** The course requests Use Local sends, so a test can stand in for the backend. */
internal interface CourseSyncBackend {
    /** `DELETE /sync/courses?semester=`: a reset of one term, never of every term. */
    suspend fun deleteAllCourses(semester: String)
    suspend fun uploadCourses(courses: List<Course>, semester: String)
    suspend fun deleteCourse(courseKey: String)

    /** `PATCH /sync/courses/{moodle_id}/override` with one locale's custom name. */
    suspend fun patchCustomName(moodleId: String, name: String, locale: String)
}

/**
 * Course sync went off while Use Local was sending. Nothing is asked then:
 * turning course sync back on marks the question again.
 */
internal class CourseSyncOffException : IllegalStateException("course sync is off")

/**
 * [CourseSyncBackend] over [PushApiClient], whose course requests return
 * quietly unsent with cloud or course sync off. Use Local's fail instead, so
 * it stops rather than follow a term reset with an upload that never went.
 * The names go through [PendingCourseOverrides], as every name PATCH does.
 */
internal class PushCourseSyncBackend(
    private val api: PushApiClient,
    private val prefs: AppPreferences,
    private val overrides: PendingCourseOverrides,
) : CourseSyncBackend {
    private fun ensureSends() {
        if (!prefs.cloudSyncEnabled || !prefs.syncCourses) throw CourseSyncOffException()
    }

    override suspend fun deleteAllCourses(semester: String) {
        ensureSends()
        api.deleteAllCourses(semester)
    }

    override suspend fun uploadCourses(courses: List<Course>, semester: String) {
        ensureSends()
        // Null: switched off between the check and the request.
        api.uploadCourses(courses, semester) ?: throw CourseSyncOffException()
    }

    override suspend fun deleteCourse(courseKey: String) {
        ensureSends()
        api.deleteCourse(courseKey)
    }

    override suspend fun patchCustomName(moodleId: String, name: String, locale: String) {
        ensureSends()
        // Through the outbox: kept until it lands, and in place of an older
        // name of the course still waiting, which would land over this one.
        overrides.send(moodleId, customName = name, locale = locale)
    }
}

/**
 * Use Local for courses, after course sync is switched back on, term by term
 * (tigerduck-app/tigerduck-app#235 on iOS, tigerduck-app-android#153):
 *
 * 1. Reset a term this phone caches courses for. Every tombstone in it
 *    becomes this phone's reset tombstone.
 * 2. Upload the term's cached courses, hand-typed ones included. The upload
 *    releases those tombstones.
 * 3. Delete every course hidden here in the term. A single-course delete
 *    leaves a tombstone that binds every device, this one included, so the
 *    roster a later refresh uploads cannot bring the course back. The
 *    backend writes it only for a row it has, so a hidden course the cache
 *    lacks goes up in step 2 as a stub named by its course number. A term
 *    with hidden courses and none cached gets steps 2 and 3 without the
 *    reset.
 *
 * [terms] maps each semester to its cached courses; [hiding] is the
 * deleted-course store, where a legacy bare entry hides its course in every
 * term. [customNames] (by course number, then locale; empty with name sync
 * off) go up again for every course of a term that was reset, which deletes
 * them on the server along with the course rows. A term whose portal rows
 * the cache has lost ([portalCached] false, see `DataCache.hasPortalCourses`)
 * is not reset: its hand-typed courses go up and its hidden ones are deleted. Not a full reset: that erases every term's tombstones and stamps
 * `courses_reset_at`, which this phone's next sync then took for a reset made
 * elsewhere, dropping its hidden and hand-typed courses.
 *
 * Every request is awaited and a failure is thrown, for the caller to ask
 * again. Once [isCurrent] turns false, as at a sign-out, nothing more is sent
 * and it throws [CancellationException]. [onReset] hears of each term reset
 * once its upload has landed.
 */
internal suspend fun keepLocalCourses(
    terms: List<Pair<String, List<Course>>>,
    hiding: Set<String>,
    backend: CourseSyncBackend,
    customNames: Map<String, Map<String, String>> = emptyMap(),
    portalCached: (semester: String) -> Boolean = { true },
    isCurrent: () -> Boolean = { true },
    onReset: (semester: String) -> Unit = {},
) {
    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("signed out during Use Local")
    }
    for ((semester, courses) in terms) {
        val hiddenNos = CourseTombstoneKeys.hiddenIn(semester, hiding)
        val cachedNos = courses.mapTo(mutableSetOf()) { it.courseNo }
        val stubs = (hiddenNos - cachedNos).sorted().map { Course(courseNo = it, courseName = it) }
        if (courses.isEmpty() && stubs.isEmpty()) continue
        // Without the term's portal rows here, a reset would leave the server
        // with the hand-typed ones alone, and every other device without the
        // term's enrolled courses.
        val reset = courses.isNotEmpty() && portalCached(semester)
        if (reset) {
            ensureCurrent()
            backend.deleteAllCourses(semester)
        }
        ensureCurrent()
        backend.uploadCourses(courses + stubs, semester)
        if (reset) onReset(semester)
        for (courseNo in hiddenNos.sorted()) {
            ensureCurrent()
            backend.deleteCourse("client:$semester:$courseNo")
        }
        if (reset) {
            // The reset took the term's override rows, names included, with
            // its course rows; colours ride the upload, names do not. After
            // the deletes, so a name that fails to go up leaves no hidden
            // course on the server.
            for (course in courses.filter { it.courseNo !in hiddenNos }.sortedBy { it.courseNo }) {
                val names = customNames[course.courseNo] ?: continue
                // The id the upload stored the row under, as the backend
                // derives it; see CourseUploadPayload.
                val moodleId = course.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: "$semester${course.courseNo}"
                for ((locale, name) in names.toSortedMap()) {
                    if (name.isEmpty()) continue
                    ensureCurrent()
                    backend.patchCustomName(moodleId, name, locale)
                }
            }
        }
    }
}

/**
 * What Use Local needs around its requests: the reset latch and stamps of
 * `DataCache` (see `DataCache.beginReset`), and a way to ask the question
 * again.
 */
internal interface UseLocalHost {
    /** Latches [semester] as mid-reset; false when a class-table reset already holds it. */
    fun beginReset(semester: String): Boolean
    fun endReset(semester: String)
    suspend fun stampReset(semester: String, atMs: Long)

    /** Marks courses for the re-enable check again. */
    fun askAgain()
}

/**
 * Use Local's latch on its terms (`DataCache.beginReset`), which keeps full
 * syncs off them until it has sent its requests. A term a class-table reset
 * already holds is left to that reset. Released once: a second [release]
 * would let go of a reset that latched the term since.
 */
internal class UseLocalLatch(val semesters: List<String>, private val host: UseLocalHost) {
    val held: List<String> = semesters.filter(host::beginReset)
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) held.forEach(host::endReset)
    }
}

/**
 * Lets the courses re-enable check's hold on the course list go
 * ([releaseHold]) once the user has answered it, latching Use Local's terms
 * first when [useLocal]: a sync between the two would reconcile the course
 * list and un-hide a course deleted here, which the server still lists,
 * before Use Local could send its delete. [semesters] must be cheap and
 * synchronous: this runs on the tap.
 */
internal fun handOverCourseHold(
    useLocal: Boolean,
    semesters: () -> List<String>,
    host: UseLocalHost,
    releaseHold: () -> Unit,
): UseLocalLatch? {
    val latch = if (useLocal) UseLocalLatch(semesters(), host) else null
    releaseHold()
    return latch
}

/**
 * [keepLocalCourses] over every term in [terms], latched as a class-table
 * reset's are and stamped as reset when it ends, so a sync that read a term
 * mid-way does not un-hide the courses the upload puts back before their
 * deletes. A failure asks again ([UseLocalHost.askAgain]): until a term's
 * upload lands, the server holds none of its courses, and the reset's
 * tombstones hide them on every other device. Stopped by a sign-out, it asks
 * and stamps nothing for the next account; the [latch], taken on the tap by
 * [handOverCourseHold], goes either way. Returns the terms it reset and
 * uploaded again, a failure's included, for the course ignores to follow
 * (`CourseIgnoreSync.pushLocal`).
 */
internal suspend fun useLocalCourses(
    terms: List<Pair<String, List<Course>>>,
    hiding: Set<String>,
    backend: CourseSyncBackend,
    customNames: Map<String, Map<String, String>>,
    portalCached: (semester: String) -> Boolean,
    latch: UseLocalLatch,
    host: UseLocalHost,
    now: () -> Long,
    isCurrent: () -> Boolean,
): Set<String> {
    val reset = mutableSetOf<String>()
    try {
        try {
            keepLocalCourses(terms, hiding, backend, customNames, portalCached, isCurrent, onReset = reset::add)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CourseSyncOffException) {
            // Not asked now, with course sync off: turning it back on marks
            // the question again by itself.
            Log.w("SyncReenable", "[reenable] keep-local courses stopped: course sync went off")
        } catch (e: Exception) {
            Log.w("SyncReenable", "[reenable] keep-local courses failed, asking again: ${e.logSummary()}")
            if (isCurrent()) host.askAgain()
        }
        // Before the latch goes, so no sync reads a term between the two.
        val doneAt = now()
        for (semester in latch.held) host.stampReset(semester, doneAt)
    } finally {
        latch.release()
    }
    return reset
}
