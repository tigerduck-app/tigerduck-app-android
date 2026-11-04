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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

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
 * the waiting choices as this phone has them ([keptOver]).
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
 * A choice made while TigerSync is off is kept and goes once it is on (LWW
 * D12). Each choice is stamped by the sync clock ([stamp]). One an older build
 * left waiting with no time is given this build's first run as its time at its
 * first load and written back (X1), never its send time. A reply that says the
 * backend kept a later choice puts that choice on this phone, unless the user
 * has chosen again since (LWW D12).
 */
@Singleton
class PendingHolidayOverrides internal constructor(
    private val prefs: AppPreferences,
    private val writer: HolidayOverrideWriter,
    private val syncs: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    // Whether a choice is kept to send ([SyncPolicy.keepsHolidays]).
    private val keeps: () -> Boolean = { true },
    // The time a choice is stamped with: the sync clock's.
    private val stamp: () -> Long = clock,
    ack: AckClock = AckClock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val oldEntryTime: OldEntryTime? = null,
    // Where the backend's winning choice is written; with none, it is left to the next pull.
    private val calendar: AcademicCalendarStore? = null,
    // The Calendar's thread, which its switch writes a choice on.
    private val main: CoroutineContext = Dispatchers.Main,
) : OutboxChannel {

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

    private val lane = OutboxLane(
        id = OutboxChannelId.HOLIDAYS,
        store = Waiting(),
        policy = LanePolicy(
            field = { choice: Choice -> SyncField.holiday(choice.id) },
            maxAttempts = NEVER_PARKED,
            attempts = InMemoryAttempts { choice: Choice -> SyncField.holiday(choice.id) },
            untimed = UntimedEntries(
                timeOf = { choice -> choice.madeAtMs },
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
     * kept, before this suspends, so a pull keeps the choice from then on
     * ([keptOver]), and the choice kept as waiting and sent, only while
     * [signIn] stands: kept after the sign-out's clear, it would go up as the
     * next account's.
     */
    suspend fun send(holidayId: Int, signIn: SignIn) {
        val madeAtMs = stamp()
        // Before the keep check: a choice still waiting from before is this one now, and goes up
        // with this time whenever it goes.
        signIn.ifCurrent {
            synchronized(lock) { prefs.holidayOverrideEditTimes = prefs.holidayOverrideEditTimes + (holidayId to madeAtMs) }
        }
        if (!keeps()) return
        lane.enqueueAndDrain(Choice(holidayId, madeAtMs), signIn, syncs)
    }

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
     * The holidays whose choice a pull keeps as this phone has it (LWW D12), read in the step
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
        // Changed during the request with no choice made, which would have a time of its own:
        // the backend holds the older value, and this one waits for the next sync, as a choice
        // changed under its PUT always has.
        if (changedUnder(choice, notify)) return SendResult.Retry
        takeWinner(choice, answer, signIn)
        return SendResult.Sent(answer.serverClockUs)
    }

    // LWW D12, a write response's winner: the backend kept a later choice than [choice], so this
    // phone takes it, through the calendar, which tells its pages. Only while [choice] is still the
    // one waiting: a choice made since has a time of its own, goes next, and its own answer settles
    // it. On the main thread, where the Calendar's switch writes a choice and then its time without
    // a pause, and under the lane's lock with the choice let go, in one step: no switch comes
    // between the check and the write, and nothing sends the taken value as this phone's. A value
    // changed with no choice made is a pull's, which applies on the same thread and keeps a choice
    // still waiting, as this one is until let go here ([keptOver]).
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
    }
}

/** The holiday choices join the outbox's channels (`OutboxModule`). */
@Module
@InstallIn(SingletonComponent::class)
object HolidayChannelModule {
    @Provides
    @IntoSet
    fun channel(holidays: PendingHolidayOverrides): OutboxChannel = holidays
}
