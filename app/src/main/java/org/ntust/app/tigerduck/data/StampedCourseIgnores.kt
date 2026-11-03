package org.ntust.app.tigerduck.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncField

/**
 * A `/v4` pull's ignored courses, course by course (LWW D12, [CourseIgnoreReconciler.stamped]),
 * for [CourseIgnoreSync.applySnapshot]. Decided and written under [mutex], the lock every write of
 * the ignored courses and of the changes waiting takes, with the waiting changes read there: a tap
 * kept after the pull's other reads is kept here too. Nothing is resent and no rows are recorded:
 * those were `/v3`'s ways to guess what the backend had lost.
 */
internal class StampedCourseIgnores(
    private val dataCache: DataCache,
    private val queue: CourseIgnoreQueue,
    private val mutex: Mutex,
    private val effects: CourseIgnoreEffects,
) {
    /**
     * Applies [result] under [signIn], but to each course whose field [keeps] names, and not in
     * [resetTerms]; nothing when [takes], read under the lock, says the pull may not. Then each
     * course's numeric Moodle id goes up where the backend lacks it, which is routing, not a
     * choice. Returns the new set when it changed, else null.
     */
    suspend fun apply(
        result: BackendSyncResult,
        signIn: SignIn,
        keeps: (String) -> Boolean,
        resetTerms: Set<String>,
        takes: () -> Boolean,
    ): Set<String>? {
        val updated = mutex.withLock {
            if (!takes()) return null
            val local = dataCache.loadIgnoredCourses()
            val waiting = queue.entries().mapNotNullTo(HashSet()) { it.key }
            CourseIgnoreReconciler.stamped(local, result.courseOverrides, resetTerms) { key ->
                key in waiting || keeps(SyncField.ignore(SyncField.courseKey(CourseIgnores.semesterOf(key), key.substringAfter(':'))))
            }.takeIf { it != local && dataCache.saveIgnoredCourses(it, signIn) }
        }
        if (updated != null) effects.changed()
        queue.fillInMoodleCourseIds(result.courseOverrides, signIn)
        return updated
    }
}
