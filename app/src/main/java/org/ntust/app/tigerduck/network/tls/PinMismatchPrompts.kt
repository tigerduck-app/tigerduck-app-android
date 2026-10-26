package org.ntust.app.tigerduck.network.tls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.shared.tls.PinMismatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The pin-mismatch prompts waiting for the student (TLS pins spec, A6): one at a time, at most
 * once per set per launch, and none for a set within the hour after its Cancel (D5). A mismatch
 * from a background request only queues here; the app's root shows [pending] when it is next in
 * the foreground. A foreground request may wait for the answer through [awaitAnswer].
 */
class PinMismatchPrompts(
    private val store: PinAcceptanceStore,
    private val clock: () -> Long,
    private val onContinue: (PinMismatch) -> Unit,
    /** Whether a mismatch may still be offered (D18); checked when queued, shown and answered. */
    private val eligible: (PinMismatch) -> Boolean = { true },
) {
    private val _pending = MutableStateFlow<PinMismatch?>(null)

    /** The prompt to show, or null. */
    val pending: StateFlow<PinMismatch?> = _pending.asStateFlow()

    private val backlog = LinkedHashMap<String, PinMismatch>()
    private val answered = mutableSetOf<String>()
    private val answers = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    /** Queues [mismatch]'s prompt, unless its set was asked about this launch or is quiet. */
    @Synchronized
    fun queue(mismatch: PinMismatch) {
        val set = mismatch.setId
        if (set in answered || store.isQuiet(set, clock()) || !eligible(mismatch)) return
        if (_pending.value?.setId == set || set in backlog) return
        answers.putIfAbsent(set, CompletableFuture())
        if (_pending.value == null) _pending.value = mismatch else backlog[set] = mismatch
    }

    /**
     * The student's answer for [setId]'s queued prompt, waiting up to [timeoutMs]: true for
     * Continue; false for Cancel, a timeout, or no prompt queued.
     */
    fun awaitAnswer(setId: String, timeoutMs: Long, isCanceled: () -> Boolean = { false }): Boolean {
        val answer = answers[setId] ?: return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // In slices, so a call given up meanwhile stops its wait.
        while (!isCanceled()) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            val slice = minOf(left, TimeUnit.MILLISECONDS.toNanos(WAIT_SLICE_MS))
            try {
                return answer.get(slice, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Not answered yet: look at the call again.
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    /** Continue on the prompt shown. */
    @Synchronized
    fun continueToSite() {
        val mismatch = _pending.value ?: return
        answered += mismatch.setId
        // No longer offered: nothing is stored, as for a Cancel, but no quiet hour starts.
        val stillEligible = eligible(mismatch)
        if (stillEligible) onContinue(mismatch)
        answers.remove(mismatch.setId)?.complete(stillEligible)
        showNext()
    }

    /** Cancel on the prompt shown: nothing is stored, and the set is quiet for an hour. */
    @Synchronized
    fun cancel() {
        val mismatch = _pending.value ?: return
        answered += mismatch.setId
        store.cancelled(mismatch.setId, clock())
        answers.remove(mismatch.setId)?.complete(false)
        showNext()
    }

    /**
     * Retires the prompt waiting to be shown if it is no longer eligible (D18), as when its
     * document lapsed while the app was in the background, and shows the next one that is.
     */
    @Synchronized
    fun revalidate() {
        val shown = _pending.value ?: return
        if (eligible(shown)) return
        answers.remove(shown.setId)?.complete(false)
        showNext()
    }

    private fun showNext() {
        while (true) {
            val next = backlog.entries.firstOrNull()
            if (next == null) {
                _pending.value = null
                return
            }
            backlog.remove(next.key)
            if (eligible(next.value)) {
                _pending.value = next.value
                return
            }
            answers.remove(next.key)?.complete(false)
        }
    }

    private companion object {
        const val WAIT_SLICE_MS = 250L
    }
}
