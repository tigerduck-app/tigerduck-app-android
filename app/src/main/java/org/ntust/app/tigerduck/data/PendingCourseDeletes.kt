package org.ntust.app.tigerduck.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.InMemoryAttempts
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes one course's row from the backend by its key, as sign-in [signIn], with the age of the
 * time the hide was asked ([hiddenAt]); see [PushApiClient.deleteCourseAnswered].
 */
internal fun interface CourseDeleter {
    suspend fun delete(courseKey: String, signIn: SignIn, hiddenAt: EditTime)
}

/**
 * Courses deleted on the class table whose backend DELETE has not landed,
 * by term, each with the moment it was asked ([stamp]): the outbox's course deletes channel.
 *
 * A delete went once. A course taken back out of Add course
 * (`ClassTableViewModel.removeAddedCourse`) writes no tombstone here, so a
 * failed DELETE left its row on the server for the next full sync to merge
 * back as a hand-added course; a regular delete's tombstone did not help
 * either, as the reconcile un-hides a course the server still lists. Now the
 * delete waits on disk, kept before anything else so a process killed while
 * it waits for the lock or during the request keeps it too, every full sync
 * sends it again first, as [PendingCourseAdds] does for adds, and the
 * reconcile keeps the course out of the term until it lands
 * (`CourseMembership.reconcileTerm`'s `keeps`). It goes
 * once the DELETE lands, once the course is added back (the add's force key
 * would otherwise lose to a DELETE landing after it), once its term is
 * reset, which deletes the term's rows itself, or once a full sync reads a
 * course reset made on another device, which brought the course back
 * ([forgetAskedBefore]). Account-scoped: kept with the
 * user data and wiped with it at sign-out, and each request pinned to the
 * sign-in it was read under, as the adds are, so a sign-out and another
 * sign-in while it waits for its header cannot send it as the next account.
 *
 * Every request holds the lock the adds and term resets take,
 * [PendingCourseAdds.removing], and reads the delete again under it right
 * before sending: a retry that read a delete, then waited behind a re-add's
 * upload or a reset, must not take the restored course off the server
 * afterwards. So the re-add's [forget] takes that lock too, and a reset
 * calls [forgetTerm] inside the lock its wipe holds. A re-add and a removal
 * compare by when each was asked, not by when each reached the lock: [forget]
 * lets go only of a delete asked before the re-add, so a course removed again
 * while its first DELETE is still under way stays queued once that DELETE
 * fails, and so a delete can be kept on disk before its wait for the lock.
 * A new action outranks every delete already queued, a process restart and a
 * clock set back included: the stamps start above the largest one kept.
 *
 * The deletes wait in an [OutboxLane] over the queue's own file ([CourseDeletesStore]), one per
 * course ([membershipField]), one request per hold of the lock. A delete is never given up on for
 * its refusals, as before. The moments are the device's clocks' ([DeviceClocks]). A delete names its kind, read as
 * it goes, as the iPhone's does ([courseDeleteKind]): with none, the backend hid a course
 * whose hand-add had not reached it on every device (tigerduck-app-android#698). One made while course sync is off
 * is kept until it is on (LWW D12), and one the backend refused, the course being added since
 * elsewhere, lifts this phone's hide of it unless the course was deleted again since.
 *
 * The iPhone's `CloudSyncCoordinator` queues its course deletes in its outbox
 * the same way, hides them with `pendingCourseDeleteKeys` meanwhile and drops
 * them with `discardCourseDeletes` on a re-add or a reset.
 */
@Singleton
class PendingCourseDeletes internal constructor(
    private val dataCache: DataCache,
    private val pendingCourseAdds: PendingCourseAdds,
    private val deleter: AnsweredCourseDeleter,
    private val clocks: DeviceClocks,
    // The file's read; a test holds it to order two callers around it.
    private val load: suspend () -> Map<String, Map<String, Long>>,
    private val outbox: MembershipOutbox,
) : OutboxChannel {
    /** A queue alone, its DELETEs answered by [deleter] and its moments read by [clock] alone. */
    internal constructor(
        dataCache: DataCache,
        pendingCourseAdds: PendingCourseAdds,
        deleter: CourseDeleter,
        clock: () -> Long = System::currentTimeMillis,
        load: suspend () -> Map<String, Map<String, Long>> = dataCache::loadPendingCourseDeletes,
    ) : this(
        dataCache, pendingCourseAdds,
        AnsweredCourseDeleter { courseKey, signIn, hiddenAt, _ -> deleter.delete(courseKey, signIn, hiddenAt).let { ANSWERED } },
        DeviceClocks.wallOnly(clock), load, MembershipOutbox(),
    )

    @Inject constructor(
        dataCache: DataCache,
        pendingCourseAdds: PendingCourseAdds,
        pushApiClient: PushApiClient,
        clocks: DeviceClocks? = null,
        syncPolicies: SyncPolicies? = null,
        ackClock: AckClock? = null,
        @ApplicationScope scope: CoroutineScope? = null,
    ) : this(
        dataCache = dataCache,
        pendingCourseAdds = pendingCourseAdds,
        deleter = AnsweredCourseDeleter { courseKey, signIn, hiddenAt, kind ->
            pushApiClient.deleteCourseAnswered(courseKey, signIn, hiddenAt, kind)
        },
        clocks = clocks ?: DeviceClocks.WALL,
        load = dataCache::loadPendingCourseDeletes,
        outbox = MembershipOutbox(syncPolicies, ackClock, scope),
    )

    override val id: OutboxChannelId get() = OutboxChannelId.COURSE_DELETES

    // The file's lock: every read that a write follows, and the stamps' seed.
    private val mutex = Mutex()

    private val lastStamp = AtomicLong(0)

    // Whether [lastStamp] has been raised above every stamp the file held when
    // this process first needed one; read and set under [mutex].
    private var seeded = false

    // Every delete asked before this a course reset let go of ([forgetAskedBefore]),
    // so one asked before it and kept only after is not kept; read and set under [mutex].
    private var forgottenBefore = Long.MIN_VALUE

    // The kind the class table chose for each delete it asked ([keep], [send]), by `<semester>:<courseNo>`
    // key, with the moment of the delete it was chosen for: sent for that delete only, a newer one
    // of the course choosing again. Chosen as asked, before the class table's own writes, which take
    // a school course off its term's rows and hide it a step at a time. In memory: a delete from
    // before this process goes by what the phone holds as it goes ([courseDeleteKind]).
    private val chosenKinds = ConcurrentHashMap<String, Pair<Long, ChosenKind>>()

    private val sends: CourseDeleteSends = CourseDeleteSends(
        dataCache, deleter, outbox,
        // The file as it stands, under the queue's lock (the lane's own is not held across it).
        waiting = { deletesOf(waiting()) },
        underQueueLock = { step -> mutex.withLock { step() } },
        kindOf = { delete ->
            val chosen = chosenKinds[CourseTombstoneKeys.key(delete.semester, delete.courseNo)]
                ?.takeIf { (askedAt, _) -> askedAt == delete.queuedAt }?.second
            if (chosen != null) chosen.kind else dataCache.courseDeleteKind(delete.semester, delete.courseNo)
        },
    )

    private val lane: OutboxLane<CourseDelete> = OutboxLane(
        OutboxChannelId.COURSE_DELETES,
        CourseDeletesStore(dataCache, load),
        LanePolicy(
            field = CourseDelete::field,
            // A delete is never given up on for its refusals, as before it was a channel.
            maxAttempts = Int.MAX_VALUE,
            attempts = InMemoryAttempts(CourseDelete::field),
            stopsOnRetry = true,
        ),
        outbox.ack,
        outbox.scope,
        sends::sendOnce,
    )

    /**
     * The moment of a class-table action, for [send] and [forget] ([CourseMoment]):
     * its order, the clock, and later than every earlier one's whatever the clock
     * does, the deletes still queued from before this process included, and its
     * time, the clock's, which its age is read from. Take it as the user
     * acts, before the work that follows suspends, so a re-add and a removal
     * compare by the order they were asked in, not by when each reached the
     * lock behind a slow request. Waits for the file's lock, the first time
     * to read the stamps already kept, and is taken before that lock goes:
     * the lock hands over in the order asked, so a second stamp asked while
     * the first reads gets the later one, even when it resumes inline as the
     * lock goes, as on the main thread's immediate dispatcher.
     */
    suspend fun stamp(): CourseMoment = mutex.withLock {
        if (!seeded) {
            noteStamps(waiting())
            // The adds' moments too, which the class table takes from here: after a restart with
            // the clock set back, a hide must still come after an add queued before it (#591).
            noteStamps(dataCache.loadPendingCourseAddTimes())
            seeded = true
        }
        // Ordered past the clock once it was set back; its time, which its age is read from, stays the clock's.
        val now = clocks.now()
        CourseMoment(lastStamp.updateAndGet { last -> maxOf(last + 1, now.wallMs) }, now)
    }

    /**
     * Keeps the delete of [courseNo] in [semester], asked at [queuedAt], on
     * disk without sending it, for [send] or the next full sync to send: the
     * class table keeps it before its hide is marked, so a course selection
     * answer never finds the mark without the delete
     * (`DataCache.hideDeletedCourse`). False once [signIn] has ended. It goes
     * as the [chosen] kind, when one was.
     */
    suspend fun keep(semester: String, courseNo: String, queuedAt: CourseMoment, signIn: SignIn, chosen: ChosenKind? = null): Boolean {
        choose(semester, courseNo, queuedAt.at, chosen)
        return edit(signIn, queuedAt) { it.with(semester, courseNo, queuedAt.at) }
    }

    /**
     * Sends the delete of [courseNo] in [semester] that [keep] kept, without
     * keeping it again: a full sync's retry may have sent it since, and a
     * course selection answer brought the course back with the roster after
     * it landed, which a second DELETE would take off the server again. Sent
     * only while it is still queued, as [drain] sends. Takes
     * [PendingCourseAdds.removing] itself, so call it outside that lock.
     */
    suspend fun sendKept(semester: String, courseNo: String, signIn: SignIn) {
        sendHeld(semester, courseNo, signIn)
    }

    /**
     * The kind of the class table's delete of [courseNo] in [semester], [deleted] as the term held
     * it, for [keep] or [send] ([CourseDeleteKind.asked]): leaving a hide here when [hides]. Call it
     * before the delete writes anything, as its save takes a school course off the term's rows.
     */
    suspend fun chooseKind(semester: String, courseNo: String, deleted: Course?, hides: Boolean): ChosenKind =
        ChosenKind(CourseDeleteKind.asked(deleted, hides, dataCache.schoolLists(semester, courseNo)))

    /** [send] with the delete asked now. */
    suspend fun send(semester: String, courseNo: String, signIn: SignIn) = send(semester, courseNo, stamp(), signIn)

    /**
     * Deletes [courseNo]'s row in [semester] from the backend, keeping the
     * delete, asked at [queuedAt], until it lands, as the [chosen] kind when
     * one was. Takes [PendingCourseAdds.removing] itself, so call it outside
     * that lock.
     */
    suspend fun send(semester: String, courseNo: String, queuedAt: CourseMoment, signIn: SignIn, chosen: ChosenKind? = null) {
        choose(semester, courseNo, queuedAt.at, chosen)
        // On disk before the wait for the lock, which a slow request may hold
        // for a while: a process killed meanwhile keeps the delete for its
        // next sync. A re-add's forget waiting ahead of this cannot let it go,
        // as the delete was asked after the re-add; see [forget].
        if (!edit(signIn, queuedAt) { it.with(semester, courseNo, queuedAt.at) }) return
        sendHeld(semester, courseNo, signIn)
    }

    /**
     * Keeps a delete of [courseNo] in [semester] at the oldest time ([EditTime.OLDEST], LWW D13), unless one waits
     * for the course: it fills only what the backend holds no stamp for. Sends nothing.
     */
    suspend fun queueAsOldest(semester: String, courseNo: String, signIn: SignIn): Boolean =
        edit(signIn) { if (it[semester]?.containsKey(courseNo) == true) it else it.with(semester, courseNo, EditTime.OLDEST.wallMs) }

    /**
     * Sends every delete still waiting, each under the lock on its own, so an
     * add or a reset can come between two; each full sync starts with this,
     * so the snapshot lacks them. A delete that fails waits for the next sync,
     * and the next one goes.
     */
    override suspend fun drain(signIn: SignIn) {
        for ((semester, entries) in waiting()) {
            for (courseNo in entries.keys) {
                if (!sendHeld(semester, courseNo, signIn)) return
            }
        }
    }

    override suspend fun requeueParked(signIn: SignIn) = lane.requeueParked(signIn)

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    override fun forgetMemory() = lane.forgetMemory()

    /** The course numbers whose delete in [semester] is still waiting, for the reconcile to keep out. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().keys

    /** [forget] for a course added back now. */
    suspend fun forget(semester: String, courseNo: String, signIn: SignIn) = forget(semester, courseNo, stamp(), signIn)

    /**
     * Lets go of [courseNo]'s delete in [semester] asked before [askedAt], the
     * moment the course was added back: one asked after the re-add is a newer
     * removal and stays. Under the adds lock, after any send of it under way:
     * a retry that read it is then either done or finds it gone. Call it
     * outside that lock, before the add's own upload.
     */
    suspend fun forget(semester: String, courseNo: String, askedAt: CourseMoment, signIn: SignIn) {
        pendingCourseAdds.removing {
            edit(signIn) { it.without(semester, courseNo) { queuedAt -> queuedAt <= askedAt.at } }
        }
    }

    /**
     * Lets go of every delete in [semester]: the term was reset, which deleted
     * its rows. Call it inside the [PendingCourseAdds.removing] block whose
     * wipe just landed, before that lock goes: a retry waiting behind the
     * wipe would otherwise send a delete the reset's refetch then re-uploads
     * past, taking the course off the server again and tombstoning it.
     */
    suspend fun forgetTerm(semester: String, signIn: SignIn) {
        edit(signIn) { it - semester }
    }

    /**
     * Lets go of every delete asked before [stamp], one [stamp] took before a full sync's fetch:
     * that snapshot read a course reset made on another device, which brought back the courses
     * they took (`HomeBackendSync.applyCourseReset`). One asked after it is newer than the reset
     * and stays. Call it inside [PendingCourseAdds.removing], as [forgetTerm]: a retry waiting
     * behind it then finds them gone, rather than taking a restored course off the server again.
     * A removal stamped before [stamp] whose own writes outlast this, as the class table's may
     * before it keeps its delete, finds it let go too: [keep] and [send] then keep nothing.
     */
    suspend fun forgetAskedBefore(stamp: Long, signIn: SignIn) {
        mutex.withLock { forgottenBefore = maxOf(forgottenBefore, stamp) }
        edit(signIn) { pending ->
            pending.mapValues { (_, entries) -> entries.filterValues { queuedAt -> queuedAt > stamp } }
                .filterValues { it.isNotEmpty() }
        }
    }

    // Under the adds lock, the delete read again there by the lane: false once [signIn] has ended,
    // when the delete was another account's and is not sent as this one's; true otherwise, sent
    // or not. One request at most ([CourseDeleteSends.one]).
    private suspend fun sendHeld(semester: String, courseNo: String, signIn: SignIn): Boolean =
        pendingCourseAdds.sendingOne(
            block = {
                if (!signIn.isCurrent) return@sendingOne false
                sends.one { lane.drain(signIn, setOf(membershipField(semester, courseNo))) }
                true
            },
        )

    private suspend fun waiting(): Map<String, Map<String, Long>> = load()

    // Keeps the kind [chosen] for the delete of [courseNo] asked at [queuedAt], over any older one's.
    private fun choose(semester: String, courseNo: String, queuedAt: Long, chosen: ChosenKind?) {
        if (chosen == null) return
        chosenKinds.merge(CourseTombstoneKeys.key(semester, courseNo), queuedAt to chosen) { old, new -> if (new.first >= old.first) new else old }
    }

    // False, and nothing written, once [signIn] has ended. Through the lane, under the file's lock;
    // each moment keeps its clocks, and a delete asked at [asked] takes its own.
    private suspend fun edit(
        signIn: SignIn,
        asked: CourseMoment? = null,
        change: (Map<String, Map<String, Long>>) -> Map<String, Map<String, Long>>,
    ): Boolean = mutex.withLock {
        lane.edit(signIn) { deletes ->
            val before = deletesByTerm(deletes)
            noteStamps(before)
            val clocks = deletes.associate { it.queuedAt to it.clocks } + listOfNotNull(asked?.let { it.at to it.clocks })
            deletesOf(change(before), clocks::get)
        }
    }

    // Keeps the next stamp above every one [pending] holds.
    private fun noteStamps(pending: Map<String, Map<String, Long>>) {
        val largest = pending.values.maxOfOrNull { entries -> entries.values.maxOrNull() ?: 0L } ?: return
        lastStamp.updateAndGet { last -> maxOf(last, largest) }
    }

    // A delete asked again keeps the later moment: it is the newer removal. One asked before a
    // course reset let go of the deletes is not kept at all; called under [mutex], by [edit].
    private fun Map<String, Map<String, Long>>.with(semester: String, courseNo: String, queuedAt: Long) =
        if (queuedAt < forgottenBefore) {
            this
        } else {
            this + (semester to this[semester].orEmpty().let { it + (courseNo to maxOf(it[courseNo] ?: 0L, queuedAt)) })
        }

    companion object {
        // What a delete that returned answers, for a deleter that says nothing more.
        private val ANSWERED = WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap())
    }
}

/** The course deletes join the outbox. */
@Module
@InstallIn(SingletonComponent::class)
object CourseDeletesChannelModule {
    @Provides
    @IntoSet
    fun channel(deletes: PendingCourseDeletes): OutboxChannel = deletes
}
