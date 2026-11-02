package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.accountEventStream
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries

/**
 * The first pull's gate, per sign-in, over a real sign-in series and the one stream of account
 * events. Waiters run on [Dispatchers.Default], so a release may land at any moment of a wait.
 */
class FirstPullGateTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = accountEventStream()
    private val school = SignInSeries()
    private val gate = FirstPullGate(events, scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** The school's sign-out as the plan tells it: the sign-in ends, then the event goes out. */
    private fun signOut(account: Account = Account.SCHOOL): SignIn {
        val ended = school.end()
        events.tryEmit(AccountEvent.SignedOut(account, SignOutReason.SCHOOL, ended, address = null))
        return ended
    }

    private suspend fun untilWaiting(count: Int) {
        withTimeoutOrNull(10_000) { while (gate.waiting != count) delay(1) }
        assertEquals("callers waiting", count, gate.waiting)
    }

    /** What [this] answered, or null when it has not answered in ten seconds. */
    private suspend fun Deferred<Boolean>.answer(): Boolean? = withTimeoutOrNull(10_000) { await() }

    @Test
    fun `a pull that succeeds opens the gate for its sign-in only`() = runBlocking {
        val first = school.current
        val waiter = scope.async { gate.awaitPull(first) }
        untilWaiting(1)

        gate.notePull(first)

        assertEquals(true, waiter.answer())
        assertTrue(gate.hasPulled(first))
        assertTrue(gate.awaitPull(first))

        signOut()
        school.open()
        val next = school.current
        assertFalse("the gate shuts with the sign-in it opened for", gate.hasPulled(first))
        assertFalse("and is not open for the next one", gate.hasPulled(next))

        // A pull of the one that left opens nothing for the next.
        val nextWaiter = scope.async { gate.awaitPull(next) }
        untilWaiting(1)
        gate.notePull(first)
        assertFalse(gate.hasPulled(next))
        assertEquals(1, gate.waiting)
        gate.notePull(next)
        assertEquals(true, nextWaiter.answer())
        assertEquals(0, gate.waiting)
        assertFalse("open for the one that pulled, and no other", gate.hasPulled(first))
    }

    /** The next sign-in's pull can land before the sign-out's event reaches the gate. */
    @Test
    fun `the next sign-in's pull lets no waiter of the one that ended through`() = runBlocking {
        val first = school.current
        val waiter = scope.async { gate.awaitPull(first) }
        untilWaiting(1)
        val ended = school.end()
        school.open()

        gate.notePull(school.current)
        delay(50)
        assertFalse("released before its sign-out was told", waiter.isCompleted)

        events.tryEmit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, ended, address = null))
        assertEquals(false, waiter.answer())
    }

    @Test
    fun `a sign-out releases a waiter with false`() = runBlocking {
        val signIn = school.current
        val waiter = scope.async { gate.awaitPull(signIn) }
        untilWaiting(1)

        signOut()

        assertEquals(false, waiter.answer())
        assertEquals(0, gate.waiting)
        assertEquals("an ended sign-in waits for nothing", false, withTimeoutOrNull(10_000) { gate.awaitPull(signIn) })
    }

    @Test
    fun `the sign-out of another account releases nothing`() = runBlocking {
        val signIn = school.current
        val waiter = scope.async { gate.awaitPull(signIn) }
        untilWaiting(1)

        events.tryEmit(AccountEvent.SignedOut(Account.MAIL, SignOutReason.MAIL, SignInSeries().current, address = null))
        events.tryEmit(AccountEvent.SignedIn(Account.SCHOOL, byHand = true))
        delay(50)

        assertEquals(1, gate.waiting)
        gate.notePull(signIn)
        assertEquals(true, waiter.answer())
    }

    @Test
    fun `a cancelled waiter leaves none behind`() = runBlocking {
        val signIn = school.current
        val job = scope.launch { gate.awaitPull(signIn) }
        untilWaiting(1)

        job.cancelAndJoin()

        assertEquals(0, gate.waiting)
    }

    @Test
    fun `a pull under a sign-in that ended opens nothing`() = runBlocking {
        val ended = signOut()
        school.open()

        gate.notePull(ended)

        assertFalse(gate.hasPulled(ended))
        assertFalse(gate.hasPulled(school.current))
    }
}
