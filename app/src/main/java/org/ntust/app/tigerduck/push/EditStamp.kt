package org.ntust.app.tigerduck.push

import androidx.annotation.WorkerThread
import okhttp3.Call
import okhttp3.Response
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditStamp
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.AnswerArrivals

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
 * before a hold its caller releases lets a newer edit of them go.
 */
internal class FieldAges(private val clocks: DeviceClocks, private val arrivals: AnswerArrivals) {
    /**
     * The stamp of an edit of [fields] made at [madeAt] ([DeviceClocks.stampOf]): its `age_ms`,
     * from the latest arrival of a field's answer that came after it, else from [madeAt]; 0 for an
     * edit made now (null); `hlc: 0` for the oldest.
     */
    fun stampOf(fields: Collection<String>, madeAt: EditTime?): EditStamp =
        clocks.stampOf(madeAt?.let { fields.fold(it) { from, field -> arrivals.ageFrom(field, from) } })

    /**
     * Sends an edit of [fields] made at [madeAt] under [signIn] as `/v4` takes it: [call] builds the
     * request with the edit's stamp ([stampOf]), read as it is built, so a request built again sends
     * a grown age. Sent once: with `age_ms` the backend never refuses an edit as ahead of its clock,
     * and a 409 is the answer [handle] reads. A 2xx answer keeps its arrival under [fields], but
     * for the oldest value: its `hlc: 0` never lands after a later edit, which needs no counting
     * past its answer. Run off the main thread, as the arrival is written before this returns.
     */
    @WorkerThread
    fun <T : Any> send(fields: Collection<String>, madeAt: EditTime?, signIn: SignIn, call: (EditStamp) -> Call, handle: (Response) -> T): T =
        sendKeeping(if (madeAt?.isOldest == true) emptyList() else fields, signIn, { call(stampOf(fields, madeAt)) }, handle)

    /**
     * [send] for a write of several edits, each stamped by [call] itself ([stampOf]): a 2xx answer
     * keeps its arrival under [kept], the fields of those that went with an age. Run off the main
     * thread, as the arrival is written before this returns.
     */
    @WorkerThread
    fun <T : Any> sendKeeping(kept: Collection<String>, signIn: SignIn, call: () -> Call, handle: (Response) -> T): T =
        call().execute().use { response ->
            if (response.isSuccessful) arrivals.arrived(kept, signIn)
            handle(response)
        }
}
