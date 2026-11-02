package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.follow
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInBound
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this process has pulled the backend's snapshot for a sign-in yet. A fresh process sends
 * nothing derived from school data until it has (`sync-order`; iOS `FirstPullGate`): a roster
 * fetched before the pull would go up over what another device holds. A local edit never waits
 * for it.
 *
 * Per process and per sign-in. [notePull] opens it for the sign-in its pull ran under, and a
 * [SignInBound] lets go of that once the sign-in ends, so a sign-out closes it with nothing to
 * clear. [awaitPull] waits for it; the school's sign-out releases a waiter with false, as what
 * waited belongs to the account that left.
 */
@Singleton
class FirstPullGate internal constructor(events: Flow<AccountEvent>, scope: CoroutineScope) {

    @Inject constructor(sessions: AccountSessions, @ApplicationScope scope: CoroutineScope) : this(sessions.events, scope)

    private class Waiter(val signIn: SignIn) {
        val released = CompletableDeferred<Boolean>()
    }

    // The sign-in whose pull this process made, the one value it holds; null once it has ended.
    private val pulled = SignInBound<SignIn?>(null)

    private val lock = Any()

    // Under [lock]: registered with the check that found the gate shut, so no release falls between.
    private val waiters = mutableListOf<Waiter>()

    init {
        // Subscribed as the gate is built, so every sign-out from then on reaches its waiters. Any of
        // the school's events lets go of a waiter whose sign-in has ended, whatever ended it.
        scope.follow(events.filter { it.account == Account.SCHOOL }) { event ->
            release(ended = (event as? AccountEvent.SignedOut)?.ended)
        }
    }

    /** Whether a pull under [signIn] has landed in this process, [signIn] still standing. */
    fun hasPulled(signIn: SignIn): Boolean = pulled.value === signIn

    /** A pull under [signIn] has landed: the gate opens for it, and its waiters go on. */
    fun notePull(signIn: SignIn) {
        val opened = synchronized(lock) {
            pulled.set(signIn, signIn)
            if (!hasPulled(signIn)) return
            waiters.filter { it.signIn === signIn }.also { waiters.removeAll(it) }
        }
        opened.forEach { it.released.complete(true) }
    }

    /**
     * True once a pull under [signIn] has landed, at once if one has. False once [signIn] has ended,
     * at once if it has: whatever waited for the pull is the departing account's.
     */
    suspend fun awaitPull(signIn: SignIn): Boolean {
        val waiter = synchronized(lock) {
            if (hasPulled(signIn)) return true
            if (!signIn.isCurrent) return false
            Waiter(signIn).also { waiters += it }
        }
        try {
            return waiter.released.await()
        } finally {
            // A cancelled waiter, as one released, leaves nothing behind.
            synchronized(lock) { waiters.remove(waiter) }
        }
    }

    /** How many callers wait, for a test that a cancelled one leaves none. */
    internal val waiting: Int get() = synchronized(lock) { waiters.size }

    private fun release(ended: SignIn?) {
        val dropped = synchronized(lock) {
            waiters.filter { !it.signIn.isCurrent || it.signIn === ended }.also { waiters.removeAll(it) }
        }
        dropped.forEach { it.released.complete(false) }
    }
}
