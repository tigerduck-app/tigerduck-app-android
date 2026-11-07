package org.ntust.app.tigerduck.data

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.QueueFile
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.CourseRef
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.EntryFloors
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.OnDiskAttempts
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.sync.outbox.UntimedEntries
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Sends a course's setting and numeric Moodle id with the answer; see `PushApiClient.patchCourseIgnoreAnswered`. */
internal fun interface CourseIgnoreWriter {
    suspend fun write(target: CourseRef, ignore: Boolean?, moodleCourseId: Int?, signIn: SignIn, madeAt: EditTime?): WriteAnswer
}

/**
 * The scope of a queue a test builds without the application's: each request runs on its caller's
 * dispatcher ([from]), as before the queue was a channel, so a test's dispatcher orders them.
 */
internal class CallersScope : CoroutineScope {
    private val job = SupervisorJob()

    @Volatile
    private var dispatcher: CoroutineContext = Dispatchers.Unconfined

    override val coroutineContext: CoroutineContext get() = job + dispatcher

    /** Runs [block], its requests on the caller's dispatcher. */
    suspend fun from(block: suspend () -> Unit) {
        currentCoroutineContext()[ContinuationInterceptor]?.let { dispatcher = it }
        block()
    }
}

/** The clocks and the outbox a course ignore queue is built with: see [CourseIgnoreQueue]. */
internal class IgnoreOutbox(
    /** The clocks a change's time is read by. */
    val clocks: DeviceClocks,
    val ack: AckClock,
    val scope: CoroutineScope,
    val oldEntryTime: OldEntryTime?,
)

/**
 * The queue half of [CourseIgnoreSync], the outbox's course ignores channel: a change kept in
 * `pending_course_ignores.json` and sent, the retries, the refusals and the parking, and the
 * numeric Moodle id's fill-in. It shares the sync's lock over both files ([mutex]).
 *
 * The changes wait in an [OutboxLane] over the queue's own file, one per course
 * ([SyncField.ignore]), oldest first, one PATCH at a time, through the answered form. A change the
 * backend refuses [CourseIgnoreSync.MAX_ATTEMPTS] times is parked, as before: it keeps its value,
 * and only the first retry of a process sends it again. A change goes by its course's key with its
 * age (`age_ms`) from when it was made, one an older build wrote with no time from its old-entry
 * time (X1), and a reply that says the backend kept a later setting puts it on this phone unless a
 * newer change waits (LWW D12). The fill-in is sent as it is made: 0 old.
 */
internal class CourseIgnoreQueue(
    private val dataCache: DataCache,
    private val writer: CourseIgnoreWriter,
    private val effects: CourseIgnoreEffects,
    private val sends: () -> Boolean,
    private val clock: () -> Long,
    private val mutex: Mutex,
    private val outbox: IgnoreOutbox,
) : OutboxChannel {
    // A PATCH of the queue's at a time with the fill-in's, which is no lane's.
    private val sendMutex = Mutex()

    // Whether this process has given the parked changes their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    override val id: OutboxChannelId get() = OutboxChannelId.COURSE_IGNORES

    private val lane: OutboxLane<PendingCourseIgnore> = OutboxLane(
        OutboxChannelId.COURSE_IGNORES,
        object : LaneStore<PendingCourseIgnore> {
            // Oldest first, as the retries have always sent them.
            override suspend fun load(): List<PendingCourseIgnore> = dataCache.loadPendingCourseIgnores().sortedBy { it.changedAtMs }

            override suspend fun save(entries: List<PendingCourseIgnore>, signIn: SignIn): Boolean =
                dataCache.savePendingCourseIgnores(entries, signIn)
        },
        LanePolicy(
            field = ::fieldOf,
            maxAttempts = CourseIgnoreSync.MAX_ATTEMPTS,
            attempts = OnDiskAttempts(
                attempts = { it.attempts },
                counted = { change, attempts, _ -> change.copy(attempts = attempts) },
                // The whole change, so the course too: the lane asks it of changes of any course.
                sameEdit = { a, b -> a.isSameChange(b) },
            ),
            untimed = UntimedEntries(
                timeOf = { change -> change.madeAtMs },
                withTime = { change, atMs -> change.copy(madeAtMs = atMs) },
                oldEntryTimeMs = { outbox.oldEntryTime?.oldEntryTimeMs(dataCache.queueFile(QueueFile.COURSE_IGNORES)) ?: 0L },
            ),
            floors = EntryFloors(PendingCourseIgnore::madeAt, { it.madeAtClocks?.floor?.time }, PendingCourseIgnore::withFloor),
        ),
        outbox.ack,
        outbox.scope,
        ::sendNow,
    )

    /** A change of [semester]'s [courseNo] made now: its time as the clock reads it, and by the device's clocks. */
    fun changeNow(semester: String, courseNo: String, moodleId: String, moodleCourseId: Int?, ignore: Boolean): PendingCourseIgnore {
        val madeAt = madeAt()
        return PendingCourseIgnore(semester, courseNo, moodleId, moodleCourseId, ignore, clock(), madeAtMs = madeAt.wallMs, madeAtClocks = EditClocks.of(madeAt))
    }

    /** The time of every change of a batch, taken once for the batch, by the device's clocks. */
    fun madeAt(): EditTime = outbox.clocks.now()

    /**
     * A change of the course [key] names to [ignore], made at [now] and timed [madeAt], its batch's, by
     * the Moodle id the backend's row [serverRow] has, else this phone's course's, else the one the
     * backend keys a course added by hand by.
     */
    suspend fun changeFor(
        key: String,
        ignore: Boolean,
        serverRow: CourseOverrideResult?,
        now: Long,
        madeAt: EditTime,
    ): PendingCourseIgnore {
        val semester = CourseIgnores.semesterOf(key)
        val courseNo = key.substringAfter(':')
        val course = dataCache.loadCourses(semester).find { it.courseNo == courseNo }
        val moodleId = serverRow?.moodleCourseId
            ?: course?.moodleIdNumber?.takeIf { it.isNotEmpty() }
            ?: "$semester$courseNo"
        return PendingCourseIgnore(
            semester = semester,
            courseNo = courseNo,
            moodleId = moodleId,
            moodleCourseId = serverRow?.moodleNumericCourseId ?: course?.moodleNumericCourseId,
            ignore = ignore,
            changedAtMs = now,
            madeAtMs = madeAt.wallMs,
            madeAtClocks = EditClocks.of(madeAt),
        )
    }

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
        val change = changeNow(semester, courseNo, moodleId, moodleCourseId, ignore)
        val key = CourseIgnores.key(semester, courseNo)
        // Under way from before it is kept (a drain running that sends it counts for it); both files as one.
        draining {
            lane.keepAndDrain(signIn, sends) {
                val written = mutex.withLock {
                    val keys = dataCache.loadIgnoredCourses()
                    if (!dataCache.saveIgnoredCourses(if (ignore) keys + key else keys - key, signIn)) {
                        return@withLock false
                    }
                    lane.edit(signIn) { it.with(change) }
                    true
                }
                onWritten()
                if (!written) return@keepAndDrain null
                effects.changed()
                setOf(fieldOf(change))
            }
        }
    }

    /**
     * Keeps [change] at the oldest time there is ([EditTime.OLDEST]), unless a change of its course waits: a
     * setting this phone held before the category ever synced, which fills only a field the
     * backend holds no stamp for (LWW D13), as a category's first sync queues it. Sends nothing.
     * False once [signIn] has ended.
     */
    suspend fun queueAsOldest(change: PendingCourseIgnore, signIn: SignIn): Boolean = mutex.withLock {
        lane.edit(signIn) { rows -> if (rows.any { it.key == change.key }) rows else rows.with(change.copy(madeAtMs = EditTime.OLDEST.wallMs, madeAtClocks = null)) }
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
        if (signIn.ifCurrent { parkedRequeued.compareAndSet(false, true) } == true) lane.requeueParked(signIn)
        draining { lane.drain(signIn) }
    }

    // [drain], its requests on the caller's dispatcher where the queue has no application scope.
    private suspend fun draining(drain: suspend () -> Unit) {
        val callers = outbox.scope as? CallersScope
        if (callers == null) drain() else callers.from(drain)
    }

    /** The changes waiting, parked ones included, oldest first. */
    suspend fun entries(): List<PendingCourseIgnore> = lane.entries()

    /**
     * Writes [change] of the changes waiting under [signIn], in one step with the lane's own
     * writes; under [mutex], as every write of both files. False once [signIn] has ended.
     */
    suspend fun edit(signIn: SignIn, change: (List<PendingCourseIgnore>) -> List<PendingCourseIgnore>): Boolean = lane.edit(signIn, change)

    /** [retryPending]. */
    override suspend fun drain(signIn: SignIn) = retryPending(signIn)

    /** Nothing: a course upload never gave a parked ignore its try, which follows its own rule ([retryPending]). */
    override suspend fun requeueParked(signIn: SignIn) = Unit

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    override suspend fun raiseFloors(key: String, start: EditTime, end: EditTime, signIn: SignIn) {
        lane.raiseFloors(key, start, end, signIn)
    }

    override suspend fun floorOf(key: String, madeAt: EditTime): EditTime? = lane.floorOf(key, madeAt)

    /** At a sign-out: the next student's parked changes get their own one more try in this process. */
    override fun forgetMemory() {
        parkedRequeued.set(false)
        lane.forgetMemory()
    }

    // The lane's send of [change] under [signIn], read as it was about to go: what became of it.
    private suspend fun sendNow(change: PendingCourseIgnore, signIn: SignIn): SendResult {
        if (!sends()) return SendResult.NotSent
        // A row with no Moodle id is too damaged to tell its course by ([PendingCourseIgnore.key]),
        // which no build writes: it waits, as it always has.
        if (change.moodleId == null) return SendResult.NotSent
        val answer = try {
            sendMutex.withLock {
                // Read again once the lock is had, which the fill-in keeps across its own request: a
                // change taken back (Use Server, a forget) meanwhile waits, and so does one held (a
                // re-enable check) by then, the gate asked last, right before the request.
                val current = mutex.withLock {
                    signIn.isCurrent && lane.entries().any { it.isSameChange(change) && it.attempts < CourseIgnoreSync.MAX_ATTEMPTS }
                }
                if (!current || !sends()) return SendResult.NotSent
                writer.write(CourseRef(courseKeyOf(change)), change.ignore, change.moodleCourseId, signIn, change.madeAt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course ignore PATCH failed: ${e.logSummary()}")
            return SendResult.afterFailure(e)
        }
        if (!answer.sent) return SendResult.NotSent
        // A backend that predates the field drops it unread: not stored, a refusal.
        if (IGNORE !in answer.values) return SendResult.Refused
        takeWinner(change, answer, signIn)
        return SendResult.Sent(answer.serverClockUs)
    }

    /**
     * LWW D12, a write response's winner: when the backend kept a later setting of [change]'s
     * course, that setting goes on this phone, unless a newer change of the course waits, whose
     * own answer settles it, or this phone's setting is no longer the one the reply answered.
     * Decided and written in one step under [mutex], which every write of the ignored courses
     * takes, so a tap kept meanwhile stays.
     */
    private suspend fun takeWinner(change: PendingCourseIgnore, answer: WriteAnswer, signIn: SignIn) {
        val applied = answer.applied ?: return
        if (IGNORE in applied) return
        val winner = answer.values[IGNORE] as? Boolean ?: return
        val key = change.key ?: return
        val changed = mutex.withLock {
            if (!signIn.isCurrent || lane.entries().any { it.key == key && !it.isSameChange(change) }) return@withLock false
            val keys = dataCache.loadIgnoredCourses()
            if ((key in keys) != change.ignore || winner == change.ignore) return@withLock false
            dataCache.saveIgnoredCourses(if (winner) keys + key else keys - key, signIn)
        }
        if (changed) effects.changed()
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
                    writer.write(CourseRef(SyncField.courseKey(semester, courseNo)), null, numericId, signIn, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "moodle_course_id fill-in failed for $semester: ${e.logSummary()}")
                }
            }
        }
    }

    // The course's key, which a change always names its term and number for.
    private fun courseKeyOf(change: PendingCourseIgnore): String? {
        val semester = change.semester ?: return null
        val courseNo = change.courseNo ?: return null
        return SyncField.courseKey(semester, courseNo)
    }

    // The field the change edits: whether its course's assignments are ignored.
    private fun fieldOf(change: PendingCourseIgnore): String = SyncField.ignore(courseKeyOf(change).orEmpty())

    private companion object {
        const val TAG = "CourseIgnoreSync"

        // The reply's field (`docs/api-v4.md`, "Course override"), and its name in `applied`.
        const val IGNORE = "ignore_assignments"
    }
}

/** [this] with [change] in place of the change waiting for its course, which goes last. */
internal fun List<PendingCourseIgnore>.with(change: PendingCourseIgnore) =
    filterNot { it.key == change.key } + change

/** The course ignores join the outbox's channels (`OutboxModule`). */
@Module
@InstallIn(SingletonComponent::class)
object CourseIgnoresChannelModule {
    @Provides
    @IntoSet
    fun channel(ignores: CourseIgnoreSync): OutboxChannel = ignores.channel
}
