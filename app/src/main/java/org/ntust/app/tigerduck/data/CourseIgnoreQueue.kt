package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The queue half of [CourseIgnoreSync]: a change kept in `pending_course_ignores.json` and sent,
 * the retries, the refusals and the parking, and the numeric Moodle id's fill-in. It shares the
 * sync's lock over both files ([mutex]) and its record of when each key last changed
 * ([touchedAt]), which the snapshot half reads.
 */
internal class CourseIgnoreQueue(
    private val dataCache: DataCache,
    private val sender: CourseIgnoreSender,
    private val effects: CourseIgnoreEffects,
    private val sends: () -> Boolean,
    private val clock: () -> Long,
    private val mutex: Mutex,
    private val touchedAt: HashMap<String, Long>,
) {
    // One PATCH at a time, so changes reach the backend in the order they
    // were made: the backend keeps whichever request lands last.
    private val sendMutex = Mutex()

    // Whether this process has given the parked changes their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    /**
     * Ignores, or stops ignoring, every assignment of [semester]'s
     * [courseNo]: here at once, then on the backend. [onWritten] runs once
     * the file has the change, or the write was dropped, before the backend
     * is asked.
     */
    suspend fun setIgnored(
        semester: String,
        courseNo: String,
        moodleId: String,
        moodleCourseId: Int?,
        ignore: Boolean,
        signIn: SignIn,
        onWritten: () -> Unit = {},
    ) {
        val change = PendingCourseIgnore(semester, courseNo, moodleId, moodleCourseId, ignore, clock())
        val key = CourseIgnores.key(semester, courseNo)
        val written = mutex.withLock {
            val keys = dataCache.loadIgnoredCourses()
            val pending = dataCache.loadPendingCourseIgnores()
            if (!dataCache.saveIgnoredCourses(if (ignore) keys + key else keys - key, signIn)) {
                return@withLock false
            }
            dataCache.savePendingCourseIgnores(pending.with(change), signIn)
            touchedAt[key] = change.changedAtMs
            true
        }
        onWritten()
        if (!written) return
        effects.changed()
        send(change, signIn)
    }

    /**
     * Sends every change still waiting, oldest first, but a parked one. One
     * that fails waits for the next sync. The first run in a process gives
     * each parked change one more try, as every full sync on iOS does: here a
     * full sync runs before every pull and hourly in the background, so
     * trying a parked change at each would send a refused one without end.
     */
    suspend fun retryPending(signIn: SignIn) {
        if (!sends()) return
        if (parkedRequeued.compareAndSet(false, true)) requeueParked(signIn)
        dataCache.loadPendingCourseIgnores()
            .filterNot { it.isParked }
            .sortedBy { it.changedAtMs }
            .forEach { send(it, signIn) }
    }

    /** Leaves each parked change one attempt, as `SyncOutbox.requeueParked` on iOS does. */
    private suspend fun requeueParked(signIn: SignIn) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        if (pending.none { it.isParked }) return@withLock
        dataCache.savePendingCourseIgnores(
            pending.map { if (it.isParked) it.copy(attempts = CourseIgnoreSync.MAX_ATTEMPTS - 1) else it },
            signIn,
        )
    }

    suspend fun send(change: PendingCourseIgnore, signIn: SignIn) = sendMutex.withLock {
        if (!sends()) return@withLock
        val moodleId = change.moodleId ?: return@withLock
        // Gone from the file: confirmed already, replaced by a later change,
        // or wiped with the account that made it. Sending it would undo
        // whatever replaced it. A parked one waits for its one more try.
        val current = mutex.withLock {
            signIn.isCurrent &&
                dataCache.loadPendingCourseIgnores().any { it.isSameChange(change) && !it.isParked }
        }
        if (!current) return@withLock
        val stored = try {
            sender.send(moodleId, change.ignore, change.moodleCourseId, signIn, change.madeAtMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course ignore PATCH failed: ${e.logSummary()}")
            if (CourseIgnoreSync.refuses(e)) false else null
        }
        when (stored) {
            true -> settle(change, signIn)
            false -> countRefusal(change, signIn)
            null -> Unit
        }
    }

    private suspend fun settle(change: PendingCourseIgnore, signIn: SignIn) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        if (pending.none { it.isSameChange(change) }) return@withLock
        if (dataCache.savePendingCourseIgnores(pending.filterNot { it.isSameChange(change) }, signIn)) {
            change.key?.let { touchedAt[it] = clock() }
        }
    }

    private suspend fun countRefusal(change: PendingCourseIgnore, signIn: SignIn) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        val stored = pending.find { it.isSameChange(change) } ?: return@withLock
        val counted = stored.copy(attempts = stored.attempts + 1)
        if (counted.isParked) Log.w(TAG, "course ignore parked after ${CourseIgnoreSync.MAX_ATTEMPTS} refusals")
        dataCache.savePendingCourseIgnores(pending.map { if (it === stored) counted else it }, signIn)
    }

    /**
     * A course the backend ignores but cannot match to the assignments it
     * fetches itself, for want of the numeric Moodle id, gets the id from
     * this device when it knows it.
     */
    suspend fun fillInMoodleCourseIds(overrides: List<CourseOverrideResult>, signIn: SignIn) {
        val missing = overrides.filter { it.ignoreAssignments == true && it.moodleNumericCourseId == null }
        if (missing.isEmpty()) return
        val known = dataCache.loadMoodleCourseIds()
            .mapKeys { MoodleCourseIds.normalizedIdnumber(it.key) }
        for (o in missing) {
            val semester = o.semester ?: continue
            val courseNo = o.courseNo ?: continue
            val numericId = dataCache.loadCourses(semester)
                .find { it.courseNo == courseNo }
                ?.moodleNumericCourseId
                ?: known[MoodleCourseIds.normalizedIdnumber(o.moodleCourseId)]
                ?: continue
            sendMutex.withLock {
                if (!signIn.isCurrent) return
                try {
                    sender.send(o.moodleCourseId, null, numericId, signIn, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "moodle_course_id fill-in failed for $semester: ${e.logSummary()}")
                }
            }
        }
    }

    private val PendingCourseIgnore.isParked: Boolean
        get() = attempts >= CourseIgnoreSync.MAX_ATTEMPTS

    private companion object {
        const val TAG = "CourseIgnoreSync"
    }
}

/** [this] with [change] in place of the change waiting for its course, which goes last. */
internal fun List<PendingCourseIgnore>.with(change: PendingCourseIgnore) =
    filterNot { it.key == change.key } + change
