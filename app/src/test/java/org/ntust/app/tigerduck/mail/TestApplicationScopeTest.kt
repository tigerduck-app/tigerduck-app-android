package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [cancelAndJoinBlocking] is what keeps sign-out's cache wipe from outliving a
 * mail test: it has to return only once work already running on the scope has
 * ended, and stop work that would otherwise run on.
 */
class TestApplicationScopeTest {

    @Test
    fun `it returns only after work already running has ended`() {
        val scope = testApplicationScope()
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        // Blocking like File.deleteRecursively: cancellation cannot cut it short.
        scope.launch(Dispatchers.IO) {
            started.countDown()
            Thread.sleep(200)
            finished.set(true)
        }
        started.await()

        scope.cancelAndJoinBlocking()

        assertTrue(finished.get())
    }

    @Test
    fun `it stops work that would otherwise never end`() {
        val scope = testApplicationScope()
        val started = CountDownLatch(1)
        val job = scope.launch {
            started.countDown()
            while (isActive) Thread.sleep(5)
        }
        started.await()

        scope.cancelAndJoinBlocking()

        assertFalse(job.isActive)
    }
}
