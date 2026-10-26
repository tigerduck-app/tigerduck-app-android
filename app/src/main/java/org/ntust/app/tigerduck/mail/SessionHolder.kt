package org.ntust.app.tigerduck.mail

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The one IMAP connection the mail screens share (spec §8.2). Closed
 * [idleMillis] after the page is left. A command already in flight is never
 * retried: a held connection is instead probed with a cheap [MailSession.noop]
 * call before every reuse, and replaced with a fresh one if that probe fails
 * -- only then does the caller's [block] run, exactly once. Callers run [use]
 * on an IO dispatcher.
 */
class SessionHolder(
    private val factory: MailSessionFactory,
    private val scope: CoroutineScope,
    private val idleMillis: Long = 30_000,
) {
    private val mutex = Mutex()
    private var session: MailSession? = null
    private var owner: String? = null

    /** Only ever touched via its atomic ops, never plain-read-then-written, so [releaseLater] (non-suspend) and [use]/[closeNow] (mutex-guarded) never race on it. */
    private val closeJob = AtomicReference<Job?>(null)

    /** True once [releaseLater] has run and no [hold]/[closeNow] has happened since: every later [use] (e.g. a background retry after the page closed) keeps re-arming the idle close instead of leaving the connection open forever. */
    private val released = AtomicBoolean(false)

    /** How many [ahead] blocks run now. */
    private val aheadBlocks = MutableStateFlow(0)

    /**
     * Runs right before a [use] waits for the connection. A test's way of knowing a caller has
     * reached that wait while another call holds the connection; null in the app.
     */
    @VisibleForTesting
    internal var onWait: (() -> Unit)? = null

    /**
     * Whether a [use] holds the connection now. A test's way of telling, from [onWait], a caller
     * that waits on another from one that goes straight on.
     */
    @VisibleForTesting
    internal val held: Boolean get() = mutex.isLocked

    /**
     * @param stillWanted see [SchoolMailRepository.loadPage]: whether the caller still wants the
     *   command sent. Asked under the connection's lock twice -- before the connection is so
     *   much as probed, and again right before [block] -- because a probe or an open logs in with
     *   [credentials], and a caller whose account has left since it read them must not log in as
     *   the next one: a refusal then would be the next account's, dropped as the old one's.
     * @param onRefused runs when the server refuses the login with [credentials], before the
     *   connection's lock is let go: a caller queued behind this one then finds the refusal
     *   recorded, rather than sending the same password again (spec §7.4).
     */
    suspend fun <T> use(
        credentials: MailCredentials,
        stillWanted: () -> Boolean = { true },
        onRefused: () -> Unit = {},
        block: (MailSession) -> T,
    ): T {
        lock()
        return try {
            if (!stillWanted()) throw MailError.Abandoned()
            cancelCloseJob()
        if (owner != credentials.loginName) closeLocked()
        session?.let { if (!isAlive(it)) closeLocked() }
        val s = session ?: try {
            factory.open(credentials)
        } catch (e: MailError.AuthFailed) {
            onRefused()
            throw e
        }.also {
            session = it
            owner = credentials.loginName
        }
        try {
            // Opening may have taken a while; the command itself is what the caller may no longer want.
            if (!stillWanted()) throw MailError.Abandoned()
            block(s)
        } catch (e: Exception) {
            val classified = MailErrors.classify(e)
            if (shouldCloseOn(classified)) closeLocked()
            throw classified
        } finally {
            // Whether block succeeded or threw something that didn't close
            // the connection, a still-released holder must keep the idle
            // close armed -- otherwise cancelling it above (to run this
            // call at all) would leave it open forever once nothing calls
            // releaseLater again.
            if (released.get() && session != null) armCloseJob()
        }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs [block] with its calls ahead of everyone else's: from the moment it starts until it
     * ends, a [use] from outside it waits to so much as get in line for the connection, so the
     * mail the user just opened goes out next rather than after the background download's next
     * batch -- and that batch does not get in between two of [block]'s calls either. A call
     * already holding the connection is let finish.
     */
    suspend fun <T> ahead(block: suspend () -> T): T {
        aheadBlocks.update { it + 1 }
        try {
            return withContext(Ahead(this)) { block() }
        } finally {
            aheadBlocks.update { it - 1 }
        }
    }

    /**
     * Takes the connection's lock: in turn for a call in an [ahead] block, and for any other once
     * no [ahead] block runs. One that started while this one was already in line goes first: this
     * one lets go of the lock and waits for it to end.
     */
    private suspend fun lock() {
        val ahead = currentCoroutineContext()[Ahead]?.holder === this
        while (true) {
            if (!ahead) aheadBlocks.first { it == 0 }
            onWait?.invoke()
            mutex.lock()
            if (ahead || aheadBlocks.value == 0) return
            mutex.unlock()
        }
    }

    fun releaseLater() {
        released.set(true)
        armCloseJob()
    }

    /** The page became active again: cancels any pending idle close and stops re-arming it until the next [releaseLater]. */
    fun hold() {
        released.set(false)
        cancelCloseJob()
    }

    suspend fun closeNow() {
        released.set(false)
        cancelCloseJob()
        mutex.withLock { closeLocked() }
    }

    /** A cheap round trip on a held connection; false means the server has since dropped it and it must be replaced before running [block]. */
    private fun isAlive(session: MailSession): Boolean = try {
        session.noop()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Only a dead socket ([MailError.Network]) or a broken TLS/pin check
     * ([MailError.Certificate]) invalidates the connection. Everything else
     * -- including [MailErrors.classify]'s catch-all [MailError.Protocol],
     * which can't be told apart from a genuinely unrecognized failure --
     * says nothing about the connection itself, so it stays open for the
     * next call; the [isAlive] probe on that next [use] is what actually
     * catches staleness.
     */
    private fun shouldCloseOn(e: MailError): Boolean = when (e) {
        is MailError.Network, is MailError.Certificate -> true
        else -> false
    }

    private fun armCloseJob() {
        closeJob.getAndSet(
            scope.launch {
                delay(idleMillis)
                mutex.withLock { closeLocked() }
            },
        )?.cancel()
    }

    private fun cancelCloseJob() {
        closeJob.getAndSet(null)?.cancel()
    }

    private fun closeLocked() {
        session?.let { runCatching { it.close() } }
        session = null
        owner = null
    }

    /** Marks a coroutine as running in one of [holder]'s [ahead] blocks. */
    private class Ahead(val holder: SessionHolder) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Ahead>
    }
}
