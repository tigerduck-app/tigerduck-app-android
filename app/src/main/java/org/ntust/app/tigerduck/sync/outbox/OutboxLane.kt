package org.ntust.app.tigerduck.sync.outbox

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.util.logSummary

/**
 * One channel's queue of unsent edits, at most one per field ([LanePolicy.field]), kept in the
 * channel's own file ([LaneStore]) until the backend has them, and sent one request at a time
 * (spec §5.3; `sync-order`, "One send per channel at a time").
 *
 * - **One in flight.** A drain sends the oldest entry first, in the store's order, and the next
 *   only once the reply to the last is stored. A second caller waits for the running send rather
 *   than cancelling it, and an edit kept while a send is under way waits too, so the backend sees
 *   each field's edits in the order they were made. An edit is kept at once all the same: only the
 *   requests wait.
 * - **Each edit once per drain under way.** An edit any drain sends counts as tried for every
 *   drain of its sign-in under way at that moment, so two running together never send one edit
 *   twice, as each tap sent only its own edit before there was one lane; a drain that starts later
 *   tries it again, as a later sync always has. A drain whose sign-in has ended reads nothing, and
 *   counts nothing for the drains of another. A send now ([enqueueAndDrain]) counts as under way from
 *   before its edit is kept, so a drain already running that takes the edit first counts for it.
 * - **On the application scope.** Each request, and the storing of its outcome, runs on [scope],
 *   so a caller cancelled meanwhile never cuts a request short or loses what it answered. A caller
 *   cancelled before its request starts sends nothing.
 * - **Outcomes** ([SendResult]). [SendResult.Sent] takes the entry out, unless a newer edit of its
 *   field replaced it while it was in flight: that one goes next, after the reply (refactor-v4 R4,
 *   R10). The reply's clock is recorded in [AckClock] first, so a pull in between holds the field
 *   by one or the other. [SendResult.NotSent] and [SendResult.Retry] keep it as it is.
 *   [SendResult.Refused] counts one attempt, and at the policy's `maxAttempts` the entry parks:
 *   kept at this phone's value, and not sent until [requeueParked] gives it one more try.
 *   [SendResult.WaitsForUpload] parks it at once. An entry leaves only when sent, superseded or
 *   cleared by a sign-out.
 * - **A requeue may reach the entry in flight**, when its caller asks ([requeueParked]'s
 *   `inFlightToo`). The course overrides' requeue at a landed course upload does: an override's
 *   404 `course_not_found` ([SendResult.WaitsForUpload]) waits for that upload, which may land while
 *   its PATCH is out and have made the row it is refused for. The mark covers the entry whose
 *   request is out, when it is of the requeue's sign-in and due: a reply that would park it (a
 *   refusal that reaches `maxAttempts`, or [SendResult.WaitsForUpload]) leaves it one try short of
 *   parked instead, so it has one more try, as a parked entry has. The mark is honored as the reply
 *   is stored, on [scope], whatever its caller does meanwhile, and goes with it: one requeue is one
 *   more try at most for each entry. Every other requeue (a channel's own wait, its try in a new
 *   process, another channel's upload requeue) reaches parked entries only: a refusal it did not
 *   wait for keeps its channel's wait.
 * - **The sign-in guard.** Entries read from disk belong to the [SignIn] the caller hands in, the
 *   open session's. One read under a sign-in that has ended is neither sent nor written back, and
 *   the reply to a request that crossed a sign-out lands nowhere: the next student's files are not
 *   the ones it was read from.
 * - **Old entries** ([LanePolicy.untimed], X1). An entry an older build wrote with no time is given
 *   its old-entry time as it is read, and written back before anything else writes the file.
 */
class OutboxLane<E : Any>(
    val id: OutboxChannelId,
    private val store: LaneStore<E>,
    private val policy: LanePolicy<E>,
    private val ack: AckClock,
    private val scope: CoroutineScope,
    private val send: suspend (E, SignIn) -> SendResult,
) {
    // A read and write of the store: held for the file's read and write alone, never across a
    // request, so an edit is kept at once while a send is under way.
    private val storeLock = Mutex()

    // The one request in flight: taken by the caller, given back by the send on [scope] once its
    // outcome is stored, so a caller cancelled meanwhile neither cuts it short nor lets a second
    // start beside it.
    private val inFlight = Mutex()

    // Each drain under way, with its sign-in and the edits it has had, read and written under
    // itself: a send adds its edit to every drain here of its own sign-in.
    private val underWay = ArrayList<Drain<E>>()

    // One drain under way: the sign-in it drains under, and the edits it has had, which the sends
    // of that sign-in add to, under [underWay]. Equal only to itself.
    private class Drain<T>(val signIn: SignIn) {
        val tried = ArrayList<T>()
    }

    // The entry whose request is out, set as it is read and taken back as its reply is stored, under
    // [storeLock]: a requeue meanwhile marks it [woken] (see [requeueParked]).
    private var flight: Flight<E>? = null

    // One request out: the entry read for it, under its sign-in, and whether a requeue has woken it.
    private class Flight<T>(val entry: T, val signIn: SignIn) {
        var woken = false
    }

    /**
     * Keeps [entry] under [signIn], in place of an entry waiting for its field: an edit of a field
     * takes the place its waiting edit had, and a field's first edit goes last. Sends nothing. False
     * when it was not kept, as under an ended sign-in.
     */
    suspend fun enqueue(entry: E, signIn: SignIn): Boolean = edit(signIn) { entries -> replacing(entries, entry) }

    /**
     * A send now: keeps [entry] under [signIn], as [enqueue] does, and when [sends] then says so,
     * drains its field. Under way from before the entry is kept, so a drain already running that
     * sends this edit first does not leave it to be sent again here. False when it was not kept.
     */
    suspend fun enqueueAndDrain(entry: E, signIn: SignIn, sends: () -> Boolean): Boolean =
        keepAndDrain(signIn, sends) { if (enqueue(entry, signIn)) setOf(policy.field(entry)) else null }

    /**
     * A send now of what a channel keeps itself, [keep]: its own write, which may keep nothing (a
     * newer edit outbids it, the edit stays this phone's own) and may take the channel's own locks
     * (two files written as one), returning the fields it kept, or null for none. Under way from
     * before it is kept, as [enqueueAndDrain], and drains those fields when [sends] then says so.
     * False when nothing was kept.
     */
    suspend fun keepAndDrain(signIn: SignIn, sends: () -> Boolean, keep: suspend () -> Set<String>?): Boolean {
        val drain = startDrain(signIn)
        try {
            val fields = keep()?.takeIf { it.isNotEmpty() } ?: return false
            if (sends()) drainWith(signIn, fields, drain)
            return true
        } finally {
            endDrain(drain)
        }
    }

    /**
     * Writes [change] of the entries under [signIn], in one step with the lane's own writes: what a
     * channel drops or rewrites itself (a sign-in's forgetting, a server's answer taken) goes
     * through here. True when the store holds the change.
     */
    suspend fun edit(signIn: SignIn, change: (List<E>) -> List<E>): Boolean = storeLock.withLock {
        if (!signIn.isCurrent) return@withLock false
        val entries = loaded(signIn)
        val changed = change(entries)
        changed == entries || (signIn.isCurrent && store.save(changed, signIn))
    }

    /** The entries waiting, parked ones included, oldest first. */
    suspend fun entries(): List<E> = storeLock.withLock { loaded(null) }

    /** The fields an entry waits for, parked ones included: a pull keeps this phone's value of each (LWW D12). */
    suspend fun heldFields(): Set<String> = entries().mapTo(HashSet(), policy.field)

    /**
     * Records that [entry]'s send under [signIn] landed, answered at the backend's clock
     * [serverClockUs], as its field's acknowledgment. The lane does this as it stores a
     * [SendResult.Sent], before the entry goes. A channel whose send lets the entry go itself, or
     * clears what a pull reads as it waiting, records it first too, so a pull in between holds the
     * field by one or the other. Recording it twice changes nothing.
     */
    fun acknowledge(entry: E, serverClockUs: Long?, signIn: SignIn) {
        serverClockUs?.let { ack.acknowledged(policy.field(entry), it, signIn) }
    }

    /**
     * Sends what waits under [signIn], oldest first, each entry once, and an entry edited again
     * while its last edit was in flight after that edit's reply. Only the [fields] given, when
     * given. Parked entries stay. Returns once nothing it may send waits, at the first
     * [SendResult.Retry] when the policy [stops there][LanePolicy.stopsOnRetry], or once [signIn]
     * has ended.
     */
    suspend fun drain(signIn: SignIn, fields: Set<String>? = null) {
        val drain = startDrain(signIn)
        try {
            drainWith(signIn, fields, drain)
        } finally {
            endDrain(drain)
        }
    }

    // [drain] under way, the edits it has had in it, which every send of its sign-in adds to.
    private suspend fun drainWith(signIn: SignIn, fields: Set<String>?, drain: Drain<E>) {
        while (signIn.isCurrent) {
            val result = sendNext(signIn, fields, drain) ?: return
            if (result == SendResult.Retry && policy.stopsOnRetry) return
        }
    }

    // A drain under [signIn] starts, with none of the edits had, which every send of that sign-in
    // from now on adds to.
    private fun startDrain(signIn: SignIn): Drain<E> = Drain<E>(signIn).also { drain -> synchronized(underWay) { underWay += drain } }

    // A drain ends: no send adds to it any more.
    private fun endDrain(drain: Drain<E>) {
        synchronized(underWay) { underWay.remove(drain) }
    }

    /**
     * Gives each parked entry that is [due] one more try at the next [drain]: one refusal parks it
     * again. A course upload has landed, or the channel's own wait for a parked entry is over. With
     * [inFlightToo], the entry in flight under [signIn], when [due], has the same: a reply that
     * would park it leaves it due for one more try.
     */
    suspend fun requeueParked(signIn: SignIn, inFlightToo: Boolean = false, due: (E) -> Boolean = { true }) {
        storeLock.withLock {
            if (!signIn.isCurrent) return@withLock
            if (inFlightToo) flight?.takeIf { it.signIn === signIn && due(it.entry) }?.woken = true
            val entries = loaded(signIn)
            val book = policy.attempts
            val requeued = entries.indices.filter { book.attempts(entries[it]) >= policy.maxAttempts && due(entries[it]) }.toSet()
            if (requeued.isEmpty()) return@withLock
            // Counted while the sign-in stands, in one step with its end; see [countedWhileCurrent].
            val updated = countedWhileCurrent(signIn) {
                entries.mapIndexed { i, entry -> if (i in requeued) book.counted(entry, policy.maxAttempts - 1, refused = false) else entry }
            } ?: return@withLock
            if (updated != entries) store.save(updated, signIn)
        }
    }

    /** Forgets the attempts counted in memory: at a sign-out, whose clearing takes the entries. */
    fun forgetMemory() = policy.attempts.forget()

    // The next entry [drain] has not had, sent under [signIn] once no other request is in flight,
    // and its outcome stored; null when none is due or the sign-in has ended.
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun sendNext(signIn: SignIn, fields: Set<String>?, drain: Drain<E>): SendResult? {
        inFlight.lock()
        // ATOMIC: once the lock is taken, the block runs to give it back, whatever has been cancelled.
        val request = scope.async(start = CoroutineStart.ATOMIC) {
            try {
                // Read as the request is about to go, so an edit kept while the last was in flight goes now.
                // Ended while it waited for the request before: what the store holds now may be the
                // next student's, which it neither reads nor counts.
                if (!signIn.isCurrent) return@async null
                val entry = storeLock.withLock {
                    loaded(signIn).firstOrNull { it.isDue(fields, drain) }?.also { flight = Flight(it, signIn) }
                } ?: return@async null
                // Had by every drain of this sign-in under way, this one included: none of them sends
                // it again. A drain of another sign-in is another student's, which this was not read for.
                synchronized(underWay) { underWay.forEach { if (it.signIn === signIn) it.tried += entry } }
                if (!signIn.isCurrent) return@async null
                val result = sent(entry, signIn)
                settle(entry, result, signIn)
                result
            } finally {
                inFlight.unlock()
            }
        }
        return request.await()
    }

    // What [send] answered; a failure the channel did not classify keeps the entry as it is.
    private suspend fun sent(entry: E, signIn: SignIn): SendResult = try {
        send(entry, signIn)
    } catch (e: CancellationException) {
        // The scope's own cancellation goes on; a timeout of the channel's is a failed request.
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "$id: a send was cut short, kept: ${e.logSummary()}")
        SendResult.Retry
    } catch (e: Exception) {
        Log.w(TAG, "$id: a send failed unclassified, kept: ${e.logSummary()}")
        SendResult.Retry
    }

    // Stores what [result] does to [entry], unless [signIn] has ended: then the files are no
    // longer the ones it was read from. Takes back its flight, and with it a requeue's mark.
    private suspend fun settle(entry: E, result: SendResult, signIn: SignIn) {
        val field = policy.field(entry)
        // Before the entry goes, so a pull in between holds the field by one or the other.
        if (result is SendResult.Sent) acknowledge(entry, result.serverClockUs, signIn)
        storeLock.withLock {
            val woken = flight?.takeIf { it.entry === entry }?.woken == true
            flight = null
            if (result == SendResult.NotSent || result == SendResult.Retry) return@withLock
            if (!signIn.isCurrent) return@withLock
            val entries = loaded(signIn)
            val book = policy.attempts
            // The edit that went, still waiting: a newer edit of the field is not the one answered.
            val at = entries.indexOfFirst { policy.field(it) == field && book.sameEdit(it, entry) }
            if (at < 0) return@withLock
            val stored = entries[at]
            val updated = when (result) {
                is SendResult.Sent -> entries.filterIndexed { i, _ -> i != at }
                SendResult.Refused, SendResult.WaitsForUpload -> countedWhileCurrent(signIn) {
                    val attempts = book.attempts(stored)
                    val counted = if (result == SendResult.Refused) attempts + 1 else maxOf(attempts, policy.maxAttempts)
                    // Woken while out: one try short of parked, its one more try.
                    val kept = if (woken) minOf(counted, policy.maxAttempts - 1) else counted
                    entries.replacedAt(at, book.counted(stored, kept, refused = true))
                } ?: return@withLock
                SendResult.NotSent, SendResult.Retry -> return@withLock
            }
            if (result !is SendResult.Sent && book.attempts(updated[at]) >= policy.maxAttempts) {
                Log.w(TAG, "$id: an edit parked (${if (result == SendResult.Refused) "refused ${policy.maxAttempts} times" else "waits for a course upload"})")
            }
            if (updated != entries) store.save(updated, signIn)
        }
    }

    // Runs [count], which may count in memory, only while [signIn] stands, in one step with its end
    // (SignIn.ifCurrent): a sign-out that forgets the counts in memory ([forgetMemory]) while the store
    // was read, or as a count was read, then never finds the departing student's count put back,
    // for the next student's equal entry to inherit. Null when the sign-in has ended.
    private fun <T> countedWhileCurrent(signIn: SignIn, count: () -> T): T? = signIn.ifCurrent(count)

    // Under [storeLock]. The entries as stored, each an older build wrote with no time given its
    // old-entry time, and written back under [signIn] before anything else writes the file, so no
    // later write can move it (X1). Read with no sign-in, the times are given and not written.
    private suspend fun loaded(signIn: SignIn?): List<E> {
        val entries = store.load()
        val untimed = policy.untimed ?: return entries
        if (entries.none { untimed.timeOf(it) == null }) return entries
        val oldEntryTimeMs = untimed.oldEntryTimeMs()
        val filled = entries.map { if (untimed.timeOf(it) == null) untimed.withTime(it, oldEntryTimeMs) else it }
        if (signIn != null && signIn.isCurrent) store.save(filled, signIn)
        return filled
    }

    // Under its field, the same edit as one [drain] has had counts as had, whichever field the
    // channel's sameEdit looks at.
    private fun E.isDue(fields: Set<String>?, drain: Drain<E>): Boolean {
        val field = policy.field(this)
        if (fields != null && field !in fields) return false
        if (policy.attempts.attempts(this) >= policy.maxAttempts) return false
        return synchronized(underWay) { drain.tried.none { policy.field(it) == field && policy.attempts.sameEdit(it, this) } }
    }

    // [entries] with [entry] in place of the first waiting for its field, and no other of that field.
    private fun replacing(entries: List<E>, entry: E): List<E> {
        val field = policy.field(entry)
        val out = ArrayList<E>(entries.size + 1)
        var placed = false
        for (e in entries) {
            if (policy.field(e) != field) {
                out += e
            } else if (!placed) {
                out += entry
                placed = true
            }
        }
        if (!placed) out += entry
        return out
    }

    private fun List<E>.replacedAt(at: Int, entry: E): List<E> = toMutableList().also { it[at] = entry }

    private companion object {
        const val TAG = "OutboxLane"
    }
}
