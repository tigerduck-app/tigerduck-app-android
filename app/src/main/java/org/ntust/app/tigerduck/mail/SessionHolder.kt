package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one IMAP connection the mail screens share (spec §8.2). Closed
 * [idleMillis] after the page is left. A command already in flight is never
 * retried: a held connection is instead probed with a cheap [MailSession.status]
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

    /** True once [releaseLater] has run and no [closeNow] has happened since: a later [use] (e.g. a background retry after the page closed) keeps re-arming the idle close instead of leaving the connection open forever. */
    private val released = AtomicBoolean(false)

    suspend fun <T> use(credentials: MailCredentials, block: (MailSession) -> T): T = mutex.withLock {
        cancelCloseJob()
        if (owner != credentials.loginName) closeLocked()
        session?.let { if (!isAlive(it)) closeLocked() }
        val s = session ?: factory.open(credentials).also {
            session = it
            owner = credentials.loginName
        }
        try {
            val result = block(s)
            if (released.get()) armCloseJob()
            result
        } catch (e: Exception) {
            val classified = MailErrors.classify(e)
            if (shouldCloseOn(classified)) closeLocked()
            throw classified
        }
    }

    fun releaseLater() {
        released.set(true)
        armCloseJob()
    }

    suspend fun closeNow() {
        released.set(false)
        cancelCloseJob()
        mutex.withLock { closeLocked() }
    }

    /** A cheap round trip on a held connection; false means the server has since dropped it and it must be replaced before running [block]. */
    private fun isAlive(session: MailSession): Boolean = try {
        session.status("INBOX")
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A dead socket ([MailError.Network], [MailError.Certificate]) or
     * anything unrecognized invalidates the connection. A tagged NO/BAD for
     * one command -- unsupported search, a protocol-level complaint, or an
     * auth failure on a connection that already logged in -- says nothing
     * about the connection itself, so it stays open for the next call.
     */
    private fun shouldCloseOn(e: MailError): Boolean = when (e) {
        is MailError.SearchUnsupported, is MailError.Protocol, is MailError.AuthFailed -> false
        else -> true
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
}
