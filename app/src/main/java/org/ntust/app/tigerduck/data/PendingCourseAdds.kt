package org.ntust.app.tigerduck.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.QueueFile
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.InMemoryAttempts
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
import org.ntust.app.tigerduck.sync.outbox.UntimedEntries
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads one term's courses as sign-in [signIn], each add in [addedAt] with its time; see
 * [PushApiClient.uploadCoursesAnswered].
 */
internal fun interface CourseUploader {
    suspend fun upload(
        courses: List<Course>,
        semester: String,
        forceKeys: List<String>,
        signIn: SignIn,
        addedAt: Map<String, Long>,
    ): CourseUploadAnswer?
}

/**
 * Courses added by hand on the class table whose upload the backend has not
 * taken, by term: the outbox's course adds channel.
 *
 * An add went up once. A sync re-uploads a term's courses only when the
 * server has none for it (`CourseSyncReconciler.reconcileSemester`), so in a
 * term the server already had, a failed upload left the course on this phone
 * alone: never on another device, and lost at sign-out. Now the add waits on
 * disk, kept before the request so a process killed during it keeps it too,
 * and every full sync sends it again first with the same force key, as
 * [UnsentMarks] does for marks. It goes once an upload's answer accepts the
 * course or a snapshot lists it as enrolled ([listed]), or once the course has left this phone's
 * cache or been deleted (by hand, a reset, Use Server). Account-scoped: sent
 * only under the sign-in that kept it, and wiped with the user data at sign-out.
 * Meanwhile the reconcile keeps the course ([waitingIn]), past a tombstone or
 * the server's silence, and the sign-out warning counts it.
 *
 * The adds wait in an [OutboxLane] over the queue's own two files ([CourseAddsStore]), one per
 * course ([membershipField]). One send is a term's upload with the adds asked of it, under the
 * send lock ([removing]): a full sync's retry asks for every add of a term, the class table's add
 * for its own. An add is never given up on for its refusals, as before. On `/v4` an add an older
 * build kept with no moment goes with its old-entry time (X1), one made while course sync is off
 * is kept until it is on (LWW D12), and one the backend refused, a hide or reset elsewhere being
 * newer, is the backend's to settle unless the course was added again since.
 *
 * As the iPhone's outbox since tigerduck-app#727.
 */
@Singleton
class PendingCourseAdds internal constructor(
    private val dataCache: DataCache,
    private val uploader: CourseUploader,
    private val outbox: MembershipOutbox = MembershipOutbox(),
) : OutboxChannel {
    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        syncPolicies: SyncPolicies? = null,
        ackClock: AckClock? = null,
        @ApplicationScope scope: CoroutineScope? = null,
        oldEntryTime: OldEntryTime? = null,
        outbox: dagger.Lazy<Outbox>? = null,
        overrides: dagger.Lazy<PendingCourseOverrides>? = null,
    ) : this(
        dataCache = dataCache,
        uploader = CourseUploader { courses, semester, forceKeys, signIn, addedAt ->
            pushApiClient.uploadCoursesAnswered(courses, semester, signIn, forceKeys, addedAt = addedAt)
        },
        outbox = MembershipOutbox(syncPolicies, ackClock, scope, oldEntryTime, outbox, overrides),
    )

    override val id: OutboxChannelId get() = OutboxChannelId.COURSE_ADDS

    // One upload at a time, and none while courses are taken off the backend;
    // see [removing].
    private val sendMutex = Mutex()

    private val sends: CourseAddSends = CourseAddSends(
        dataCache, uploader, outbox,
        waiting = { lane.entries() },
        settle = { signIn, change -> lane.edit(signIn, change) },
    )

    private val lane: OutboxLane<CourseAdd> = OutboxLane(
        OutboxChannelId.COURSE_ADDS,
        CourseAddsStore(dataCache),
        LanePolicy(
            field = CourseAdd::field,
            // An add is never given up on for its refusals, as before it was a channel.
            maxAttempts = Int.MAX_VALUE,
            attempts = InMemoryAttempts(CourseAdd::field),
            stopsOnRetry = true,
            untimed = UntimedEntries(
                // X1, on /v4 alone. On /v3 an add with no moment goes unstamped, as it always went,
                // so it is given none: written back, it would go out with the add.
                timeOf = { add -> add.addedAtMs ?: if (outbox.lastWriterWins()) null else GOES_UNSTAMPED },
                withTime = { add, atMs -> add.copy(addedAtMs = atMs) },
                oldEntryTimeMs = { outbox.oldEntryTime?.oldEntryTimeMs(dataCache.queueFile(QueueFile.COURSE_ADDS)) ?: 0L },
            ),
        ),
        outbox.ack,
        outbox.scope,
        sends::sendRequested,
    )

    /**
     * Uploads [courseNo], just added to [semester]'s list [courses], forcing it
     * past a tombstone as an add does, with [addedAt], the add's moment
     * ([PendingCourseDeletes.stamp]): the backend applies it only when later
     * than the course's last add, hide and reset, so an add kept offline loses
     * to a hide or a reset made since on another device (#591). Kept until the
     * backend accepts or settles it. On `/v3` let go as well when nothing was sent
     * because course sync is off: the re-enable check answers for the courses
     * when it comes back on.
     */
    suspend fun send(courses: List<Course>, semester: String, courseNo: String, addedAt: Long? = null, signIn: SignIn) {
        val kept = lane.edit(signIn) { it.asked(CourseAdd(semester, courseNo, addedAt)) }
        if (!kept) return
        trySend(semester, listOf(courseNo), signIn, courses)
    }

    /**
     * Keeps an add of [courseNo] in [semester] stamped 0, the oldest (LWW D13), unless one waits
     * for the course: it fills only what the backend holds no stamp for. Sends nothing.
     */
    suspend fun queueAsOldest(semester: String, courseNo: String, signIn: SignIn): Boolean =
        lane.edit(signIn) { adds ->
            if (adds.any { it.field == membershipField(semester, courseNo) }) adds else adds + CourseAdd(semester, courseNo, 0L)
        }

    /**
     * Sends every add still waiting, with the course as this phone has it
     * now; each full sync starts with this, so the snapshot it fetches has
     * them. An add whose course this phone no longer holds is let go unsent.
     */
    suspend fun retry(signIn: SignIn) = drain(signIn)

    /** [retry]: each term's adds in one upload, a term after the other, in the file's order. */
    override suspend fun drain(signIn: SignIn) {
        for ((semester, adds) in lane.entries().groupBy { it.semester }) {
            if (!trySend(semester, adds.map { it.courseNo }, signIn)) return
        }
    }

    override suspend fun requeueParked(signIn: SignIn) = lane.requeueParked(signIn)

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    override fun forgetMemory() = lane.forgetMemory()

    /**
     * Runs [block], which takes courses off the backend (a course deleted by
     * hand, a term reset), with no add being sent meanwhile. An add already
     * under way lands first, and the DELETE after it; one that waits finds
     * its course gone from this phone and is not sent. The other way round,
     * its force key would bring the course back past the DELETE. The courses
     * have left this phone's cache by the time [block] ends.
     */
    suspend fun <T> removing(block: suspend () -> T): T = sendMutex.withLock { block() }

    /**
     * Runs [block], which puts a term's courses on the backend as this phone
     * holds them — a fetch's upload, with the save it goes with — in the
     * order [removing]'s DELETEs run in: begun before one, it lands first and
     * the DELETE takes the courses after; begun after one, it finds what the
     * DELETE left. An upload past a DELETE of its term would bring the
     * courses back, as an add's force key would, and with no tombstone to
     * stop it.
     */
    suspend fun <T> uploading(block: suspend () -> T): T = sendMutex.withLock { block() }

    /**
     * One request of a course channel's, [block], under the send lock [removing] takes, then
     * [then] with its result once the lock is given back. The lock is waited for in the caller,
     * so one cancelled while it waits sends nothing; once it is held, the request runs to its end
     * on the application scope, the lock held, so a caller's cancellation never cuts it short nor
     * lets an add or a DELETE start beside it (spec §5.3).
     */
    internal suspend fun <T> sendingOne(block: suspend () -> T, then: suspend (T) -> Unit = {}): T {
        sendMutex.lock()
        // ATOMIC: once the lock is taken, the block runs to give it back, whatever has been cancelled.
        return outbox.scope.async(start = CoroutineStart.ATOMIC) {
            val result = try {
                block()
            } finally {
                sendMutex.unlock()
            }
            then(result)
            result
        }.await()
    }

    /**
     * Lets go of the adds [serverCourses], a snapshot's rows, list as enrolled: the server has
     * them. A landed add enrolls its course, and a roster never drops it after, so a row a roster
     * dropped is one from before the add. So is an enrolled row of a course on this phone's drop
     * list, unless added by hand: the next roster drops it unless the add lands first. Taken as
     * the add, either let it go unsent, the course left out on every other device.
     */
    suspend fun listed(serverCourses: List<ServerCourse>, signIn: SignIn) {
        val waiting = waiting()
        if (waiting.isEmpty()) return
        val droppedHere = dataCache.loadSelectionDroppedNos()
        val listed = serverCourses
            .filter { !it.isDropped && (it.isUserAdded || it.courseNo !in droppedHere[it.semester].orEmpty()) }
            .groupBy({ it.semester }, { it.courseNo })
        if (waiting.none { (semester, nos) -> listed[semester].orEmpty().any { it in nos } }) return
        lane.edit(signIn) { adds -> adds.filterNot { it.courseNo in listed[it.semester].orEmpty() } }
    }

    /**
     * Sends [courseNos]' adds in [semester], [courses] going up (or the
     * courses themselves as this phone holds them), under the sign-in
     * [signIn] they were read with, as one request of the lane's
     * ([CourseAddSends]). False when a sign-out since stops it; the request
     * itself carries [signIn], so a sign-out after the check sends it without
     * a token rather than with the next account's.
     */
    private suspend fun trySend(
        semester: String,
        courseNos: List<String>,
        signIn: SignIn,
        courses: List<Course>? = null,
    ): Boolean {
        val fields = courseNos.mapTo(HashSet()) { membershipField(semester, it) }
        val landed = sendingOne(
            block = {
                if (!signIn.isCurrent) return@sendingOne null
                sends.asking(semester, courseNos, courses) { lane.drain(signIn, fields) }
            },
            // An edit that waited for the backend to have the course goes again.
            then = { landed -> if (landed == true) outbox.courseUploadLanded(signIn) },
        )
        return landed != null
    }

    /**
     * Lets go of every waiting add: a course reset made on another device has
     * taken the hand-added courses (`HomeBackendSync.applyCourseReset`), and an
     * add kept past it would put its course back on every device.
     */
    suspend fun forgetAll(signIn: SignIn) {
        lane.edit(signIn) { emptyList() }
    }

    /** The course numbers whose add in [semester] is still waiting, for the reconcile to keep. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().toSet()

    private suspend fun waiting(): Map<String, List<String>> = dataCache.loadPendingCourseAdds()

    // [add] kept in place of the add waiting for its course, or last: an add asked again keeps
    // the later moment, the newer add's, and one asked with none has none.
    private fun List<CourseAdd>.asked(add: CourseAdd): List<CourseAdd> {
        val at = indexOfFirst { it.field == add.field }
        if (at < 0) return this + add
        val time = add.addedAtMs?.let { maxOf(this[at].addedAtMs ?: 0L, it) }
        return toMutableList().also { it[at] = add.copy(addedAtMs = time) }
    }

    companion object {
        private const val TAG = "PendingCourseAdds"

        // Any time at all: on /v3, an add with none is not given one.
        private const val GOES_UNSTAMPED = 0L

        /** The key an upload forces past a tombstone, as the class table's add sends it. */
        fun forceKey(semester: String, courseNo: String): String = "client:$semester:$courseNo"
    }
}

/** The course adds join the outbox. */
@Module
@InstallIn(SingletonComponent::class)
object CourseAddsChannelModule {
    @Provides
    @IntoSet
    fun channel(adds: PendingCourseAdds): OutboxChannel = adds
}
