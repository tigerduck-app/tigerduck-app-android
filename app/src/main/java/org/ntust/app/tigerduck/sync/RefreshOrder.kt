package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The order of a refresh that pulls the backend and fetches the school (refactor-v4 D13; iOS
 * `RefreshOrder.run`): the two start together, the school's data is applied only after the pull
 * has been, and then the outbox. What the school's data changed goes up after that, as
 * [SchoolUploads] holds it for the first pull. So an edit another device made lands here before
 * a roster or a list fetched meanwhile is applied over it, and the fetch adds no wait of its own
 * to the pull's round trip.
 *
 * `sync-order`, "The school answers first": its result waits for the pull. "The backend is
 * unreachable": a pull that failed still lets the school's data show; what it derived waits for a
 * pull that lands. `RefreshOrderTest` holds the order.
 */
object RefreshOrder {

    /**
     * Runs [pull], then [pushOutbox], beside [fetchSchool], which calls its `afterPull` before it
     * applies anything it fetched: that returns once both are done. A cancellation of the refresh
     * cancels the pull, whose outbox step still runs, and `afterPull` then throws it, so the fetch
     * applies nothing. A school fetch that throws lets the pull finish before the refresh throws,
     * as the pull used to finish before the fetch began.
     */
    suspend fun run(
        pull: suspend () -> Unit,
        pushOutbox: suspend () -> Unit,
        fetchSchool: suspend (afterPull: suspend () -> Unit) -> Unit,
    ) = coroutineScope {
        val pulled = async {
            try {
                pull()
            } finally {
                // As iOS's: the outbox goes after the pull however it ended, a cancelled one's too.
                withContext(NonCancellable) { pushOutbox() }
            }
        }
        try {
            fetchSchool { pulled.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pulled.await()
            throw e
        }
        pulled.await()
    }
}
