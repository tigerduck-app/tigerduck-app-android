package org.ntust.app.tigerduck.sync.outbox

import kotlinx.coroutines.CompletableDeferred
import org.ntust.app.tigerduck.shared.account.SignIn

/** One queued edit of a test lane: [value] set on [field] at [madeAtMs], with its refusals counted in it. */
internal data class Edit(
    val field: String,
    val value: String,
    val madeAtMs: Long? = 1L,
    val attempts: Int = 0,
    val refusedAtMs: Long = 0L,
)

/** Counts kept in the entry, as the marks keep theirs on disk. */
internal fun onDisk(refusedAtMs: () -> Long = { 99L }) = OnDiskAttempts<Edit>(
    attempts = { it.attempts },
    counted = { e, n, refused -> e.copy(attempts = n, refusedAtMs = if (refused) refusedAtMs() else e.refusedAtMs) },
    sameEdit = { a, b -> a.field == b.field && a.value == b.value && a.madeAtMs == b.madeAtMs },
)

/**
 * A queue file in memory, which takes every write, an ended sign-in's too, and names each: what
 * a lane must not write, this does not stop for it.
 */
internal class FakeStore(initial: List<Edit> = emptyList()) : LaneStore<Edit> {
    @Volatile
    var entries: List<Edit> = initial
    val saves = mutableListOf<Pair<List<Edit>, SignIn>>()

    /** When set, the next read waits for it before it reads: a file read that takes its time. */
    var holdNextLoad: CompletableDeferred<Unit>? = null

    override suspend fun load(): List<Edit> {
        holdNextLoad?.let {
            holdNextLoad = null
            it.await()
        }
        return entries
    }

    override suspend fun save(entries: List<Edit>, signIn: SignIn): Boolean {
        saves += entries to signIn
        this.entries = entries
        return true
    }
}

/**
 * The backend, as a lane's send: each request recorded with its sign-in, answered at once by
 * [answer] when it names an answer, else held until the test completes it ([pending]).
 */
internal class FakeBackend {
    val sent = mutableListOf<Pair<Edit, SignIn>>()
    val pending = ArrayDeque<CompletableDeferred<SendResult>>()
    var answer: (Edit) -> SendResult? = { null }
    var inFlight = 0
    var mostInFlight = 0
    var cancelled = 0

    val values: List<String> get() = sent.map { it.first.value }

    suspend fun send(edit: Edit, signIn: SignIn): SendResult {
        sent += edit to signIn
        inFlight++
        mostInFlight = maxOf(mostInFlight, inFlight)
        try {
            return answer(edit) ?: CompletableDeferred<SendResult>().also { pending.addLast(it) }.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled++
            throw e
        } finally {
            inFlight--
        }
    }

    /** Answers the oldest request still waiting. */
    fun reply(result: SendResult) {
        pending.removeFirst().complete(result)
    }
}
