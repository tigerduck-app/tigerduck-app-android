package org.ntust.app.tigerduck.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * One request per key at a time, its answer handed to every caller that asks
 * for the same key while it is running and for [windowMs] after it lands.
 *
 * Home, the class table, the calendar and the background worker each fetch
 * the same school data through the same services, and on launch they do it
 * within seconds of each other. Not always in step, though: Home talks to
 * the backend first, so its Moodle call can start after the class table's
 * has already returned. Sharing only the requests in flight would miss
 * those; the window catches them.
 *
 * A failed request is shared with the callers waiting on it, so a school
 * server that is down costs one timeout rather than one per screen, and
 * nothing is kept: the next caller tries again.
 *
 * Requests run on [scope], so a caller that is cancelled while waiting
 * leaves the request going for everyone else. [now] is a monotonic clock.
 */
class SharedFetch<K : Any, V>(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val now: () -> Long,
) {
    private class Landed<V>(val value: V, val atMs: Long)

    private val lock = Any()
    private val inFlight = HashMap<K, Deferred<V>>()
    private val landed = HashMap<K, Landed<V>>()

    suspend fun get(key: K, fetch: suspend () -> V): V {
        val request = synchronized(lock) {
            landed[key]?.takeIf { now() - it.atMs < windowMs }?.let { return it.value }
            inFlight[key] ?: start(key, fetch)
        }
        return request.await()
    }

    /**
     * Forgets every answer that has landed, so the next caller asks the
     * server again — for a refresh the user asked for, which has to show
     * what is there now. A request still running is shared as before: it
     * started recently enough to count as now.
     */
    fun expire() {
        synchronized(lock) { landed.clear() }
    }

    /** The keys whose answers are kept, for tests. */
    internal fun keptKeys(): Set<K> = synchronized(lock) { landed.keys.toSet() }

    // Lazy so the request cannot run inside the lock; await() starts it.
    private fun start(key: K, fetch: suspend () -> V): Deferred<V> {
        lateinit var request: Deferred<V>
        request = scope.async(start = CoroutineStart.LAZY) {
            try {
                fetch().also { value -> synchronized(lock) { land(key, value) } }
            } finally {
                synchronized(lock) { if (inFlight[key] === request) inFlight.remove(key) }
            }
        }
        inFlight[key] = request
        return request
    }

    // Under the lock. Kept only while it can still be handed out: with no
    // window that is never, and an answer past its window is dropped here,
    // since a key that is not asked for again would otherwise hold it for
    // the life of the process.
    private fun land(key: K, value: V) {
        if (windowMs <= 0) return
        val atMs = now()
        landed.values.removeAll { atMs - it.atMs >= windowMs }
        landed[key] = Landed(value, atMs)
    }
}
