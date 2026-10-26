package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tries the stored password again once a refusal's wait is over ([StoredPasswordLatch.retryAt]),
 * so the quick retries go while the app runs and a relaunch takes the wait up where it stood
 * (tigerduck-app/tigerduck-app#768). Each try is [retry], [AuthService.retryStoredPassword] in
 * the app. A refusal it gets is counted and starts the next wait; a try that stops short of the
 * school's answer, lost to the network say, goes again a step further down the schedule. A post
 * the school takes, a sign-in by hand and a sign-out end the count, and with it the wait.
 */
@Singleton
class StoredPasswordRetry internal constructor(
    private val latch: StoredPasswordLatch,
    private val now: () -> Long,
    /** One try; true once it ran to the end. */
    private val retry: suspend () -> Boolean,
) {
    @Inject
    constructor(latch: StoredPasswordLatch, authService: AuthService) :
        this(latch, System::currentTimeMillis, authService::retryStoredPassword)

    /** Keeps the tries going in [scope] while it runs. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        retryWhenDue(latch.changes, { latch.refusals.value }, latch::retryAt, now, retry)
    }
}

/**
 * The tries behind [StoredPasswordRetry] and the library's: on each of [changes], which move with
 * every refusal counted and every end of the count, waits until [retryAt] and runs [retry] once.
 * A try that stops short of an answer goes again a step further down the schedule than the
 * [refusals] counted. A wait is taken up again from [retryAt] itself, so one a relaunch finds run
 * out goes at once.
 */
internal suspend fun retryWhenDue(
    changes: Flow<*>,
    refusals: () -> Int,
    retryAt: () -> Long?,
    now: () -> Long,
    /** One try; true once it got an answer, or there is nothing left to try. */
    retry: suspend () -> Boolean,
) {
    changes.collectLatest {
        if (refusals() == 0) return@collectLatest
        var misses = 0
        var wait = (retryAt() ?: return@collectLatest) - now()
        while (true) {
            delay(wait.coerceAtLeast(0))
            val at = retryAt() ?: return@collectLatest
            if (at > now()) {
                wait = at - now()
                continue
            }
            // Not cut short when a refusal it gets moves the count, which starts the next wait.
            if (withContext(NonCancellable) { retry() }) return@collectLatest
            misses++
            wait = StoredPasswordSchedule.delayMs(refusals() + misses)
        }
    }
}

/**
 * One try of the stored password, down the chain a typed sign-in runs: [signInToSchool], which
 * posts the password to the school, warm session or not, then [renewMoodleToken] where none is
 * stored, then [signInToBackend], told whether this try got the Moodle token. Signs nothing out.
 * It stops at the first step that fails, a refusal the latch counts included, and says whether it
 * ran to the end.
 *
 * [settle] ends the refusals once the school and the Moodle token step are both through, before
 * the backend's step, not on the school's yes alone: a renewal refused every time would otherwise
 * start the count again at its first step after each yes, and the retry would post every 10
 * seconds for good. As on the iPhone. See [RetrySettlement] for what it ends.
 */
internal suspend fun retryStoredPasswordChain(
    signInToSchool: suspend () -> Boolean,
    moodleTokenMissing: () -> Boolean,
    renewMoodleToken: suspend () -> Unit,
    settle: () -> Unit,
    signInToBackend: suspend (renewedToken: Boolean) -> Boolean,
): Boolean {
    if (!signInToSchool()) return false
    val renewed = moodleTokenMissing()
    if (renewed) {
        try {
            renewMoodleToken()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }
    settle()
    return signInToBackend(renewed)
}

/**
 * What one try of the stored password settles once its chain is through ([retryStoredPasswordChain]):
 * the refusals counted up to the school taking the password this try posted, and nothing if none
 * was posted, or a refusal was counted, or the count ended, since. The account, and the latch's
 * generation, are the ones the try began with: a sign-in by hand or a sign-out meanwhile leaves
 * nothing to settle.
 */
internal class RetrySettlement(
    private val latch: StoredPasswordLatch,
    private val studentId: String,
    private val password: String,
) {
    private val generation = latch.generation
    private var acceptedAt: Int? = null

    /** The school took this try's post. Called as its answer is read, under [StoredPasswordLatch.automaticPosts]. */
    fun schoolAccepted() {
        acceptedAt = latch.changes.value
    }

    fun settle() {
        val seen = acceptedAt ?: return
        latch.settle(generation, seen, studentId, password)
    }
}
