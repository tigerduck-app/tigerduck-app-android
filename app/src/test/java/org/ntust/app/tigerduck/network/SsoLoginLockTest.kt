@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [lockedUnlessStale], the turn an SSO login waits for before it may sign an account in. */
class SsoLoginLockTest {

    @Test
    fun `a login still wanted once it holds the lock runs`() = runTest {
        var ran = false
        val result = Mutex().lockedUnlessStale({ true }, stale = false) { ran = true; true }
        assertTrue(result)
        assertTrue(ran)
    }

    @Test
    fun `a login its caller gave up on while it waited never runs`() = runTest {
        val lock = Mutex()
        var wanted = true
        var ran = false
        lock.lock()

        val waiting = async { lock.lockedUnlessStale({ wanted }, stale = false) { ran = true; true } }
        runCurrent()
        // The account signs out while another login holds the lock.
        wanted = false
        lock.unlock()

        assertFalse(waiting.await())
        assertFalse(ran)
        assertEquals(false, lock.isLocked)
    }
}
