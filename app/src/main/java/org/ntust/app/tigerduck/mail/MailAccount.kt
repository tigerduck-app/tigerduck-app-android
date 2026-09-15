package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.mail.imap.MailFolders
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The school mail account: its own sign-in, separate from the NTUST and
 * library ones (spec §7). Credentials are saved only after the server
 * accepted them.
 */
@Singleton
class MailAccount @Inject constructor(
    private val credentials: MailCredentialStore,
    private val state: MailStateStore,
    private val sessions: MailSessionFactory,
    private val cache: MailCache,
    private val demo: MailDemoGate,
    private val scheduler: MailBackgroundScheduler,
    private val notifier: MailNotifier,
) {
    private val _signedIn = MutableStateFlow(credentialsOrNull() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _authFailed = MutableStateFlow(state.authFailed)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    val studentId: String? get() = credentials.mailStudentId

    /** True for the mail demo mailbox itself, or whenever the app-wide demo is active -- either way, nothing may open a socket. */
    val isDemo: Boolean get() = state.demoMailbox || demo.appDemoActive

    fun credentialsOrNull(): MailCredentials? {
        val id = credentials.mailStudentId
        val password = credentials.mailPassword
        return if (id.isNullOrBlank() || password.isNullOrEmpty()) null else MailCredentials(id, password)
    }

    /** Returns null on success, or the error to show. */
    suspend fun signIn(studentId: String, password: String): MailError? = withContext(Dispatchers.IO) {
        val creds = MailCredentials(studentId.trim(), password)
        val sameStudent = credentials.mailStudentId?.equals(creds.studentId, ignoreCase = true) == true
        val keptName = if (sameStudent) state.displayName else null

        if (demo.matches(creds.studentId, password)) {
            if (!sameStudent) cache.clearAll()
            state.clear()
            state.demoMailbox = true
            state.displayName = keptName ?: demo.mailbox().displayName
            save(creds)
            return@withContext null
        }
        if (demo.appDemoActive) return@withContext MailError.AuthFailed()

        try {
            sessions.open(creds).use { session ->
                val folders = MailFolders.resolve(session.listFolders())
                val status = session.status(folders.nameOf(SpecialFolder.INBOX) ?: "INBOX")
                val name = folders.nameOf(SpecialFolder.SENT)?.let { sent ->
                    runCatching { session.newestSenderName(sent, creds.address, window = 20) }.getOrNull()
                }
                if (!sameStudent) cache.clearAll()
                state.clear()
                state.inboxUidValidity = status.uidValidity
                state.inboxSeenUidNext = status.uidNext
                state.displayName = keptName ?: name
            }
        } catch (e: Exception) {
            return@withContext MailErrors.classify(e)
        }
        save(creds)
        scheduler.schedule()
        null
    }

    fun signOut() {
        scheduler.cancel()
        notifier.cancelAll()
        credentials.clearMailCredentials()
        state.clear()
        cache.clearAll()
        _signedIn.value = false
        _authFailed.value = false
    }

    /** The server rejected the stored password: stop everything, never retry (spec §7.4). */
    fun onAuthFailure() {
        state.authFailed = true
        _authFailed.value = true
        scheduler.cancel()
    }

    private fun save(creds: MailCredentials) {
        credentials.mailStudentId = creds.studentId
        credentials.mailPassword = creds.password
        state.authFailed = false
        _authFailed.value = false
        _signedIn.value = true
    }
}
