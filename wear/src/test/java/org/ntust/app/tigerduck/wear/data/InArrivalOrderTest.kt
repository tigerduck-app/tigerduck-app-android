package org.ntust.app.tigerduck.wear.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * [InArrivalOrder], which the Data Layer listener hands each callback's
 * payloads to: an older schedule must never be stored over a newer one.
 */
class InArrivalOrderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val done = Collections.synchronizedList(mutableListOf<String>())
    private val failures = Collections.synchronizedList(mutableListOf<Exception>())

    @After
    fun tearDown() = scope.cancel()

    private suspend fun awaitDone(count: Int) = withTimeout(5_000) { while (done.size < count) delay(10) }

    @Test
    fun `work runs in the order it came, even when the first takes longer`() = runBlocking {
        val inOrder = InArrivalOrder(scope)
        val older = CompletableDeferred<Unit>()

        inOrder.submit {
            older.await()
            done += "older"
        }
        inOrder.submit { done += "newer" }
        delay(100)
        assertTrue("nothing ran ahead of the older work", done.isEmpty())
        older.complete(Unit)

        awaitDone(2)
        assertEquals(listOf("older", "newer"), done.toList())
    }

    @Test
    fun `a piece of work that fails is reported and the rest still run`() = runBlocking {
        val inOrder = InArrivalOrder(scope) { failures += it }

        inOrder.submit { throw IllegalStateException("a bad payload") }
        // Its own timeout, not the queue's end.
        inOrder.submit { withTimeout(1) { delay(1_000) } }
        inOrder.submit { done += "after" }

        awaitDone(1)
        assertEquals(listOf("after"), done.toList())
        assertEquals(
            listOf(IllegalStateException::class.java, TimeoutCancellationException::class.java),
            failures.map { it.javaClass },
        )
    }

    @Test
    fun `work handed over once the scope is gone is refused`() = runBlocking {
        val inOrder = InArrivalOrder(scope)
        scope.cancel()
        delay(50)

        assertFalse(inOrder.submit { done += "late" })
        delay(50)
        assertTrue(done.isEmpty())
    }
}
