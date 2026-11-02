package org.ntust.app.tigerduck.mail

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.auth.SignedInState
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.di.MailAccountSession
import org.ntust.app.tigerduck.mail.imap.MailFolders
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.index.MailSearchIndex
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The school mail account: its own sign-in, separate from the NTUST and
 * library ones (spec §7). Credentials are saved only after the server
 * accepted them.
 */
@OptIn(StartsAccountWork::class)
@Singleton
class MailAccount @Inject constructor(
    private val credentials: MailCredentialStore,
    private val state: MailStateStore,
    private val sessions: MailSessionFactory,
    private val cache: MailCache,
    private val demo: MailDemoGate,
    private val site: MailSite,
    private val scheduler: MailBackgroundScheduler,
    private val notifier: MailNotifier,
    @param:ApplicationScope private val scope: CoroutineScope,
    /**
     * The mail held for Undo or being sent, emptied with the cache whenever the account leaves:
     * a held mail is the student's who wrote it, and no one else's (tigerduck-app#555). Absent in
     * tests that are not about it.
     */
    private val heldSends: HeldSendStore? = null,
    /**
     * The text of every mail downloaded for search, emptied with the cache whenever the account
     * leaves. The repository empties it too, but only once something has made it: a sign-out
     * from Settings, or a full reset, in a process that never opened Mail has no repository to
     * hear it. Absent in tests that are not about it.
     */
    private val searchIndex: MailSearchIndex? = null,
    /**
     * The mail session, whose sign-in moves with the account here. The default is a session of
     * its own, for tests that are not about it.
     */
    @param:MailAccountSession private val session: AccountSession =
        AccountSession(SignInSeries(), scope, account = Account.MAIL),
) {
    private val signedInState = SignedInState(credentialsOrNull() != null)
    val signedIn: StateFlow<Boolean> = signedInState.signedIn.also(session::signedInFrom)

    /**
     * This account's [AccountEvent]s, for cleanup that must run on every sign-out, and every time
     * [signIn] puts another student in place of the signed-in one without a [signOut] in between,
     * as the sign-in sheet shown after a rejected password allows. A [AccountEvent.SignedOut] is
     * told of a sign-out with nobody signed in too, with no address.
     */
    val events: Flow<AccountEvent> = session.events.filter { it.account == Account.MAIL }

    private val _authFailed = MutableStateFlow(state.authFailed)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    val studentId: String? get() = credentials.mailStudentId

    // Held while a sign-in or sign-out changes the account and its sign-in, and while [snapshot]
    // reads them, so a reader never pairs one account with the other's sign-in.
    private val accountLock = Any()

    /**
     * The next sign-in, under [accountLock]. Ended and opened in one hold: mail has no wipe to
     * wait for, its cleanup has its own guards, and so its series never stays closed. [ownDemo]
     * says whether it is the demo mailbox's, which the series answers from here on.
     */
    private fun moveSignIn(ownDemo: Boolean = false): SignIn {
        val ended = session.signIns.end()
        if (ownDemo) session.signIns.startDemo() else session.signIns.leaveDemo()
        session.open()
        present += session.current
        return ended
    }

    /**
     * Runs right before a sign-in or a sign-out waits for the account's lock: a test's way of
     * knowing one has got there while something else holds it. Null in the app.
     */
    @VisibleForTesting
    @Volatile
    internal var onLockWait: (() -> Unit)? = null

    /** Runs first in a sign-out's cleanup: a test's way of holding it back past what comes next. Null in the app. */
    @VisibleForTesting
    @Volatile
    internal var onCleanup: (() -> Unit)? = null

    /** Whether the calling thread holds the account's lock: a test's way of telling a step made under it. */
    @VisibleForTesting
    internal fun holdsLock(): Boolean = Thread.holdsLock(accountLock)

    /**
     * The sign-in work starting now belongs to, for it to ask [SignIn.isCurrent] once it has run
     * on: it ends with every sign-in and sign-out, even the same student's. While signed out, the
     * one the next sign-in ends. Unlike [snapshot] no read of the encrypted store, so for any thread.
     */
    @StartsAccountWork
    val currentSignIn: SignIn get() = session.current

    // Under [accountLock]: the sign-ins of the student here now, the current one and those a
    // password typed again ended.
    private var present: Set<SignIn> = setOf(session.current)

    // The sign-ins of students who have left, while anything still holds one to ask about. Added
    // to only as one leaves, so a sign-in just opened is never in it, whoever reads it without the lock.
    private val gone: MutableSet<SignIn> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** The student here now leaves, under [accountLock]. */
    private fun studentLeaves() {
        gone += present
        present = emptySet()
    }

    /**
     * Whether [signIn]'s student has left, by a sign-out or another student signing in: not what
     * [SignIn.isCurrent] says. A password typed again ends a sign-in with the student still here,
     * whose held mail and notices stay theirs; and a student change is told before it ends the
     * sign-in, so whoever hears of it finds the student gone already. Asked of what outlives a request.
     */
    fun hasLeft(signIn: SignIn): Boolean = signIn in gone

    /**
     * The signed-in account and its sign-in, read together; null while signed out. [clears] is the
     * state's [MailStateStore.clears] read with them: a sign-in resets the state in the same hold
     * that puts its credentials in place ([save]), so the count is always that of [credentials]'
     * student, never the next one's.
     */
    class Snapshot(val signIn: SignIn, val credentials: MailCredentials, val clears: Int)

    fun snapshot(): Snapshot? = synchronized(accountLock) {
        credentialsOrNull()?.let { Snapshot(session.current, it, state.clears) }
    }

    /**
     * The signed-in student's own address, without decrypting the password the way
     * [credentialsOrNull] does. Still a read of the encrypted store, so not for the main thread.
     */
    val ownAddress: String? get() = credentials.mailStudentId?.takeIf { it.isNotBlank() }?.let { MailCredentials.addressOf(it, site.domain()) }

    /** True for the mail demo mailbox itself, or whenever the app-wide demo is active -- either way, nothing may open a socket. */
    val isDemo: Boolean get() = session.isDemo

    // The whole-app demo account is signed in: no real mail sign-in may reach the server.
    private val appDemo: Boolean get() = session.demoFromElsewhere

    fun credentialsOrNull(): MailCredentials? {
        val id = credentials.mailStudentId
        val password = credentials.mailPassword
        return if (id.isNullOrBlank() || password.isNullOrEmpty()) null else MailCredentials(id, password, site.domain())
    }

    /** Returns null on success, or the error to show. */
    suspend fun signIn(studentId: String, password: String): MailError? = withContext(Dispatchers.IO) {
        val creds = MailCredentials(studentId.trim(), password, site.domain())
        val sameStudent = credentials.mailStudentId?.equals(creds.studentId, ignoreCase = true) == true
        val keptName = if (sameStudent) state.displayName else null

        val replacing = !sameStudent && !credentials.mailStudentId.isNullOrBlank()

        if (demo.matches(creds.studentId, password)) {
            if (!sameStudent) leaveMailOf(ownAddress)
            if (replacing) leaveStudent()
            save(creds, sameStudent, ownDemo = true) {
                state.demoMailbox = true
                state.displayName = keptName ?: demo.mailbox().displayName
            }
            notifier.cancelAuthFailure()
            return@withContext null
        }
        if (appDemo) return@withContext MailError.AuthFailed()

        val fill = try {
            sessions.open(creds).use { session ->
                val folders = MailFolders.resolve(session.listFolders())
                val status = session.status(folders.nameOf(SpecialFolder.INBOX) ?: "INBOX")
                val name = folders.nameOf(SpecialFolder.SENT)?.let { sent ->
                    runCatching { session.newestSenderName(sent, creds.address, window = 20) }.getOrNull()
                }
                if (!sameStudent) leaveMailOf(ownAddress)
                if (replacing) leaveStudent()
                val fresh: () -> Unit = {
                    state.inboxUidValidity = status.uidValidity
                    state.inboxSeenUidNext = status.uidNext
                    state.displayName = keptName ?: name
                }
                fresh
            }
        } catch (e: Exception) {
            return@withContext MailErrors.classify(e)
        }
        save(creds, sameStudent, fill = fill)
        notifier.cancelAuthFailure()
        scheduler.schedule()
        null
    }

    /**
     * Another student signs in over this one, which is no sign-out: the screens drop this one's
     * mail on the [events] told here, and this one's notifications go, whose taps would open the
     * next student's mail with this one's UIDs.
     */
    private fun leaveStudent() {
        synchronized(accountLock) { studentLeaves() }
        session.emit(AccountEvent.StudentChanged(Account.MAIL, ownAddress))
        notifier.cancelAll()
    }

    /**
     * The mail on the phone of [departing], an account that leaves: the cache, and the mail it
     * held to send under a sign-in [ended] says is over. Only its own held mail: a sign-out's
     * cleanup runs some time after it, and the next student may have mail of their own held by
     * then. A read of the encrypted store when the caller reads [departing]: for the IO dispatcher.
     */
    private fun leaveMailOf(departing: String?, ended: (SignIn) -> Boolean = { true }) {
        cache.clearAll()
        heldSends?.clear(departing, ended)
        // Kept only when it is no longer the departing student's but the one signed in now, as
        // the repository's own clearing keeps it: by now the next student may have downloaded
        // mail of theirs. An index that can't be emptied now (a full disk fails the VACUUM) must
        // not stop the rest of the leaving, or a sign-in: no read shows its rows to anyone but
        // their owner, and the next student's first download empties it.
        val left = departing?.lowercase()
        runCatching { searchIndex?.clearUnless { owner -> owner != null && owner != left && owner == ownAddress?.lowercase() } }
    }

    /** A password typed again keeps what the student chose; see [MailStateStore.clearSignIn]. */
    private fun resetState(sameStudent: Boolean) = if (sameStudent) state.clearSignIn() else state.clear()

    /**
     * Both callers are on the main thread, so only the cheap part runs there:
     * the flags and the credential removal stay synchronous because the
     * repository's reaction to [events] and every later `credentialsOrNull()`
     * depend on them having already happened. Deleting the cache tree and
     * cancelling the notifications are IO and finish on their own.
     */
    fun signOut() {
        scheduler.cancel()
        // Together, so a snapshot holds the departing account with its own sign-in or nothing.
        onLockWait?.invoke()
        val (departing, ended) = synchronized(accountLock) {
            val address = ownAddress
            studentLeaves()
            val ended = moveSignIn()
            credentials.clearMailCredentials()
            address to ended
        }
        session.cancelWork()
        state.clear()
        signedInState.set(false) {
            _authFailed.value = false
            session.emit(AccountEvent.SignedOut(Account.MAIL, SignOutReason.MAIL, ended, departing))
        }
        scope.launch(Dispatchers.IO) {
            // Only the mail of the sign-ins that left: by now the same student may have signed in
            // again and kept a mail of the new sign-in's.
            try {
                onCleanup?.invoke()
                leaveMailOf(departing, ended = ::hasLeft)
            } finally {
                notifier.cancelAll()
            }
        }
    }

    /**
     * The server rejected the stored password: stop everything, never retry (spec §7.4). A request
     * that met the rejection marks it with [onAuthFailure] for the sign-in it was sent under.
     */
    fun onAuthFailure() {
        state.authFailed = true
        _authFailed.value = true
        scheduler.cancel()
    }

    /**
     * [onAuthFailure] for a login sent under [signIn], unless it has ended -- even by the same
     * student's sign-in with the same password, which the server has just accepted. Read, marked
     * and the checks cancelled under the lock a sign-in and a sign-out move the sign-in under, so
     * neither can come in between: not even to schedule the next sign-in's checks before this
     * cancels them. Says whether it marked the account.
     */
    fun onAuthFailure(signIn: SignIn): Boolean = synchronized(accountLock) {
        if (!signIn.isCurrent) return@synchronized false
        onAuthFailure()
        true
    }

    /**
     * Puts [creds] in place with the state reset for them ([resetState]) and [fill]ed in, in one hold
     * of [accountLock] with the sign-in's move. Resetting first and switching the credentials
     * later left a moment when the departing student's credentials stood beside the next one's
     * fresh state, and a write that started then recorded its flagged mail as the next one's
     * ([MailStateStore.setOwnedDeletedIf]); a [snapshot] now holds one student's of both.
     */
    private fun save(creds: MailCredentials, sameStudent: Boolean, ownDemo: Boolean = false, fill: () -> Unit) {
        onLockWait?.invoke()
        synchronized(accountLock) {
            resetState(sameStudent)
            fill()
            moveSignIn(ownDemo)
            credentials.mailStudentId = creds.studentId
            credentials.mailPassword = creds.password
        }
        session.cancelWork()
        state.authFailed = false
        _authFailed.value = false
        signedInState.set(true) { changed ->
            session.emit(AccountEvent.SignedIn(Account.MAIL, byHand = true))
            if (changed) session.emit(AccountEvent.SignedIn(Account.MAIL, byHand = false))
        }
    }
}
