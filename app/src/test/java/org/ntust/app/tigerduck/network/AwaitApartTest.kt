package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [awaitApart]: a caller cancelled while its call ran never takes the call's answer. */
class AwaitApartTest {

    @Test
    fun `a caller cancelled while the call ran never takes its answer, though the call finished`() = runBlocking {
        var taken = false
        var cancelledCalls = 0
        val caller = launch {
            val callerJob = currentCoroutineContext().job
            // Unconfined from here, the call runs to its end at once, before the caller awaits it:
            // the answer is there without the caller suspending.
            val answer = awaitApart(onCancel = { cancelledCalls++ }, dispatcher = Dispatchers.Unconfined) {
                callerJob.cancel()
                "SSO's answer"
            }
            taken = answer.isNotEmpty()
        }
        caller.join()

        assertFalse(taken)
        assertTrue(caller.isCancelled)
        assertEquals(1, cancelledCalls)
    }

    @Test
    fun `a caller cancelled before the call starts never starts it`() = runBlocking {
        var ran = false
        val caller = launch {
            currentCoroutineContext().job.cancel()
            try {
                awaitApart(onCancel = {}, dispatcher = Dispatchers.Unconfined) { ran = true }
            } catch (e: CancellationException) {
                throw e
            }
        }
        caller.join()

        assertFalse(ran)
    }

    @Test
    fun `an answer the caller is still there for is handed over`() = runBlocking {
        assertEquals("SSO's answer", awaitApart(onCancel = {}) { "SSO's answer" })
        assertTrue((coroutineContext[Job] ?: error("no job")).isActive)
    }
}
