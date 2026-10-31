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
 * counting from when it arrives, as the course upload's adds do. [call] makes the request for the
 * `hlc` it is given; [handle] reads the answer of the request that stands.
 */
internal fun <T> executeStamped(madeAtMs: Long?, call: (hlc: Long?) -> Call, handle: (Response) -> T): T {
    if (madeAtMs != null) {
        var ahead = false
        val handled = call(clockOf(madeAtMs)).execute().use { response ->
            if (isClockAhead(response)) {
                ahead = true
                null
            } else {
                handle(response)
            }
        }
        @Suppress("UNCHECKED_CAST")
        if (!ahead) return handled as T
    }
    return call(null).execute().use(handle)
}
