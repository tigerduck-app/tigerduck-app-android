package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory

/**
 * The one IMAP connection the mail screens share (spec §8.2). Closed
 * [idleMillis] after the page is left. A held connection the server has
 * since dropped fails with a network error; that is retried once on a
 * fresh connection. Callers run [use] on an IO dispatcher.
 */
class SessionHolder(
    private val factory: MailSessionFactory,
    private val scope: CoroutineScope,
    private val idleMillis: Long = 30_000,
) {
    private val mutex = Mutex()
    private var session: MailSession? = null
    private var owner: String? = null
    private var closeJob: Job? = null

    suspend fun <T> use(credentials: MailCredentials, block: (MailSession) -> T): T = mutex.withLock {
        closeJob?.cancel()
        if (owner != credentials.loginName) closeLocked()
        val reused = session != null
        try {
            runOn(credentials, block)
        } catch (e: MailError.Network) {
            if (!reused) throw e
            runOn(credentials, block)
        }
    }

    fun releaseLater() {
        closeJob?.cancel()
        closeJob = scope.launch {
            delay(idleMillis)
            mutex.withLock { closeLocked() }
        }
    }

    suspend fun closeNow() = mutex.withLock {
        closeJob?.cancel()
        closeLocked()
    }

    private fun <T> runOn(credentials: MailCredentials, block: (MailSession) -> T): T {
        val s = session ?: factory.open(credentials).also {
            session = it
            owner = credentials.loginName
        }
        return try {
            block(s)
        } catch (e: Exception) {
            closeLocked()
            throw MailErrors.classify(e)
        }
    }

    private fun closeLocked() {
        session?.let { runCatching { it.close() } }
        session = null
        owner = null
    }
}
