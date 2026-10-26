package org.ntust.app.tigerduck.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * At most one run of a refresh at a time, shared by everyone who asks for it
 * while it is running.
 *
 * A screen's refresh is asked for from several places, and often at the same
 * moment: the first composition, a sign-in, a pull, a language switch. Each
 * of those used to start a full fetch of its own, so a cold start ran the
 * school pipeline twice per screen.
 *
 * Runs are launched on [scope], not on the caller's coroutine: a caller that
 * is cancelled while waiting stops waiting, and the run it shared with
 * everyone else carries on.
 */
class SingleFlight(private val scope: CoroutineScope) {

    private val lock = Any()
    private var current: Job? = null

    // Bumped by cancel(), so a rerun that was waiting when it came does not
    // go on to start a run of its own.
    private var cancels = 0

    /** Waits for the run in progress, or starts one and waits for that. */
    suspend fun join(block: suspend () -> Unit) {
        val job = synchronized(lock) {
            current?.takeIf { it.isPending } ?: start(block)
        }
        job.join()
    }

    /**
     * Runs [block] once more after the run in progress, for a caller whose
     * request has to see a later state than that run started from: a
     * different term, a different language, a cache that was just reset.
     *
     * Waits for that run instead of cancelling it, so two runs never write
     * the cache at once. A rerun someone else started while this one waited
     * began after this request, so it is joined rather than run again. A
     * [cancel] while it waited drops it: the request belonged to what was
     * cancelled, a signed-out account's language switch, say.
     */
    suspend fun rerun(block: suspend () -> Unit) {
        val (running, generation) = synchronized(lock) {
            current?.takeIf { it.isPending } to cancels
        }
        running?.join()
        val job = synchronized(lock) {
            if (cancels != generation) return
            current?.takeIf { it.isPending && it !== running } ?: start(block)
        }
        job.join()
    }

    /**
     * Runs [block] as a run of its own once nothing else is running: for work
     * that must neither overlap a run nor be joined in place of one, such as
     * a reset that wipes what a run in progress would otherwise save back.
     * Waits out every run that starts while it waits, and callers that join
     * while it runs wait for it. Like [rerun], a [cancel] while it waited
     * drops it.
     */
    suspend fun runAlone(block: suspend () -> Unit) {
        val generation = synchronized(lock) { cancels }
        while (true) {
            val (running, mine) = synchronized(lock) {
                if (cancels != generation) return
                val pending = current?.takeIf { it.isPending }
                pending to (if (pending == null) start(block) else null)
            }
            if (mine != null) return mine.join()
            running?.join()
        }
    }

    /** Cancels the run in progress, if there is one, and any rerun waiting on it. */
    fun cancel() {
        synchronized(lock) {
            cancels++
            current?.cancel()
        }
    }

    // Lazy, so the block cannot start running inside the lock: on an
    // immediate dispatcher, launch would run it on the spot. join() starts it.
    private fun start(block: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.LAZY) { block() }.also { current = it }

    // A lazy job not yet started is not isActive, but it is about to run; a
    // cancelled one still unwinding is not isCompleted, but it will not
    // produce anything worth waiting for.
    private val Job.isPending: Boolean get() = !isCompleted && !isCancelled
}
