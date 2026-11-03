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
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.SyncPolicy
import org.ntust.app.tigerduck.sync.outbox.InMemoryAttempts
import org.ntust.app.tigerduck.sync.outbox.LanePolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.sync.outbox.UntimedEntries
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** Sends one holiday's choice as sign-in [signIn], and says only whether it went; for a test that sends through one. */
internal fun interface HolidayOverrideSender {
    suspend fun send(holidayId: Int, notify: Boolean, signIn: SignIn, madeAtMs: Long?): Boolean
}

/** Sends one holiday's choice as sign-in [signIn], and says what the backend answered; see [PushApiClient.putHolidayOverrideAnswered]. */
internal fun interface HolidayOverrideWriter {
    suspend fun write(holidayId: Int, notify: Boolean, signIn: SignIn, madeAtMs: Long?): WriteAnswer
}

/**
 * Holiday choices ([AppPreferences.holidayNotifyOverrides]) whose PUT the
 * backend has not acknowledged, by holiday id
 * ([AppPreferences.holidayOverridesAwaitingUpload]): the outbox's holidays
 * channel.
 *
 * A PUT went once, and a failure, as offline, was only logged: the choice
 * stayed on this phone alone, and the next full sync, which took the server's
 * set over this phone's, took it back. Now a choice is marked as waiting
 * before its PUT and let go only once the backend holds the value the user
 * still wants. Every full sync sends what waits first, and its snapshot keeps
 * the waiting choices as this phone has them ([merged]).
 *
 * The choices go through an [OutboxLane]: one PUT at a time, oldest first,
 * each reading the choice as it is sent, so a retry sends today's value and
 * the server keeps the user's last. A refused choice is never parked: every
 * sync sends it again, as it always has. Each is pinned to the sign-in it
 * was read under: the sign-out clears both sets
 * ([AppPreferences.forgetHolidayOverrides]), a PUT still waiting for its turn
 * then sends nothing, and one that lands is let go in a step that ends
 * before the sign-in does ([SignIn.ifCurrent]), so it never writes the
 * departing account's set back after the clear. As the iPhone's
 * `enqueueHolidayUpload` and `unacknowledgedHolidayIDs`.
 *
 * With TigerSync off on `/v3` a choice is this phone's own and goes nowhere;
 * on `/v4` it is kept and goes once TigerSync is on (LWW D12). Each choice is
 * stamped by the sync clock ([stamp]), which on `/v3` is the wall clock as it
 * always was. One an older build left waiting with no time goes without one
 * on `/v3`, as it always has; on `/v4` it is given this build's first run as
 * its time at its first load there and written back (X1), never its send
 * time. On `/v4` a reply that says the backend kept a later choice puts that
 * choice on this phone, unless the user has chosen again since (LWW D12).
 */
@Singleton
class PendingHolidayOverrides internal constructor(
    private val prefs: AppPreferences,
    private val writer: HolidayOverrideWriter,
    private val syncs: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    // Whether a choice is kept to send: whether it goes, on /v3.
    private val keeps: () -> Boolean = syncs,
    // The time a choice is stamped with: the sync clock's, which is the wall clock on /v3.
    private val stamp: () -> Long = clock,
    // Whether the backend settles each choice by its stamp, as /v4 does.
    private val lastWriterWins: () -> Boolean = { false },
    ack: AckClock = AckClock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val oldEntryTime: OldEntryTime? = null,
    // Where the backend's winning choice is written; with none, it is left to the next pull.
    private val calendar: AcademicCalendarStore? = null,
    // The Calendar's thread, which its switch writes a choice on.
    private val main: CoroutineContext = Dispatchers.Main,
) : OutboxChannel {

    /** The queue for a test that sends through a [HolidayOverrideSender], as the queue's own tests do. */
    internal constructor(
        prefs: AppPreferences,
        sender: HolidayOverrideSender,
        syncs: () -> Boolean,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(prefs, sender.answering(), syncs, clock)

    @Inject constructor(
        prefs: AppPreferences,
        pushApiClient: PushApiClient,
        policies: SyncPolicies = SyncPolicies(prefs),
        /** What a choice is stamped by; null only in tests, for the wall clock. */
        syncClock: SyncClock? = null,
        ack: AckClock = AckClock(),
        @ApplicationScope scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        /** The old-entry time of a choice an older build left with no time; null only in tests, for none. */
        oldEntryTime: OldEntryTime? = null,
        /** Where a choice the backend kept is written; null only in tests, which leave it to the next pull. */
        calendar: AcademicCalendarStore? = null,
    ) : this(
        prefs = prefs,
        writer = HolidayOverrideWriter(pushApiClient::putHolidayOverrideAnswered),
        syncs = { policies.current.tigerSyncOn },
        keeps = { policies.current.keepsHolidays },
        stamp = syncClock?.let { it::stampMs } ?: System::currentTimeMillis,
        lastWriterWins = { policies.current.lastWriterWins },
        ack = ack,
        scope = scope,
        oldEntryTime = oldEntryTime,
        calendar = calendar,
    )

    /**
     * One holiday's choice as it waits: when it was made (null for one made before the time was
     * kept). Its value is read as it is sent, so a retry sends what the user wants now.
     */
    private data class Choice(val id: Int, val madeAtMs: Long?)

    override val id: OutboxChannelId get() = OutboxChannelId.HOLIDAYS

    // The waiting set's and the edit times' read-modify-writes.
    private val lock = Any()

    // PUTs asked and not finished: a snapshot fetched meanwhile may predate one.
    private val inFlight = AtomicInteger(0)

    // The last choice made or PUT landed: a snapshot fetched before either may not hold it.
    @Volatile
    private var lastChangeAtMs = 0L

    private val lane = OutboxLane(
        id = OutboxChannelId.HOLIDAYS,
        store = Waiting(),
        policy = LanePolicy(
            field = { choice: Choice -> SyncField.holiday(choice.id) },
            maxAttempts = NEVER_PARKED,
            attempts = InMemoryAttempts { choice: Choice -> SyncField.holiday(choice.id) },
            untimed = UntimedEntries(
                // On /v3 a choice with no time goes without one, as it always has, so it is given none.
                timeOf = { choice -> choice.madeAtMs ?: if (lastWriterWins()) null else SENT_WITHOUT_TIME },
                withTime = { choice, atMs -> choice.copy(madeAtMs = atMs) },
                // Kept in preferences other edits write too, so their file's time says nothing:
                // only this build's first run counts.
                oldEntryTimeMs = { oldEntryTime?.oldEntryTimeMs(null) ?: 0L },
            ),
        ),
        ack = ack,
        scope = scope,
        send = ::sendNow,
    )

    /** The holidays whose last choice has not reached the server. */
    val waitingIds: Set<Int> get() = prefs.holidayOverridesAwaitingUpload

    /**
     * The user just wrote [holidayId]'s choice into the local set: its time
     * kept, and the choice kept as waiting and sent. Asked before this
     * suspends ([merged] applies no snapshot meanwhile), and kept only while
     * [signIn] stands: kept after the sign-out's clear, it would go up as the
     * next account's.
     */
    suspend fun send(holidayId: Int, signIn: SignIn) {
        lastChangeAtMs = clock()
        val madeAtMs = stamp()
        // Before the keep check: a choice still waiting from before is this one now, and goes up
        // with this time whenever it goes.
        signIn.ifCurrent {
            synchronized(lock) { prefs.holidayOverrideEditTimes = prefs.holidayOverrideEditTimes + (holidayId to madeAtMs) }
        }
        if (!keeps()) return
        inFlight.incrementAndGet()
        try {
            lane.enqueueAndDrain(Choice(holidayId, madeAtMs), signIn, syncs)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Sends every choice still waiting; each full sync starts with this. */
    suspend fun retry(signIn: SignIn) = drain(signIn)

    /** Sends every choice still waiting, oldest first, unless TigerSync is off. */
    override suspend fun drain(signIn: SignIn) {
        if (!syncs()) return
        lane.drain(signIn)
    }

    /** Nothing waits for a course upload: a choice is never parked. */
    override suspend fun requeueParked(signIn: SignIn) = lane.requeueParked(signIn)

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    override fun forgetMemory() = lane.forgetMemory()

    /**
     * The set a full sync fetched at [fetchedAtMs] ([snapshot]), with the
     * choices still waiting kept as this phone has them ([local]); null when
     * the snapshot must not be applied at all: fetched before the last choice
     * or the last PUT that landed, or while a PUT is under way, it may not hold
     * that choice yet, and the next sync settles it.
     */
    fun merged(snapshot: Set<Int>, fetchedAtMs: Long, local: Set<Int>): Set<Int>? {
        if (inFlight.get() > 0 || fetchedAtMs <= lastChangeAtMs) return null
        val waiting = waitingIds
        return snapshot - waiting + (local intersect waiting)
    }

    /**
     * The holidays whose choice a `/v4` pull keeps as this phone has it (LWW D12), read in the step
     * that writes the pull's: each waiting to go up, and each chosen after the snapshot began (its
     * time later than [serverHlc], in µs), whose choice may not be waiting yet.
     */
    fun keptOver(serverHlc: Long): Set<Int> =
        waitingIds + prefs.holidayOverrideEditTimes.filterValues { it > serverHlc / 1000 }.keys

    /**
     * Forgets the edit times of [holidayIds], whose choice a pull just replaced: the value is the
     * server's now, and a time kept for it would stamp a PUT of someone else's choice.
     */
    fun forgetEditTimes(holidayIds: Set<Int>) {
        if (holidayIds.isEmpty()) return
        synchronized(lock) { prefs.holidayOverrideEditTimes = prefs.holidayOverrideEditTimes - holidayIds }
    }

    // The lane's send of [choice] under [signIn], read as it was about to go: what became of it.
    private suspend fun sendNow(choice: Choice, signIn: SignIn): SendResult {
        inFlight.incrementAndGet()
        try {
            // Read after its time, which the lane read: see [Waiting].
            val notify = choice.id in prefs.holidayNotifyOverrides
            val answer = try {
                writer.write(choice.id, notify, signIn, choice.madeAtMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "holiday override upload failed: ${e.logSummary()}")
                // A choice is never parked, so a refusal keeps it as an outage does.
                return SendResult.afterFailure(e)
            }
            // Not sent, as with TigerSync turned off before its turn (the client's own gate): it still waits.
            if (!answer.sent) return SendResult.NotSent
            lastChangeAtMs = clock()
            // Changed during the request with no choice made, which would have a time of its own:
            // the backend holds the older value, and this one waits for the next sync, as a choice
            // changed under its PUT always has.
            if (changedUnder(choice, notify)) return SendResult.Retry
            if (lastWriterWins()) takeWinner(choice, answer, signIn)
            return SendResult.Sent(answer.serverClockUs)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    // LWW D12, a write response's winner: the backend kept a later choice than [choice], so this
    // phone takes it, through the calendar, which tells its pages. Only while [choice] is still the
    // one waiting: a choice made since has a time of its own, goes next, and its own answer settles
    // it. On the main thread, where the Calendar's switch writes a choice and then its time without
    // a pause, and under the lane's lock with the choice let go, in one step: no switch comes
    // between the check and the write, and nothing sends the taken value as this phone's. A value
    // changed with no choice made is the snapshot's, which no pull applies while a PUT is under way
    // ([merged]), and this runs inside one.
    private suspend fun takeWinner(choice: Choice, answer: WriteAnswer, signIn: SignIn) {
        val applied = answer.applied ?: return
        if (WriteAnswer.NOTIFY in applied) return
        val notify = answer.values[WriteAnswer.NOTIFY] as? Boolean ?: return
        val store = calendar ?: return
        withContext(main) {
            lane.edit(signIn) { waiting ->
                if (choice !in waiting) return@edit waiting
                val taken = signIn.ifCurrent {
                    synchronized(lock) {
                        store.setNotifyOnHoliday(choice.id, notify)
                        // The value is the server's now, as after a pull that replaced it.
                        prefs.holidayOverrideEditTimes = prefs.holidayOverrideEditTimes - choice.id
                        true
                    }
                } == true
                if (taken) waiting - choice else waiting
            }
        }
    }

    // Whether [choice]'s value is no longer [sent] while its time still is: changed, with no
    // choice made. The time is read first, as [Waiting] reads it.
    private fun changedUnder(choice: Choice, sent: Boolean): Boolean {
        val madeAtMs = prefs.holidayOverrideEditTimes[choice.id]
        return madeAtMs == choice.madeAtMs && (choice.id in prefs.holidayNotifyOverrides) != sent
    }

    // The waiting set as the lane reads and writes it, each choice with its time; its value is read
    // after it, as it is sent. A tap writes the value and then the time, so a tap between these two
    // reads pairs its new value with the old time, which that tap's own PUT then corrects, and
    // never an old value with the new time, which nothing would.
    private inner class Waiting : LaneStore<Choice> {
        override suspend fun load(): List<Choice> = synchronized(lock) {
            val times = prefs.holidayOverrideEditTimes
            prefs.holidayOverridesAwaitingUpload.map { Choice(it, times[it]) }
        }

        // The waiting set, and a time only for a choice none is kept for: the old-entry time it was
        // just given (X1). A tap writes its own time before it is kept, which this never moves.
        override suspend fun save(entries: List<Choice>, signIn: SignIn): Boolean = signIn.ifCurrent {
            synchronized(lock) {
                prefs.holidayOverridesAwaitingUpload = entries.mapTo(LinkedHashSet()) { it.id }
                val times = prefs.holidayOverrideEditTimes
                val given = entries.mapNotNull { choice -> choice.madeAtMs?.takeIf { choice.id !in times }?.let { choice.id to it } }
                if (given.isNotEmpty()) {
                    prefs.holidayOverrideEditTimes = times + given
                }
                true
            }
        } == true
    }

    private companion object {
        const val TAG = "HolidayOverrides"

        // A refused choice is never parked: every sync sends it again, as it always has.
        const val NEVER_PARKED = Int.MAX_VALUE

        // What a choice with no time reads as on /v3, where it goes without one: a time it needs none of.
        const val SENT_WITHOUT_TIME = 0L
    }
}

/**
 * Whether a holiday's choice is kept to send. Holidays have no switch of their own and no
 * re-enable check, so it is TigerSync's switch, as [SyncPolicy.sends] answers a category; under
 * last writer wins it is kept whatever that says, as [SyncPolicy.keeps] keeps a category's edit.
 */
internal val SyncPolicy.keepsHolidays: Boolean get() = flavorSyncs && (lastWriterWins || tigerSyncOn)

// A sender that says only whether a choice went: an answer with nothing more in it.
private fun HolidayOverrideSender.answering() = HolidayOverrideWriter { id, notify, signIn, madeAtMs ->
    if (send(id, notify, signIn, madeAtMs)) WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap()) else WriteAnswer.NOT_SENT
}

/** The holiday choices join the outbox's channels (`OutboxModule`). */
@Module
@InstallIn(SingletonComponent::class)
object HolidayChannelModule {
    @Provides
    @IntoSet
    fun channel(holidays: PendingHolidayOverrides): OutboxChannel = holidays
}
