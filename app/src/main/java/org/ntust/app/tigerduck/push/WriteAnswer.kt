package org.ntust.app.tigerduck.push

import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.ntust.app.tigerduck.network.SyncClockInterceptor
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.sync.isPlausibleClockUs
import java.io.IOException

/**
 * What the backend answered a sync write, for the outbox to settle the edit by (LWW D12): whether
 * it went at all ([sent]), the backend's clock as the reply's `X-TigerSync-Clock` carried it
 * ([serverClockUs]), which fields the backend took ([applied]), and the values the reply names
 * ([values]), in the contract's shape (`docs/api-v4.md`).
 *
 * [applied] holds the reply's own list where it gives one (a course's override); where it gives a
 * single verdict, the field the write sets ([LOCAL_STATUS], [NOTIFY], [MEMBERSHIP], [RESET]) when
 * the backend took it, and nothing when it kept a later one. Null when the reply says neither.
 * [values] are the reply's top-level fields, JSON objects as maps and arrays as lists, a JSON null
 * kept as a null value under its name. A refusal is thrown, a [BackendException] that keeps the
 * backend's code.
 */
data class WriteAnswer(
    val sent: Boolean,
    val serverClockUs: Long?,
    val applied: Set<String>?,
    val values: Map<String, Any?>,
) {
    companion object {
        /** Nothing went: a switch, a hold or the caller's own check stopped it. */
        val NOT_SENT = WriteAnswer(sent = false, serverClockUs = null, applied = null, values = emptyMap())

        /** An assignment's mark, in [applied]. */
        const val LOCAL_STATUS = "local_status"

        /** A holiday's choice, in [applied]. */
        const val NOTIFY = "notify"

        /** A course's place in the list, hidden or removed by a delete, in [applied]. */
        const val MEMBERSHIP = "membership"

        /** A term's or every term's reset, in [applied]. */
        const val RESET = "reset"

        /**
         * [response]'s answer, a 2xx, to a write that sets [field] when its reply gives a single
         * verdict. An answer whose body cannot be read still went: it says only its clock.
         */
        internal fun of(response: Response, field: String? = null): WriteAnswer {
            val values = runCatching { plain(JSONObject(response.body.string())) as Map<*, *> }.getOrNull()
                ?.entries?.associate { (name, value) -> name.toString() to value }.orEmpty()
            val applied = when (val verdict = values["applied"]) {
                is List<*> -> verdict.filterIsInstance<String>().toSet()
                is Boolean -> field?.let { if (verdict) setOf(it) else emptySet() }
                else -> null
            }
            return WriteAnswer(sent = true, serverClockUs = serverClockOf(response), applied = applied, values = values)
        }

        private fun plain(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { plain(value.opt(it)) }
            is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
            JSONObject.NULL -> null
            else -> value
        }
    }
}

/** Every clock value the reply names: each `hlc` and `…_hlc`, a map's (`custom_names_hlc`) included. */
internal fun WriteAnswer.clockValues(): Sequence<Long> = values.asSequence()
    .filter { (name, _) -> name == "hlc" || name.endsWith("_hlc") }
    .flatMap { (_, value) -> if (value is Map<*, *>) value.values.asSequence() else sequenceOf(value) }
    .mapNotNull { (it as? Number)?.toLong() }

/** The backend's clock [response] carries (`X-TigerSync-Clock`); null without one that can be a time. */
internal fun serverClockOf(response: Response): Long? =
    response.header(SyncClockInterceptor.CLOCK_HEADER)?.trim()?.toLongOrNull()?.takeIf(::isPlausibleClockUs)

/**
 * [response]'s answer, by [WriteAnswer.of]; a refusal is thrown with the backend's code kept
 * ([BackendException]), its message saying [what] failed with which status, as it always has.
 */
internal fun answerOf(response: Response, what: String, field: String? = null): WriteAnswer {
    if (!response.isSuccessful) throw BackendException(BackendError.of(response), "$what failed: HTTP ${response.code}")
    return WriteAnswer.of(response, field)
}

/**
 * A course as a request names it: its key, `client:{semester}:{course_no}`, which the caller may
 * not know.
 */
data class CourseRef(val courseKey: String?) {
    /**
     * The course as a path names it, sent raw as the key may hold a `/`; for [what], a
     * [CourseKeyUnknownException] when the caller does not know its key.
     */
    internal fun named(what: String): String = courseKey ?: throw CourseKeyUnknownException("$what names no course key")
}

/**
 * A course write that cannot name its course by its key, as iOS's
 * `CourseKeyUnknownError`: nothing was sent. An [IOException], so a queue that takes it for no
 * answer keeps the edit, uncounted, until the course can be named.
 */
class CourseKeyUnknownException(message: String) : IOException(message)
