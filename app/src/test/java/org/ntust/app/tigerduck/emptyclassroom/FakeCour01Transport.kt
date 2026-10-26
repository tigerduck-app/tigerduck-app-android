package org.ntust.app.tigerduck.emptyclassroom

import kotlinx.coroutines.CompletableDeferred

/** Answers cour01's requests in the order they come, each reply optionally held until its gate opens. */
class FakeCour01Transport : Cour01Transport {
    data class Sent(val method: String, val fields: Map<String, String>)

    private class Reply(val html: String?, val gate: CompletableDeferred<Unit>?, val error: Throwable? = null)

    val sent = mutableListOf<Sent>()
    private val replies = ArrayDeque<Reply>()

    fun serve(html: String?, gate: CompletableDeferred<Unit>? = null) {
        replies.addLast(Reply(html, gate))
    }

    /** The next request fails with [error], as the real transport fails for a 5xx or a dropped connection. */
    fun fail(error: Throwable) {
        replies.addLast(Reply(null, null, error))
    }

    override suspend fun get(url: String): String? = reply(Sent("GET", emptyMap()))

    override suspend fun post(url: String, fields: List<Pair<String, String>>): String? =
        reply(Sent("POST", fields.toMap()))

    private suspend fun reply(request: Sent): String? {
        sent += request
        val reply = replies.removeFirstOrNull() ?: error("no reply queued for request ${sent.size}")
        reply.gate?.await()
        reply.error?.let { throw it }
        return reply.html
    }
}
