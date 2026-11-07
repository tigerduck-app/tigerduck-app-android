package org.ntust.app.tigerduck.push

import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.Response
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditStamp
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.EditFloors
import java.util.concurrent.ConcurrentHashMap

/** The field, or the query parameter, an edit's age goes in, where `hlc` went (backend 4.3.0). */
internal const val AGE_MS = "age_ms"

/** The field, or the query parameter, the oldest value's stamp 0 goes in ([EditStamp.Oldest]). */
internal const val HLC = "hlc"

/** [this] stamp as a write's fields: `age_ms`, or the oldest's `hlc: 0`, exactly one of them. */
internal fun EditStamp.fields(): Map<String, Long> = listOfNotNull(ageMs?.let { AGE_MS to it }, hlc?.let { HLC to it }).toMap()

/** [this] body with [stamp]'s fields ([fields]). Every `/v4` write sends one (422 `hlc_required` otherwise). */
internal fun Map<String, Any>.stampedWith(stamp: EditStamp): Map<String, Any> = this + stamp.fields()

/**
 * Every write of a synced field goes through here, from an outbox lane or straight from a screen
 * (Use Local, a conflict's keep-local), with the fields it writes ([SyncField], a course by the
 * key the write names): the per-key rule of `age_ms`, as the iPhone's (tigerduck-app#1008).
 *
 * - A write's span runs from its first age reading, the token in hand, to its 2xx answer's arrival.
 * - An edit ages from the latest end among the spans of its key that went out at or before it was
 *   made: an edit made before a span went out, as one queued offline, keeps its own age and loses to
 *   it, which it is older than. A queue keeps that end with each of its edits ([EditFloors], raised
 *   on disk as the span ends); this keeps the spans of this process too, for an edit read before.
 * - A key ([SyncField.spanKey]): an assignment's mark, a holiday's choice, or a term, every course
 *   write of which, its reset's too, goes under one.
 * - The writes of one key go one at a time, from the age read to the end kept, a screen's and a
 *   lane's alike, so no two read the spans at once; writes of other keys go at once. In memory: the
 *   writes that race are this process's.
 *
 * No write replays itself after a 401: the sync signs in again and sends anew, a span of its own.
 */
internal class FieldAges(private val clocks: DeviceClocks, private val floors: () -> EditFloors?) {
    // One lock per key written in this process, taken in the keys' order.
    private val writing = ConcurrentHashMap<String, Mutex>()

    // Each key's latest spans that ended in this process, oldest first; read and written under its lock.
    private val ended = ConcurrentHashMap<String, ArrayDeque<Span>>()

    private class Span(val start: EditTime, val end: EditTime)

    /**
     * Sends an edit of [fields] made at [madeAt] (the later of its time and its floor, for a queued
     * one) under [signIn] as `/v4` takes it: [call] builds the request with the edit's stamp, read as
     * it is built, so a request built again sends a grown age; 0 for an edit made now (null), `hlc: 0`
     * for the oldest. Sent once: a 409 is the answer [handle] reads. A 2xx answer ends the write's
     * span, but the oldest's, whose `hlc: 0` never lands after a later edit. Run off the main thread,
     * as the floors are written before this returns.
     */
    suspend fun <T : Any> send(
        fields: Collection<String>, madeAt: EditTime?, signIn: SignIn, call: (EditStamp) -> Call, handle: (Response) -> T,
    ): T = sendEach(fields, spans = madeAt?.isOldest != true, signIn, { stampOf -> call(stampOf(madeAt)) }, handle)

    /**
     * [send] for a write of several edits of [fields], each stamped by [call] with the stamping it
     * is handed, read at the span's start. Its 2xx answer ends a span of each key when [spans].
     */
    suspend fun <T : Any> sendEach(
        fields: Collection<String>, spans: Boolean, signIn: SignIn, call: ((EditTime?) -> EditStamp) -> Call, handle: (Response) -> T,
    ): T {
        val keys = fields.map(SyncField::spanKey).distinct().sorted()
        return holding(keys) {
            val start = clocks.now()
            call { madeAt -> clocks.stampOf(madeAt?.let { if (it.isOldest) it else floored(keys, it) }, start) }.execute().use { response ->
                if (response.isSuccessful && spans) endSpans(keys, start, signIn)
                handle(response)
            }
        }
    }

    // [madeAt], or the end of the latest span of one of [keys] that went out at or before it.
    private fun floored(keys: List<String>, madeAt: EditTime): EditTime = keys.fold(madeAt) { from, key ->
        from.atLeast(ended[key]?.lastOrNull { !madeAt.isBefore(it.start) }?.end)
    }

    // The span of each of [keys] that went out at [start] ended now: kept here, and its edits' floors raised on disk.
    private suspend fun endSpans(keys: List<String>, start: EditTime, signIn: SignIn) {
        val end = clocks.now()
        for (key in keys) {
            ended.getOrPut(key) { ArrayDeque() }.apply {
                addLast(Span(start, end))
                while (size > KEPT_SPANS) removeFirst()
            }
            floors()?.raise(key, start, end, signIn)
        }
    }

    // [block] under the lock of each of [keys], taken in their order, so two writes never wait on each other.
    private suspend fun <T> holding(keys: List<String>, block: suspend () -> T): T {
        val held = ArrayList<Mutex>(keys.size)
        try {
            for (key in keys) {
                val lock = writing.computeIfAbsent(key) { Mutex() }
                lock.lock()
                held += lock
            }
            return block()
        } finally {
            held.asReversed().forEach { it.unlock() }
        }
    }

    private companion object {
        /**
         * How many of a key's spans this process keeps: an edit read before its write took the lock
         * finds its floor here among the spans that ended meanwhile, at most one per writer ahead of
         * it, as the lock is fair; a queue's own floor covers the rest.
         */
        const val KEPT_SPANS = 64
    }
}
