package org.ntust.app.tigerduck.push

import okhttp3.Call
import okhttp3.Response
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime

/** The field, or the query parameter, an edit's age goes in, where `hlc` went (backend 4.3.0). */
internal const val AGE_MS = "age_ms"

/**
 * [this] body with the age of the edit made at [madeAt] as its `age_ms`, read by [clocks] as the
 * request is built ([DeviceClocks.ageMs]): 0 for an edit made now (null). Every `/v4` write sends
 * one (422 `hlc_required` otherwise), and the backend stamps the edit with its arrival less it.
 */
internal fun Map<String, Any>.agedFrom(madeAt: EditTime?, clocks: DeviceClocks): Map<String, Any> =
    this + (AGE_MS to clocks.ageMs(madeAt))

/**
 * Sends an edit as `/v4` takes it: [call] builds the request with the edit's age, read as it is
 * built, so a retry, a request built again, sends a grown one. Sent once: with `age_ms` the
 * backend never refuses an edit as ahead of its clock, and a 409 is the answer [handle] reads.
 */
internal fun <T : Any> sendAged(call: () -> Call, handle: (Response) -> T): T = call().execute().use(handle)
