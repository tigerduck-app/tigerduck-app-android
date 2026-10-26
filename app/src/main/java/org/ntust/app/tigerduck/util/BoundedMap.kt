package org.ntust.app.tigerduck.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * School requests a fetch has in flight at once. Past the per-host connection
 * limit they queue, and each one's timeout runs while it waits; as iOS's
 * `AppServiceBridge.maxConcurrentRequests`.
 */
const val MAX_CONCURRENT_SCHOOL_REQUESTS = 4

/** [transform] over [items], at most [limit] at a time, the results in [items]' order. */
suspend fun <T, R> mapAtMost(limit: Int, items: List<T>, transform: suspend (T) -> R): List<R> {
    val permits = Semaphore(limit)
    return coroutineScope {
        items.map { item -> async { permits.withPermit { transform(item) } } }.awaitAll()
    }
}
