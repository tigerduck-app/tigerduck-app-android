package org.ntust.app.tigerduck.push

import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject

// The backend's TigerSync clock for a time in epoch ms: whole microseconds since the epoch.
internal fun clockOf(epochMs: Long): Long = epochMs * 1_000

// 409 `clock_ahead`: a time more than 5 s ahead of the backend's clock. Peeked, so a caller
// that goes on to read the body still can.
internal fun isClockAhead(response: Response): Boolean = response.code == 409 &&
    runCatching { JSONObject(response.peekBody(4_096).string()).optString("detail") == "clock_ahead" }
        .getOrDefault(false)

/** [body] with [hlc] as its `hlc`, or as it was when [hlc] is null. */
internal fun Map<String, Any>.stampedWith(hlc: Long?): Map<String, Any> =
    if (hlc == null) this else this + ("hlc" to hlc)

/**
 * Sends an edit with the time it was made as its `hlc` ([madeAtMs], epoch ms; null sends none), which
 * the backend settles it by: an edit that waited in a queue then loses to a newer one another device
 * sent meanwhile. One the backend refuses as stamped ahead of its clock goes once more without,
 * counting from when it arrives, as the course upload's adds do, if [mayResend] still says yes:
 * the gate the first request passed can have closed meanwhile (a hold, a switch), and the resend,
 * past it, would overwrite what the user is yet to choose. Then nothing more goes out, and the
 * result is null: not sent. [call] makes the request for the `hlc` it is given; [handle] reads the
 * answer of the request that stands.
 */
internal fun <T : Any> executeStamped(
    madeAtMs: Long?,
    mayResend: () -> Boolean,
    call: (hlc: Long?) -> Call,
    handle: (Response) -> T,
): T? {
    if (madeAtMs != null) {
        val stamped = call(clockOf(madeAtMs)).execute()
        if (!isClockAhead(stamped)) return stamped.use(handle)
        stamped.close()
        if (!mayResend()) return null
    }
    return call(null).execute().use(handle)
}
