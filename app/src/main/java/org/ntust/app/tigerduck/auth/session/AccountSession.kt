package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
) {
    private val running = ConcurrentHashMap.newKeySet<Job>()

    /** The sign-in work starting now belongs to; while the session is closed, the one that ended. */
    @StartsAccountWork
    val current: SignIn get() = signIns.current

    /** Runs [work] under the sign-in as it is now, which the caller hands down. */
    @StartsAccountWork
    suspend fun <T> withSignIn(work: suspend (SignIn) -> T): T = work(signIns.current)

    /**
     * Starts [work] on the session's scope under the sign-in as it is now, and cancels it when
     * that sign-in ends. Work started while the session is closed is cancelled at once.
     */
    @StartsAccountWork
    fun launch(work: suspend CoroutineScope.(SignIn) -> Unit): Job {
        val signIn = signIns.current
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

    /** Opens the next sign-in once the wipe is done. */
    fun open() = signIns.open()

    /** Returns when a sign-in is open: at once, or when the clearing that follows a sign-out is done. */
    suspend fun awaitOpen() = signIns.awaitOpen()
}
