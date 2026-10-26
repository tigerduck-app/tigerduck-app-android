package org.ntust.app.tigerduck.wear.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Runs the work handed to it one piece at a time, in the order it was handed
 * over, in [scope]. The Data Layer's callbacks come in order, but a coroutine
 * launched for each can finish out of it: an older schedule's write would
 * then land after a newer one's and stay, with the accent or courses the
 * phone has since replaced.
 *
 * A piece that fails goes to [onFailure], and the pieces after it still run:
 * one queue serves every payload, so one bad payload must not stop the rest.
 */
internal class InArrivalOrder(
    scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (work in queue) {
                try {
                    work()
                } catch (e: CancellationException) {
                    // The scope's own end ends the queue; a cancellation of the
                    // work's own, as a timeout inside it, is a failure like any.
                    ensureActive()
                    onFailure(e)
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
            // Also when the scope ends before this ever starts: work handed over
            // after that is refused rather than kept with nothing to run it.
        }.invokeOnCompletion { queue.cancel() }
    }

    /** Hands [work] over; false once the scope is gone, when it will never run. */
    fun submit(work: suspend () -> Unit): Boolean = queue.trySend(work).isSuccess
}
