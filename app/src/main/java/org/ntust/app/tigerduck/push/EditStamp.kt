package org.ntust.app.tigerduck.push

import okhttp3.Call
import okhttp3.Response
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditStamp
import org.ntust.app.tigerduck.sync.EditTime

/** The field, or the query parameter, an edit's age goes in, where `hlc` went (backend 4.3.0). */
internal const val AGE_MS = "age_ms"

/** The field, or the query parameter, the oldest value's stamp 0 goes in ([EditStamp.Oldest]). */
internal const val HLC = "hlc"

/** [this] stamp as a write's fields: `age_ms`, or the oldest's `hlc: 0`, exactly one of them. */
internal fun EditStamp.fields(): Map<String, Long> = listOfNotNull(ageMs?.let { AGE_MS to it }, hlc?.let { HLC to it }).toMap()

/**
 * [this] body with the stamp of the edit made at [madeAt], read by [clocks] as the request is built
 * ([DeviceClocks.stampOf]): its `age_ms`, 0 for an edit made now (null), or `hlc: 0` for the oldest.
 * Every `/v4` write sends one (422 `hlc_required` otherwise), and the backend stamps the edit with
 * its arrival less its age.
 */
internal fun Map<String, Any>.stampedFrom(madeAt: EditTime?, clocks: DeviceClocks): Map<String, Any> =
    this + clocks.stampOf(madeAt).fields()

/**
 * Sends an edit as `/v4` takes it: [call] builds the request with the edit's stamp, read as it is
 * built, so a retry, a request built again, sends a grown age. Sent once: with `age_ms` the
 * backend never refuses an edit as ahead of its clock, and a 409 is the answer [handle] reads.
 */
internal fun <T : Any> sendAged(call: () -> Call, handle: (Response) -> T): T = call().execute().use(handle)
