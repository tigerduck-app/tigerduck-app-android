package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.home.CourseMembership
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * Each copy merged down from the server is first told what its row is from
 * [checked], the snapshot the re-enable check compared
 * ([CourseMembership.followingRows]): on `/v4` Use Local's adds are made now,
 * so a copy of a school row named there would become a hand-add for real.
 * One still not told goes as added by hand, an add made now
 * (`PushCourseSyncBackend.uploadTerm`), as every copy does without a [checked]
 * to tell them by; every upload but Use Local's leaves such a copy out.
 */
internal suspend fun useLocalCourses(
    terms: List<Pair<String, List<Course>>>,
    hiding: Set<String>,
    backend: CourseSyncBackend,
    customNames: Map<String, Map<String, String>>,
    portalCached: (semester: String) -> Boolean,
    latch: UseLocalLatch,
    host: UseLocalHost,
    checked: BackendSyncResult?,
    isCurrent: () -> Boolean,
    now: () -> Long = System::currentTimeMillis,
): Set<String> {
    val reset = mutableSetOf<String>()
    if (checked == null) {
        Log.i("SyncReenable", "[reenable] keep-local courses: no snapshot to tell merged copies by, each goes as added by hand")
    }
    // As read, for the upload to tell a copy a write changed since (CourseSyncBackend.readFor).
    terms.forEach { (semester, courses) -> backend.readFor(semester, courses) }
    val told = if (checked == null) terms else terms.map { (semester, courses) ->
        semester to CourseMembership.followingRows(courses, semester, checked.serverCourses.filter { it.semester == semester })
    }
    try {
        try {
            keepLocalCourses(told, hiding, backend, customNames, portalCached, isCurrent, onReset = reset::add)
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
