package org.ntust.app.tigerduck.push

import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.Response
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditStamp
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.AnswerArrivals
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
 * key the write names): the per-key rule of `age_ms` at both of its ends ([AnswerArrivals]). An
 * edit's stamp, read by [clocks] as its request is built ([stampOf]), counts its age from the
 * arrival of the answer to an earlier write of a field it writes when that came after the edit,
 * and a write's 2xx answer keeps its arrival under each of its fields before [send] returns, so
 * before a hold its caller releases lets a newer edit of them go. A course's membership counts
 * from its term's reset too ([SyncField.termResetOf]).
 *
 * The writes of one field go one at a time, from the read of the stamp to the arrival kept, a
 * screen's and a lane's alike: two at once would each read the arrivals before either answer came,
 * and the older, slower one could land last. A membership write waits for its term's reset too.
 * The writes of other fields go at once. In memory: the writes that race are this process's.
 */
internal class FieldAges(private val clocks: DeviceClocks, private val arrivals: AnswerArrivals) {
    // One lock per field written in this process, taken in the fields' order.
    private val writing = ConcurrentHashMap<String, Mutex>()

    /**
     * The stamp of an edit of [fields] made at [madeAt] ([DeviceClocks.stampOf]): its `age_ms`,
     * from the latest arrival of a field's answer that came after it, else from [madeAt]; 0 for an
     * edit made now (null); `hlc: 0` for the oldest. Read under the fields' locks ([send]).
     */
    fun stampOf(fields: Collection<String>, madeAt: EditTime?): EditStamp =
        clocks.stampOf(madeAt?.let { basis(fields).fold(it) { from, field -> arrivals.ageFrom(field, from) } })

    /**
     * Sends an edit of [fields] made at [madeAt] under [signIn] as `/v4` takes it: [call] builds the
     * request with the edit's stamp ([stampOf]), read as it is built, so a request built again sends
     * a grown age. Sent once: with `age_ms` the backend never refuses an edit as ahead of its clock,
     * and a 409 is the answer [handle] reads. A 2xx answer keeps its arrival under [fields], but
     * for the oldest value: its `hlc: 0` never lands after a later edit, which needs no counting
     * past its answer. Run off the main thread, as the arrival is written before this returns.
     */
    suspend fun <T : Any> send(
        fields: Collection<String>, madeAt: EditTime?, signIn: SignIn, call: (EditStamp) -> Call, handle: (Response) -> T,
    ): T = sendKeeping(fields, if (madeAt?.isOldest == true) emptyList() else fields, signIn, { call(stampOf(fields, madeAt)) }, handle)

    /**
     * [send] for a write of several edits of [fields], each stamped by [call] itself ([stampOf]): a
     * 2xx answer keeps its arrival under [kept], the fields of those that went with an age. One at a
     * time with every other write of [fields], and of their terms' resets for a membership.
     */
    suspend fun <T : Any> sendKeeping(
        fields: Collection<String>, kept: Collection<String>, signIn: SignIn, call: () -> Call, handle: (Response) -> T,
    ): T = holding(basis(fields)) {
        call().execute().use { response ->
            if (response.isSuccessful) arrivals.arrived(kept, signIn)
            handle(response)
        }
    }

    // [fields] and the reset of the term of each membership among them.
    private fun basis(fields: Collection<String>): List<String> = (fields + fields.mapNotNull(SyncField::termResetOf)).distinct()

    // [block] under the lock of each of [fields], taken in their order, so two writes never wait on each other.
    private suspend fun <T> holding(fields: List<String>, block: () -> T): T {
        val held = ArrayList<Mutex>(fields.size)
        try {
            for (field in fields.sorted()) {
                val lock = writing.computeIfAbsent(field) { Mutex() }
                lock.lock()
                held += lock
            }
            return block()
        } finally {
            held.asReversed().forEach { it.unlock() }
        }
    }
}
