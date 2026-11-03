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
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInBound
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.OnDiskAttempts
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.sync.outbox.UntimedEntries
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one assignment's mark, and says only whether it went out; for a test that sends through one. */
internal fun interface MarkSender {
    suspend fun send(assignmentId: Int, status: String, signIn: SignIn, madeAtMs: Long?): Boolean
}

/** Sends one assignment's mark, and says what the backend answered; see [PushApiClient.patchAssignmentOverrideAnswered]. */
internal fun interface MarkWriter {
    suspend fun write(assignmentId: Int, status: String, signIn: SignIn, madeAtMs: Long?): WriteAnswer
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
 * [sends] (`SyncPolicy.sends`). With the Assignments switch or TigerSync off
 * on `/v3` the marks are this phone's own: a tap goes nowhere and is not
 * kept, but replaces a mark waiting for the same assignment. On `/v4` a tap
 * is kept while either is off and goes once both are on (LWW D12). While the
 * assignments re-enable check is pending a tap is kept but not sent, as the
 * user's answer decides the marks. What waits goes out once marks are sent
 * again, or is dropped when the server's marks are taken over this phone's
 * ([forget]). Account-scoped: wiped with the user data at sign-out.
 *
 * Each tap is stamped by the sync clock ([stamp]), which on `/v3` is the
 * wall clock as it always was. A mark an older build wrote with no time goes
 * without one on `/v3`, as it always has; on `/v4` it is given its old-entry
 * time at its first load there and written back (X1), never its send time.
 * On `/v4` a reply that says the backend kept a later mark puts that mark on
 * this phone, unless a newer tap of the assignment waits (LWW D12).
 */
@Singleton
class UnsentMarks internal constructor(
    private val dataCache: DataCache,
    private val writer: MarkWriter,
    private val records: () -> Boolean,
    private val sends: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    // The time a tap is stamped with: the sync clock's, which is the wall clock on /v3.
    private val stamp: () -> Long = clock,
    // Whether the backend settles each mark by its stamp, as /v4 does.
    private val lastWriterWins: () -> Boolean = { false },
    ack: AckClock = AckClock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val oldEntryTime: OldEntryTime? = null,
) : OutboxChannel {

    /** The queue for a test that sends through a [MarkSender], as the queue's own tests do. */
    internal constructor(
        dataCache: DataCache,
        sender: MarkSender,
        records: () -> Boolean,
        sends: () -> Boolean,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(dataCache, sender.answering(), records, sends, clock)

    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        policies: SyncPolicies = SyncPolicies(prefs),
        /** What a tap is stamped by; null only in tests, for the wall clock. */
        syncClock: SyncClock? = null,
        ack: AckClock = AckClock(),
        @ApplicationScope scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        /** The old-entry time of a mark an older build wrote with no time; null only in tests, for none. */
        oldEntryTime: OldEntryTime? = null,
    ) : this(
        dataCache = dataCache,
        writer = MarkWriter(pushApiClient::patchAssignmentOverrideAnswered),
        records = { policies.current.keeps(SyncCategory.ASSIGNMENTS) },
        sends = { policies.current.sends(SyncCategory.ASSIGNMENTS) },
        stamp = syncClock?.let { it::stampMs } ?: System::currentTimeMillis,
        lastWriterWins = { policies.current.lastWriterWins },
        ack = ack,
        scope = scope,
        oldEntryTime = oldEntryTime,
    )

    override val id: OutboxChannelId get() = OutboxChannelId.MARKS

    // Whether this process has given the parked marks their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    // The mark the backend's reply last put on each assignment in place of this phone's, for the
    // sign-in it was put there under: a later reply may write over that, never over a tap's.
    private val backendMarks = SignInBound<Map<String, String>>(emptyMap())

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
                // On /v3 a mark with no time goes without one, as it always has, so it is given none.
                timeOf = { mark -> mark.madeAtMs ?: if (lastWriterWins()) null else SENT_WITHOUT_TIME },
                withTime = { mark, atMs -> mark.copy(madeAtMs = atMs) },
                oldEntryTimeMs = { oldEntryTime?.oldEntryTimeMs(dataCache.queueFile(QueueFile.UNSENT_MARKS)) ?: 0L },
            ),
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
        lane.enqueueAndDrain(UnsentMark(assignmentId, status, madeAtMs = stamp()), signIn, sends)
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
            if (marks.any { it.assignmentId == assignmentId }) marks else marks + UnsentMark(assignmentId, status, madeAtMs = stamp())
        }
    }

    /**
     * Keeps [status] as [assignmentId]'s mark stamped 0, the oldest stamp there is, unless a mark
     * waits for it: a value this phone held before the category ever synced, which fills only a
     * mark the backend holds no stamp for (LWW D13). Written only while [signIn] stands. Nothing
     * calls it until a category's first sync on `/v4` does.
     */
    suspend fun queueAsOldest(assignmentId: String, status: String, signIn: SignIn) {
        lane.edit(signIn) { marks ->
            if (marks.any { it.assignmentId == assignmentId }) marks else marks + UnsentMark(assignmentId, status, madeAtMs = OLDEST)
        }
    }

    /** Sends every mark still waiting; each full sync starts with this. */
    suspend fun retry(signIn: SignIn) = drain(signIn)

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
            writer.write(id, status, signIn, mark.madeAtMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "mark PATCH failed, kept for the next sync: ${e.logSummary()}")
            // Refused again once stamped again after a clock_ahead: nothing about the mark itself.
            return if (e.backendError()?.isClockAhead == true) SendResult.Retry else SendResult.afterFailure(e)
        }
        if (!answer.sent) return SendResult.NotSent
        if (lastWriterWins()) takeWinner(mark, answer, signIn)
        return SendResult.Sent(answer.serverClockUs)
    }

    // LWW D12, a write response's winner: the mark the backend holds after this write is this
    // phone's, unless a newer edit of the assignment stands here. A newer tap waiting in the queue
    // settles it; one Home saved and the queue has yet to keep is this phone's mark no longer being
    // the one the reply answered. So a reply, took or refused, writes only over the mark it answered,
    // or over one an earlier reply put there in its place (remembered as it is written), never over
    // a tap: a later reply then still takes back what an older reply's winner left, as when Home
    // cleared the one set it holds and left the other. Read, decided and written in one step with
    // every save of the marks; adopted, so Home reads the marks again rather than writing its own.
    private suspend fun takeWinner(mark: UnsentMark, answer: WriteAnswer, signIn: SignIn) {
        val applied = answer.applied ?: return
        val status = answer.values[WriteAnswer.LOCAL_STATUS] as? String ?: return
        val id = mark.assignmentId ?: return
        val answered = mark.status ?: return
        if (lane.entries().any { it.assignmentId == id && !it.isSameTap(mark) }) return
        val took = WriteAnswer.LOCAL_STATUS in applied
        dataCache.updateAssignmentMarks(signIn) { ignored, completed ->
            backendMarkIn(id, status, answered, backendMarks.value[id], ignored, completed)?.also {
                // A mark the backend took is this phone's own; one it kept in its place is remembered.
                val known = backendMarks.value
                backendMarks.set(if (took) known - id else known + (id to markOf(status)), signIn)
            }
        }
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

        // The stamp of a value queued as the oldest there is (LWW D13).
        private const val OLDEST = 0L

        // What a mark with no time reads as on /v3, where it goes without one: a time it needs none of.
        private const val SENT_WITHOUT_TIME = 0L

        /** Whether a tap is kept to send: `SyncPolicy.keeps` of the assignments, as the switches stand. */
        fun recordsMarks(prefs: AppPreferences): Boolean = SyncPolicies(prefs).current.keeps(SyncCategory.ASSIGNMENTS)

        /** Whether marks go out: `SyncPolicy.sends` of the assignments, which the re-enable check holds. */
        fun sendsMarks(prefs: AppPreferences): Boolean = SyncPolicies(prefs).current.sends(SyncCategory.ASSIGNMENTS)
    }
}

/**
 * The ignored and marked-done sets with [status], the backend's mark, on [id] in place of what they
 * hold; null when nothing changes. Only while [id]'s mark in them is [answered], the mark the reply
 * answered, or [backendMark], one an earlier reply put there in its place: never over a tap's.
 */
internal fun backendMarkIn(
    id: String,
    status: String,
    answered: String,
    backendMark: String?,
    ignored: Set<String>,
    completed: Set<String>,
): Pair<Set<String>, Set<String>>? {
    val here = markIn(id, ignored, completed)
    if (here != markOf(answered) && here != backendMark) return null
    val next = when (markOf(status)) {
        AssignmentOverrideReconciler.STATUS_IGNORED -> (ignored + id) to (completed - id)
        AssignmentOverrideReconciler.STATUS_COMPLETED -> (ignored - id) to (completed + id)
        else -> (ignored - id) to (completed - id)
    }
    return next.takeIf { it != (ignored to completed) }
}

// [status] as the two sets hold a mark: ignored (an older name for it, archived, included), marked
// done, or neither.
private fun markOf(status: String): String = when (status) {
    AssignmentOverrideReconciler.STATUS_IGNORED, STATUS_ARCHIVED -> AssignmentOverrideReconciler.STATUS_IGNORED
    AssignmentOverrideReconciler.STATUS_COMPLETED -> AssignmentOverrideReconciler.STATUS_COMPLETED
    else -> AssignmentOverrideReconciler.STATUS_NONE
}

// [id]'s mark in the two sets, as [markOf] names it.
private fun markIn(id: String, ignored: Set<String>, completed: Set<String>): String = when (id) {
    in ignored -> AssignmentOverrideReconciler.STATUS_IGNORED
    in completed -> AssignmentOverrideReconciler.STATUS_COMPLETED
    else -> AssignmentOverrideReconciler.STATUS_NONE
}

// An older name for ignored, which the backend may still answer with, as a snapshot reads it.
private const val STATUS_ARCHIVED = "archived"

// A sender that says only whether a mark went: an answer with nothing more in it.
private fun MarkSender.answering() = MarkWriter { id, status, signIn, madeAtMs ->
    if (send(id, status, signIn, madeAtMs)) WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap()) else WriteAnswer.NOT_SENT
}

/** The marks join the outbox's channels (`OutboxModule`). */
@Module
@InstallIn(SingletonComponent::class)
object UnsentMarksChannelModule {
    @Provides
    @IntoSet
    fun channel(marks: UnsentMarks): OutboxChannel = marks
}
