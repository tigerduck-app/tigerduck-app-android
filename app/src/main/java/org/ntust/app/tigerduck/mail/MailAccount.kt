package org.ntust.app.tigerduck.mail

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.ApplicationScope
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
import java.util.concurrent.atomic.AtomicInteger
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
) {
    private val _signedIn = MutableStateFlow(credentialsOrNull() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /**
     * One event per [signOut], for cleanup that must run on every one of
     * them. [signedIn] can't carry that: a StateFlow hands a collector only
     * the latest value, so a sign-in and a sign-out that both land before it
     * next runs read as no change at all. Each carries the address of the
     * account that left, null for a sign-out with none signed in: cleanup that
     * runs late must not take what the next account has made since, nor keep
     * what an account that left made. Buffered without limit, so [signOut]
     * never suspends and a subscriber that falls behind still gets every one.
     */
    private val _signOuts = MutableSharedFlow<String?>(extraBufferCapacity = Int.MAX_VALUE)
    val signOuts: SharedFlow<String?> = _signOuts.asSharedFlow()

    /**
     * One event each time [signIn] puts another student in place of the signed-in one without a
     * [signOut] in between, as the sign-in sheet shown after a rejected password allows. Cleanup
     * that must not carry one student's state over to the next listens to this beside [signOuts].
     * Each carries the address of the student who left, and is buffered, as [signOuts] is.
     */
    private val _studentChanges = MutableSharedFlow<String?>(extraBufferCapacity = Int.MAX_VALUE)
    val studentChanges: SharedFlow<String?> = _studentChanges.asSharedFlow()

    private val _authFailed = MutableStateFlow(state.authFailed)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    val studentId: String? get() = credentials.mailStudentId

    private val generation = AtomicInteger()

    // Held while a sign-in or sign-out changes the account and its generation, and while
    // [snapshot] reads them, so a reader never pairs one account with the other's generation.
    private val accountLock = Any()

    /**
     * Runs right before a sign-in or a sign-out waits for the account's lock: a test's way of
     * knowing one has got there while something else holds it. Null in the app.
     */
    @VisibleForTesting
    @Volatile
    internal var onLockWait: (() -> Unit)? = null

    /** Whether the calling thread holds the account's lock: a test's way of telling a step made under it. */
    @VisibleForTesting
    internal fun holdsLock(): Boolean = Thread.holdsLock(accountLock)

    /**
     * Moved by every sign-in and sign-out. Work that reads the account through [snapshot] and then
     * runs on compares this with the snapshot's: once it has moved, the work holds an account
     * that left, even when the same student has signed in again since.
     */
    val accountGeneration: Int get() = generation.get()

    // By address, the generation each student last left at, noted as [signOuts] or
    // [studentChanges] tells of it: a collector runs some time after, and by then the same
    // student may have signed in again.
    private val departures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * The generation [address] last left at: its sign-ins up to it are gone. Null for a student
     * who has not left in this process.
     */
    fun departedAt(address: String): Int? = departures[address.lowercase()]

    /**
     * The signed-in account and its [accountGeneration], read together; null while signed out.
     * [clears] is the state's [MailStateStore.clears] read with them: a sign-in resets the state in
     * the same hold that puts its credentials in place ([save]), so the count is always that of
     * [credentials]' student, never the next one's.
     */
    class Snapshot(val generation: Int, val credentials: MailCredentials, val clears: Int)

    fun snapshot(): Snapshot? = synchronized(accountLock) {
        credentialsOrNull()?.let { Snapshot(generation.get(), it, state.clears) }
    }

    /**
     * The signed-in student's own address, without decrypting the password the way
     * [credentialsOrNull] does. Still a read of the encrypted store, so not for the main thread.
     */
    val ownAddress: String? get() = credentials.mailStudentId?.takeIf { it.isNotBlank() }?.let { MailCredentials.addressOf(it, site.domain()) }

    /** True for the mail demo mailbox itself, or whenever the app-wide demo is active -- either way, nothing may open a socket. */
    val isDemo: Boolean get() = state.demoMailbox || demo.appDemoActive

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
            save(creds, sameStudent) {
                state.demoMailbox = true
                state.displayName = keptName ?: demo.mailbox().displayName
            }
            notifier.cancelAuthFailure()
            return@withContext null
        }
        if (demo.appDemoActive) return@withContext MailError.AuthFailed()

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
        save(creds, sameStudent, fill)
        notifier.cancelAuthFailure()
        scheduler.schedule()
        null
    }

    /**
     * Another student signs in over this one, which is no sign-out: the screens drop this one's
     * mail on [studentChanges], and this one's notifications go, whose taps would open the next
     * student's mail with this one's UIDs.
     */
    private fun leaveStudent() {
        ownAddress?.let { departures[it.lowercase()] = generation.get() }
        _studentChanges.tryEmit(ownAddress)
        notifier.cancelAll()
    }

    /**
     * The mail on the phone of [departing], an account that leaves: the cache, and the mail it
     * held to send under a sign-in before [keptBefore]. Only its own held mail: a sign-out's
     * cleanup runs some time after it, and the next student may have mail of their own held by
     * then. A read of the encrypted store when the caller reads [departing]: for the IO dispatcher.
     */
    private fun leaveMailOf(departing: String?, keptBefore: Int = Int.MAX_VALUE) {
        cache.clearAll()
        heldSends?.clear(departing, below = keptBefore)
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
     * repository's [signOuts] collector and every later `credentialsOrNull()`
     * depend on them having already happened. Deleting the cache tree and
     * cancelling the notifications are IO and finish on their own.
     */
    fun signOut() {
        scheduler.cancel()
        // Together, so a snapshot holds the departing account with its own generation or nothing.
        onLockWait?.invoke()
        var leftAt = 0
        val departing = synchronized(accountLock) {
            ownAddress.also {
                leftAt = generation.incrementAndGet()
                credentials.clearMailCredentials()
            }
        }
        state.clear()
        _signedIn.value = false
        _authFailed.value = false
        departing?.let { departures[it.lowercase()] = leftAt }
        _signOuts.tryEmit(departing)
        scope.launch(Dispatchers.IO) {
            // Only the mail of the sign-in that left: by now the same student may have signed in
            // again and kept a mail of the new sign-in's.
            try {
                leaveMailOf(departing, keptBefore = leftAt)
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
     * [onAuthFailure] for a login sent under the sign-in at [generation], unless another sign-in or
     * a sign-out has come since -- even the same student's with the same password, which the
     * server has just accepted. Read, marked and the checks cancelled under the lock a sign-in and
     * a sign-out move the generation under, so neither can come in between: not even to schedule
     * the next sign-in's checks before this cancels them. Says whether it marked the account.
     */
    fun onAuthFailure(generation: Int): Boolean = synchronized(accountLock) {
        if (this.generation.get() != generation) return@synchronized false
        state.authFailed = true
        _authFailed.value = true
        scheduler.cancel()
        true
    }

    /**
     * Puts [creds] in place with the state reset for them ([resetState]) and [fill]ed in, in one hold
     * of [accountLock] with the generation's move. Resetting first and switching the credentials
     * later left a moment when the departing student's credentials stood beside the next one's
     * fresh state, and a write that started then recorded its flagged mail as the next one's
     * ([MailStateStore.setOwnedDeletedIf]); a [snapshot] now holds one student's of both.
     */
    private fun save(creds: MailCredentials, sameStudent: Boolean, fill: () -> Unit) {
        onLockWait?.invoke()
        synchronized(accountLock) {
            resetState(sameStudent)
            fill()
            generation.incrementAndGet()
            credentials.mailStudentId = creds.studentId
            credentials.mailPassword = creds.password
        }
        state.authFailed = false
        _authFailed.value = false
        _signedIn.value = true
    }
}
