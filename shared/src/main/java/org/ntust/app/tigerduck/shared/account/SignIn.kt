package org.ntust.app.tigerduck.shared.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * One sign-in of one account, from the moment it opens to the sign-out that ends it. A write or
 * a request of account data is made under the [SignIn] its work began with, and the place that
 * lands it asks [isCurrent] (or runs inside [ifCurrent]) instead of comparing numbers by hand.
 *
 * A [SignIn] never becomes current again once it has ended. Equality is identity: two sign-ins
 * of the same student are two sign-ins. It is never persisted.
 */
class SignIn internal constructor(private val series: SignInSeries, val isDemo: Boolean) {
    @Volatile
    internal var ended = false

    /** False once the sign-out has ended this sign-in, and for good. */
    val isCurrent: Boolean get() = !ended

    /**
     * Runs [block] unless this sign-in has ended, atomic with [SignInSeries.end]: a sign-out
     * either waits for the block, or comes first and the block never runs. For a short write.
     * Null when the block did not run.
     */
    fun <T> ifCurrent(block: () -> T): T? = synchronized(series.lock) { if (ended) null else block() }
}

/**
 * The sign-ins of one account, one after the other, for the life of the process.
 *
 * The series is closed from [end] until [open]: the sign-out ends the sign-in, the account's data
 * is wiped, and only then does the next sign-in open. While closed, [current] is the sign-in that
 * ended, so work that starts in the gap can write nothing, even after the wipe.
 *
 * The time signed out shares the next sign-in, as the sign-in counter it replaces did: a write
 * made while signed out is legal, and is the next sign-in's.
 */
class SignInSeries(closed: Boolean = false, demo: Boolean = false) {
    internal val lock = Any()
    private var demoNext = demo
    private var open = !closed

    @Volatile
    private var latest = SignIn(this, demo).also { if (closed) it.ended = true }

    private val _isOpen = MutableStateFlow(!closed)

    /** Whether a sign-in is open, as a flow for [awaitOpen]. */
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    /** How many callers are inside [awaitOpen], for a test that a cancelled wait leaves none. */
    internal val waiters: Int get() = _isOpen.subscriptionCount.value

    /** The current sign-in; while the series is closed, the one that ended. Never null. */
    val current: SignIn get() = latest

    /** Ends the current sign-in and closes the series. A second call changes nothing. */
    fun end(): SignIn = synchronized(lock) {
        if (open) {
            latest.ended = true
            open = false
            _isOpen.value = false
        }
        latest
    }

    /** Starts the next sign-in. A second call changes nothing. */
    fun open() = synchronized(lock) {
        if (!open) {
            latest = SignIn(this, demoNext)
            open = true
            _isOpen.value = true
        }
    }

    /** Ends the current sign-in and opens a demo one. */
    fun startDemo() = synchronized(lock) {
        end()
        demoNext = true
        open()
    }

    /** The next [open] starts a plain sign-in. */
    fun leaveDemo() = synchronized(lock) { demoNext = false }

    /** Returns at once when a sign-in is open, and otherwise when [open] is called. */
    suspend fun awaitOpen() {
        _isOpen.first { it }
    }
}

/**
 * A value that belongs to one sign-in: it reads [empty] once that sign-in has ended, and a
 * [set] from an ended sign-in is dropped.
 */
class SignInBound<T>(private val empty: T) {
    private class Held<T>(val signIn: SignIn, val value: T)

    @Volatile
    private var held: Held<T>? = null

    val value: T
        get() = held?.takeIf { it.signIn.isCurrent }?.value ?: empty

    fun set(value: T, signIn: SignIn) {
        signIn.ifCurrent { held = Held(signIn, value) }
    }
}
