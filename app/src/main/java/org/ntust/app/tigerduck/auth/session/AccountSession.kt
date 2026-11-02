package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import java.util.concurrent.ConcurrentHashMap

/**
 * Marks the calls that start account work. The work reads its [SignIn] where it starts and
 * hands it down; only the layers that start work may call these (the `sign-in-read` ratchet in
 * `tools/guardrails` names them), and every other layer is given the sign-in as a parameter.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class StartsAccountWork

/**
 * One account's session for the life of the process: its [SignInSeries], and the work started on
 * it. The sign-out ends the sign-in and then [cancelWork]; the wipe follows, and [open] starts
 * the next sign-in once it is done.
 *
 * [isDemo] answers as the code it replaces did, from [demo], until the demo moves into the
 * session.
 */
class AccountSession(
    val signIns: SignInSeries,
    private val scope: CoroutineScope,
    private val demo: () -> Boolean = { false },
    /** The marker a sign-out sets before it ends the sign-in; absent for a session whose wipe is not owed. */
    private val owed: SignOutOwed? = null,
    private val account: Account = Account.SCHOOL,
    /** The one stream, [AccountSessions.events]; a stream of its own for a session made alone in a test. */
    private val stream: MutableSharedFlow<AccountEvent> = accountEventStream(),
) {
    private val running = ConcurrentHashMap.newKeySet<Job>()

    /** The three accounts' stream: see [AccountEvent]. */
    val events = stream.asSharedFlow()

    /** Tells every reactor, without waiting for any: safe under a lock, and from any thread. */
    fun emit(event: AccountEvent) {
        stream.tryEmit(event)
    }

    /** Where [changes] reads the account's state. */
    @Volatile
    private var signedIn: StateFlow<Boolean>? = null

    /**
     * Names the state [changes] starts from. Its account's owner calls this as it is built; the
     * library's does not yet, as no page follows it and a lapsed token moves its state unsaid.
     */
    fun signedInFrom(state: StateFlow<Boolean>) {
        signedIn = state
    }

    /**
     * Whether the account is signed in: as it is now, then every change, a sign-out and a sign-in
     * landing before the collector runs included, which a page must clear for, though the state
     * reads the same after them. Subscribed before the state is read, with nothing held, so a
     * change landing around the read arrives, and the same value twice in a row is one.
     */
    val changes: Flow<Boolean> = flow {
        val page = this
        events.onSubscription { page.emit(signedIn?.value == true) }.collect { event ->
            if (event.account != account) return@collect
            when (event) {
                is AccountEvent.SignedOut -> emit(false)
                is AccountEvent.SignedIn -> if (!event.byHand) emit(true)
                is AccountEvent.StudentChanged -> Unit
            }
        }
    }.distinctUntilChanged()

    /** The sign-in work starting now belongs to; while the session is closed, the one that ended. */
    @StartsAccountWork
    val current: SignIn get() = signIns.current

    /** Runs [work] under the sign-in as it is now, which the caller hands down. */
    @StartsAccountWork
    suspend fun <T> withSignIn(work: suspend (SignIn) -> T): T = work(signIns.current)

    /**
     * Starts [work] on the session's scope under [signIn], by default the sign-in as it is now,
     * and cancels it when that sign-in ends. Work under one that has ended, as all work started
     * while the session is closed is, is cancelled at once, and never runs at all: what must
     * follow it whatever happens waits on the job, as a `finally` in [work] may not run.
     */
    @StartsAccountWork
    fun launch(signIn: SignIn = signIns.current, work: suspend CoroutineScope.(SignIn) -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { work(signIn) }
        // Registered before the check: a sign-out that ends the sign-in after it finds the job,
        // and one that ended it before is seen here.
        running += job
        job.invokeOnCompletion { running -= job }
        if (signIn.isCurrent) job.start() else job.cancel()
        return job
    }

    val isDemo: Boolean get() = demo()

    /** Cancels what [launch] started. Called after the sign-in ended, and after the sign-in lock is released. */
    fun cancelWork() {
        running.forEach { it.cancel() }
    }

    /** Whether a sign-in is open now. */
    val isOpen: Boolean get() = signIns.isOpen.value

    /** Whether a sign-out is still owed its finish: a process death cut it short. */
    val wipeOwed: Boolean get() = owed?.owed == true

    /** Whether a start finishes the sign-out on the marker, given the student stored now. */
    fun replays(storedStudentId: String?): Boolean = owed?.replays(storedStudentId) == true

    /** Counts a clearing that failed, and says whether the marker stays for the next start. */
    fun triesAgain(): Boolean = owed?.triesAgain() == true

    /** Written to disk before anything is cleared, so a process death is not forgotten. */
    fun markWipeOwed(departingStudentId: String?) {
        owed?.mark(departingStudentId)
    }

    /**
     * Opens the next sign-in once the last wipe is done, and takes the marker off. A marker the
     * disk would not release is harmless: it names who left, and a replay wipes nobody else.
     */
    fun open() {
        owed?.clear()
        signIns.open()
    }

    /** Returns when a sign-in is open: at once, or when the clearing that follows a sign-out is done. */
    suspend fun awaitOpen() = signIns.awaitOpen()
}
