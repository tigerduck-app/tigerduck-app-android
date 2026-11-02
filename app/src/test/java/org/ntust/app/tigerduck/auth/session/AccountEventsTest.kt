package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.SignedInState
import org.ntust.app.tigerduck.shared.account.SignInSeries

/** The one stream of account events: who hears what, in what order, whatever a reactor is doing. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountEventsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val series = SignInSeries()
    private val stream = accountEventStream()
    private val session = AccountSession(series, scope, stream = stream)
    private val state = SignedInState(true).also { session.signedInFrom(it.signedIn) }

    @After
    fun stop() = scope.cancel()

    private fun signOut() = state.set(false) { changed ->
        if (changed) session.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, series.current, null))
    }

    private fun signIn() = state.set(true) { changed ->
        if (changed) session.emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = false))
    }

    private fun run() = dispatcher.scheduler.runCurrent()

    /** Each change as [AccountSession.changes] tells it, collected as a page does. */
    private fun followChanges(): MutableList<Boolean> {
        val seen = mutableListOf<Boolean>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.changes.collect { seen += it } }
        return seen
    }

    @Test
    fun `a reactor hears what lands before it first runs`() {
        val seen = mutableListOf<AccountEvent>()
        scope.follow(session.events) { seen += it }

        // Its dispatcher has not run it once.
        signOut()
        signIn()
        run()

        assertEquals(2, seen.size)
        assertTrue(seen[0] is AccountEvent.SignedOut)
        assertTrue(seen[1] is AccountEvent.SignedIn)
    }

    @Test
    fun `however far behind a reactor is, it hears every event in order, and telling never fails or waits`() {
        val fast = mutableListOf<Int>()
        val slow = mutableListOf<Int>()
        val held = CompletableDeferred<Unit>()
        fun mark(i: Int) = AccountEvent.StudentChanged(Account.MAIL, "$i")
        fun number(event: AccountEvent) = (event as AccountEvent.StudentChanged).address!!.toInt()
        scope.follow(stream) { fast += number(it) }
        scope.follow(stream) {
            held.await()
            slow += number(it)
        }

        val told = (0 until 20_000).map { stream.tryEmit(mark(it)) }
        run()

        assertTrue("every telling landed", told.all { it })
        assertEquals("one that keeps up is held by nothing", (0 until 20_000).toList(), fast)
        assertEquals(emptyList<Int>(), slow)
        held.complete(Unit)
        run()
        assertEquals((0 until 20_000).toList(), slow)
    }

    @Test
    fun `a reactor cancelled while it is behind leaves the others hearing, and nothing is kept for it`() {
        val seen = mutableListOf<AccountEvent>()
        val stuck = scope.follow(stream) { CompletableDeferred<Unit>().await() }
        scope.follow(stream) { seen += it }
        repeat(3) { signOut(); signIn() }
        run()
        assertEquals(6, seen.size)
        assertEquals(2, stream.subscriptionCount.value)

        stuck.cancel()
        run()

        assertEquals(1, stream.subscriptionCount.value)
        signOut()
        run()
        assertEquals(7, seen.size)
    }

    @Test
    fun `a step that throws loses that event's work and no later one`() {
        val seen = mutableListOf<AccountEvent>()
        var first = true
        scope.follow(stream) {
            if (first) {
                first = false
                error("the first one's step fails")
            }
            seen += it
        }

        signOut()
        signIn()
        run()

        assertEquals(1, seen.size)
        assertTrue(seen.single() is AccountEvent.SignedIn)
    }

    @Test
    fun `a step cancelled by what it waited for leaves the reactor following`() {
        val seen = mutableListOf<AccountEvent>()
        val cancelled = CompletableDeferred<Unit>().apply { cancel() }
        var first = true
        val reactor = scope.follow(stream) {
            if (first) {
                first = false
                cancelled.await()
            }
            seen += it
        }

        signOut()
        signIn()
        run()

        assertTrue(reactor.isActive)
        assertTrue(seen.single() is AccountEvent.SignedIn)
    }

    @Test
    fun `only its own scope's cancellation ends a reactor`() {
        val seen = mutableListOf<AccountEvent>()
        val reactor = scope.follow(stream) {
            seen += it
            delay(1_000)
        }
        signOut()
        run()

        reactor.cancel()
        signIn()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(reactor.isCompleted)
        assertEquals(1, seen.size)
    }

    @Test
    fun `changes start from the state as it is`() {
        val seen = followChanges()

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `a sign-out and a sign-in landing before it runs are both seen`() {
        val seen = followChanges()

        signOut()
        signIn()
        run()

        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun `a sign-in and a sign-out landing before it runs are both seen`() {
        state.set(false)
        val seen = followChanges()

        signIn()
        signOut()
        run()

        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun `a sign-in by hand changes nothing a page sees, nor does another account or a student change`() {
        val seen = followChanges()

        session.emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = true))
        session.emit(AccountEvent.StudentChanged(Account.SCHOOL, null))
        session.emit(AccountEvent.SignedIn(Account.MAIL, byHand = false))
        session.emit(AccountEvent.SignedOut(Account.MAIL, SignOutReason.MAIL, series.current, "someone"))
        session.emit(AccountEvent.SignedOut(Account.LIBRARY, SignOutReason.LIBRARY, series.current, null))
        run()

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `a sign-out told twice is one change`() {
        val seen = followChanges()

        signOut()
        session.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, series.current, null))
        run()

        assertEquals(listOf(true, false), seen)
    }

    /** The state of [flag], which runs [atRead] just after each read: what lands around a collector's first look. */
    private class Landing(private val flag: StateFlow<Boolean>, private val atRead: () -> Unit) : StateFlow<Boolean> by flag {
        override val value: Boolean
            get() = flag.value.also { atRead() }
    }

    @Test
    fun `a change landing right after the first read still arrives`() {
        val flag = MutableStateFlow(true)
        var once = false
        session.signedInFrom(
            Landing(flag) {
                if (!once) {
                    once = true
                    flag.value = false
                    session.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, series.current, null))
                }
            },
        )

        val seen = followChanges()
        run()

        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `a change the first read already shows arrives as no second change`() {
        // Moved and read before it is told, as a sign-out on another thread can be.
        state.set(false)
        val seen = followChanges()

        session.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, series.current, null))
        run()

        assertEquals(listOf(false), seen)
    }
}
