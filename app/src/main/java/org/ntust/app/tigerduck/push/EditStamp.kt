package org.ntust.app.tigerduck.push

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Response
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.sync.SyncClock

// The backend's TigerSync clock for a time in epoch ms: whole microseconds since the epoch.
internal fun clockOf(epochMs: Long): Long = epochMs * 1_000

// 409 `clock_ahead`: a time more than 5 s ahead of the backend's clock. Peeked, so a caller that
// goes on to read the body still can.
internal fun isClockAhead(response: Response): Boolean = response.code == 409 && BackendError.of(response).isClockAhead

/** [body] with [hlc] as its `hlc`. */
internal fun Map<String, Any>.stampedWith(hlc: Long): Map<String, Any> = this + ("hlc" to hlc)

/**
 * The clock a write is stamped with: [SyncClock], whose stamps only grow and are capped by the
 * backend's time as last heard (`tigersync-clock`). A client a test builds without one stamps
 * with the wall clock and hears nothing.
 */
internal class WriteClock(private val clock: SyncClock?) {
    /** The stamp of an edit made now, in epoch ms. */
    fun stampMs(): Long = clock?.stampMs() ?: System.currentTimeMillis()

    /**
     * A stamp again, after a refusal as ahead of [refused] whose answer carried [serverClockUs]; null
     * when [refused] went to a backend the endpoint has moved off since ([SyncClock.restampAfterClockAhead]).
     */
    fun restampAfterClockAhead(serverClockUs: Long?, refused: HttpUrl): Long? =
        if (clock == null) System.currentTimeMillis() else clock.restampAfterClockAhead(serverClockUs, refused)

    /** A clock value an answer to [from] holds, which raises the last value seen (LWW D4). */
    fun hear(clockUs: Long, from: HttpUrl) {
        clock?.heardInSnapshot(clockUs, from)
    }

    /** The largest clock value [answer] holds, to [from]; see [hear]. */
    fun hear(answer: WriteAnswer, from: HttpUrl) {
        answer.clockValues().maxOrNull()?.let { hear(it, from) }
    }
}

/**
 * Sends an edit as `/v4` takes it, always stamped (422 `hlc_required` otherwise): with [madeAtMs],
 * the time it was made, or, with none, [clock]'s stamp now. Only a send made now reaches here
 * without a time (Use Local, the conflict dialog, a first-sync mark, the `moodle_course_id`
 * fill-in): a queued entry has its own, or the old-entry time it was given at load. One refused as
 * ahead of the backend's clock (409 `clock_ahead`) is stamped again from the refusal's clock and
 * sent once more if [mayResend] still holds, never without a stamp: the gate the first request
 * passed can have closed meanwhile (a hold, a switch), and the resend, past it, would overwrite
 * what the user is yet to choose. Then nothing more goes and the result is null. So it is for a
 * refusal from a backend the endpoint has moved off while the request was out: its clock says
 * nothing of the next backend. A second refusal is the answer [handle] reads, as any other.
 */
internal fun <T : Any> sendStamped(
    madeAtMs: Long?,
    clock: WriteClock,
    mayResend: () -> Boolean,
    call: (hlc: Long) -> Call,
    handle: (Response) -> T,
): T? {
    val first = call(clockOf(madeAtMs ?: clock.stampMs())).execute()
    if (!isClockAhead(first)) return first.use(handle)
    val serverClockUs = serverClockOf(first)
    val refused = first.request.url
    first.close()
    if (!mayResend()) return null
    val restamp = clock.restampAfterClockAhead(serverClockUs, refused) ?: return null
    return call(clockOf(restamp)).execute().use(handle)
}

/** [response]'s answer ([answerOf]), every clock value in it heard. */
internal fun WriteClock.answer(response: Response, what: String, field: String? = null): WriteAnswer =
    answerOf(response, what, field).also { hear(it, response.request.url) }
