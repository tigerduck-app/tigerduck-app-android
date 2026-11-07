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
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.QueueFile
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
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
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one assignment's mark, and says what the backend answered; see [PushApiClient.patchAssignmentOverrideAnswered]. */
internal fun interface MarkWriter {
    suspend fun write(assignmentId: Int, status: String, signIn: SignIn, madeAt: EditTime?): WriteAnswer
}

/**
 * Done and ignored marks tapped here that the backend has not confirmed, by
 * assignment id, with the status the tap set: the outbox's marks channel.
 *
 * A failed PATCH used to be only logged. Once the account's marks had been
 * through a sync here, the next one took the server — which never got the
 * mark — as the truth and wiped it (tigerduck-app-android#155 made an empty
 * server the truth). Now the mark waits on disk: a full sync keeps this
 * phone's value for it and sends it again first, until it lands. The iPhone
 * app's outbox does the same.
 *
 * The marks wait in `unsent_marks.json` as they always have, and go through
 * an [OutboxLane]: one PATCH at a time, oldest first, so they reach the
 * backend in the order they were made, each pinned to the sign-in its mark
 * was read under. A refusal is counted in the mark itself, and at
 * [MAX_ATTEMPTS] it parks: kept at this phone's value, and tried once more
 * per process and again [PARKED_RETRY_MS] after its last refusal.
 *
 * Whether a tap is kept is [records] (`SyncPolicy.keeps`), whether it goes
 * [sends] (`SyncPolicy.sends`). A tap is kept while the Assignments switch or
 * TigerSync is off and goes once both are on (LWW D12); on a build without
 * TigerSync it goes nowhere and is not kept, but replaces a mark waiting for
 * the same assignment. While the assignments re-enable check is pending a tap
 * is kept but not sent, as the user's answer decides the marks. What waits
 * goes out once marks are sent again, or is dropped when the server's marks
 * are taken over this phone's ([forget]). Account-scoped: wiped with the user
 * data at sign-out.
 *
 * Each tap keeps its time by the device's clocks ([clocks]), and goes with
 * its age (`age_ms`), from the tap or from the end of a write of its
 * assignment that was out when it was made, kept with it (its floor, the
 * client's `FieldAges`), parked or not. A mark an older build
 * wrote with no time is given its old-entry time at its first load and
 * written back (X1), never its send time. A reply writes nothing on this
 * phone, the backend's later mark included: Home saves the marks from its own
 * state, outside the queue, so no check at a reply could tell an older
 * reply's mark from a newer tap. A mark the backend kept in place of this
 * phone's is another device's later write, which the change feed brings and
 * the next pull applies.
 */
@Singleton
class UnsentMarks internal constructor(
    private val dataCache: DataCache,
    private val writer: MarkWriter,
    private val records: () -> Boolean,
    private val sends: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    // The clocks a tap's time is read by: [clock] alone, with no elapsed clock, for a test's queue.
    private val clocks: DeviceClocks = DeviceClocks.wallOnly(clock),
    ack: AckClock = AckClock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val oldEntryTime: OldEntryTime? = null,
) : OutboxChannel {

    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        policies: SyncPolicies = SyncPolicies(prefs),
        /** What a tap's time is read by; the wall clock alone in tests. */
        clocks: DeviceClocks = DeviceClocks.WALL,
        ack: AckClock = AckClock(),
        @ApplicationScope scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        /** The old-entry time of a mark an older build wrote with no time; null only in tests, for none. */
        oldEntryTime: OldEntryTime? = null,
    ) : this(
        dataCache = dataCache,
        writer = MarkWriter(pushApiClient::patchAssignmentOverrideAnswered),
        records = { policies.current.keeps(SyncCategory.ASSIGNMENTS) },
        sends = { policies.current.sends(SyncCategory.ASSIGNMENTS) },
        clocks = clocks,
        ack = ack,
        scope = scope,
        oldEntryTime = oldEntryTime,
    )

    override val id: OutboxChannelId get() = OutboxChannelId.MARKS

    // Whether this process has given the parked marks their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    private val lane = OutboxLane(
        id = OutboxChannelId.MARKS,
        store = object : LaneStore<UnsentMark> {
            override suspend fun load(): List<UnsentMark> = dataCache.loadUnsentMarks()

            override suspend fun save(entries: List<UnsentMark>, signIn: SignIn): Boolean = dataCache.saveUnsentMarks(entries, signIn)
        },
        policy = LanePolicy(
            field = ::fieldOf,
            maxAttempts = MAX_ATTEMPTS,
            attempts = OnDiskAttempts(
                attempts = { it.attempts },
                counted = { mark, attempts, refused ->
                    mark.copy(attempts = attempts, lastRefusedAtMs = if (refused) clock() else mark.lastRefusedAtMs)
                },
                // The lane asks this of marks of any assignment, so it names the assignment too.
                sameEdit = { a, b -> a.assignmentId == b.assignmentId && a.isSameTap(b) },
            ),
            untimed = UntimedEntries(
                timeOf = { mark -> mark.madeAtMs },
                withTime = { mark, atMs -> mark.copy(madeAtMs = atMs) },
                oldEntryTimeMs = { oldEntryTime?.oldEntryTimeMs(dataCache.queueFile(QueueFile.UNSENT_MARKS)) ?: 0L },
            ),
            floors = EntryFloors(UnsentMark::madeAt, { it.madeAtClocks?.floor?.time }, UnsentMark::withFloor),
        ),
        ack = ack,
        scope = scope,
        send = ::sendNow,
    )

    /** The ids whose last tap has not reached the server; a sync keeps this phone's value for them. */
    suspend fun ids(): Set<String> = lane.entries().mapTo(LinkedHashSet()) { it.assignmentId.orEmpty() }

    /** The status each of those taps set, by assignment id. */
    suspend fun statuses(): Map<String, String> =
        lane.entries().mapNotNull { mark -> mark.status?.let { mark.assignmentId.orEmpty() to it } }.toMap()

    /**
     * A tap that set [assignmentId] to [status]: kept until the server has
     * it, and sent at once unless the re-enable check holds the marks. Made
     * while marks are not kept, it is this phone's own and goes nowhere, but
     * it replaces a mark still waiting for the same assignment, which would
     * otherwise go up later over it.
     */
    suspend fun send(assignmentId: String, status: String, signIn: SignIn) {
        if (!records()) {
            lane.edit(signIn) { marks -> marks.filterNot { it.assignmentId == assignmentId } }
            return
        }
        lane.enqueueAndDrain(tapNow(assignmentId, status), signIn, sends)
    }

    /**
     * Keeps a mark Use Local could not send, whatever [sends] says: while the
     * answer is applied the re-enable check still holds marks, and dropped,
     * the mark would be lost to the next sync. Written only while [signIn]
     * stands, and never over a mark already waiting for the assignment: that
     * is a tap made during the hold, newer than what Use Local read.
     */
    suspend fun keep(assignmentId: String, status: String, signIn: SignIn) {
        lane.edit(signIn) { marks ->
            if (marks.any { it.assignmentId == assignmentId }) marks else marks + tapNow(assignmentId, status)
        }
    }

    // A tap that set [assignmentId] to [status] now, with its time by the device's clocks.
    private fun tapNow(assignmentId: String, status: String): UnsentMark {
        val now = clocks.now()
        return UnsentMark(assignmentId, status, madeAtMs = now.wallMs, madeAtClocks = EditClocks.of(now))
    }

    /**
     * Keeps [status] as [assignmentId]'s mark at the oldest time there is ([EditTime.OLDEST]), unless a mark
     * waits for it: a value this phone held before the category ever synced, which fills only a
     * mark the backend holds no stamp for (LWW D13), as a category's first sync queues it. Written
     * only while [signIn] stands.
     */
    suspend fun queueAsOldest(assignmentId: String, status: String, signIn: SignIn) {
        lane.edit(signIn) { marks ->
            if (marks.any { it.assignmentId == assignmentId }) marks else marks + UnsentMark(assignmentId, status, madeAtMs = EditTime.OLDEST.wallMs)
        }
    }

    /** The server's marks were taken over this phone's, so what waited is no longer wanted. */
    suspend fun forget(signIn: SignIn) {
        lane.edit(signIn) { emptyList() }
    }

    /**
     * Sends every mark still waiting, oldest first, unless marks are not sent now. A parked mark
     * gets one more try per process, and again once [PARKED_RETRY_MS] has passed since its last
     * refusal: the backend creates an assignment's row in its own Moodle fetch, every eight hours,
     * and the app may stay open far longer than that.
     */
    override suspend fun drain(signIn: SignIn) {
        if (!sends()) return
        val firstInProcess = parkedRequeued.compareAndSet(false, true)
        val now = clock()
        lane.requeueParked(signIn) { mark -> firstInProcess || now - mark.lastRefusedAtMs !in 0 until PARKED_RETRY_MS }
        lane.drain(signIn)
    }

    /** Nothing: a course upload never gave a parked mark its try, which follows its own rule ([drain]). */
    override suspend fun requeueParked(signIn: SignIn) = Unit

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    override suspend fun raiseFloors(key: String, start: EditTime, end: EditTime, signIn: SignIn) {
        lane.raiseFloors(key, start, end, signIn)
    }

    override suspend fun floorOf(key: String, madeAt: EditTime): EditTime? = lane.floorOf(key, madeAt)

    /** At a sign-out: the next student's parked marks get their own one more try in this process. */
    override fun forgetMemory() {
        parkedRequeued.set(false)
        lane.forgetMemory()
    }

    // The lane's send of [mark] under [signIn], read as it was about to go: what became of it.
    private suspend fun sendNow(mark: UnsentMark, signIn: SignIn): SendResult {
        if (!sends()) return SendResult.NotSent
        val status = mark.status ?: return SendResult.NotSent
        // An id no request can name goes nowhere, and is let go as it always was.
        val id = mark.assignmentId?.toIntOrNull() ?: return SendResult.Sent(null)
        val answer = try {
            writer.write(id, status, signIn, mark.madeAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "mark PATCH failed, kept for the next sync: ${e.logSummary()}")
            return SendResult.afterFailure(e)
        }
        if (!answer.sent) return SendResult.NotSent
        return SendResult.Sent(answer.serverClockUs)
    }

    // The same tap, however many times the backend refused it.
    private fun UnsentMark.isSameTap(other: UnsentMark) = status == other.status && madeAtMs == other.madeAtMs

    private fun fieldOf(mark: UnsentMark): String = SyncField.mark(mark.assignmentId.orEmpty())

    companion object {
        private const val TAG = "UnsentMarks"

        /** Refusals before a mark is parked, as on the iOS outbox. */
        const val MAX_ATTEMPTS = 5

        /** How long a parked mark waits for its next try in a process that stays alive. */
        const val PARKED_RETRY_MS = 8 * 60 * 60 * 1000L
    }
}

/** The marks join the outbox's channels (`OutboxModule`). */
@Module
@InstallIn(SingletonComponent::class)
object UnsentMarksChannelModule {
    @Provides
    @IntoSet
    fun channel(marks: UnsentMarks): OutboxChannel = marks
}
