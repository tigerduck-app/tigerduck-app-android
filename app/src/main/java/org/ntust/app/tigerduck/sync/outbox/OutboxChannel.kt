package org.ntust.app.tigerduck.sync.outbox

import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.shared.account.SignIn
import java.io.IOException

/**
 * The outbox's channels, one per kind of edit, in the order [Outbox.drain] sends them: today's
 * order, in which Home's full sync, its revision poll and the background worker each send what
 * waits (`OutboxOrderTest`). A new channel takes its place here, and the order is a decision.
 */
enum class OutboxChannelId { COURSE_IGNORES, MARKS, COURSE_ADDS, COURSE_DELETES, COURSE_OVERRIDES, HOLIDAYS }

/** What became of one send of a queued edit; see [OutboxLane] for what each does to it. */
sealed interface SendResult {
    /** The backend has it, as of its clock [serverClockUs] (`X-TigerSync-Clock`, µs), when the reply named one. */
    data class Sent(val serverClockUs: Long?) : SendResult

    /** Nothing went: TigerSync, its category or the re-enable hold keeps the edit on this phone for now. */
    data object NotSent : SendResult

    /** The request failed for a reason that says nothing about the edit: no network, a session to renew, a busy or failing server. */
    data object Retry : SendResult

    /** The backend refused the edit; one attempt towards parking it. */
    data object Refused : SendResult

    /** The backend can't take the edit before a course upload lands, as a course it does not have yet. */
    data object WaitsForUpload : SendResult

    companion object {
        /**
         * What a failed send of a queued edit is, from its failure alone, as iOS's
         * `OutboxErrorPolicy.keepsWithoutAttempt` decides: no network, no session or one to renew,
         * a timeout, a busy server or a failing one ([Retry]) say nothing about the edit; any other
         * answer of the backend, or a failure no network explains, is its refusal ([Refused]). A
         * channel that tells more from a failure, as a course the backend does not have yet
         * ([WaitsForUpload]), asks it first. For every failure a `/v3` write throws, this is the
         * rule the marks and the course overrides have counted by (`CourseIgnoreSync.refuses`); a
         * `/v4` refusal ([org.ntust.app.tigerduck.network.backend.BackendException]) is read by its
         * status, though it is an [IOException].
         */
        fun afterFailure(failure: Exception): SendResult {
            val status = failure.backendError()?.status
            return when {
                status != null -> if (status == 401 || status == 408 || status == 429 || status in 500..599) Retry else Refused
                failure is IOException || failure is NotSignedInException -> Retry
                else -> Refused
            }
        }
    }
}

/**
 * One kind of queued edit: a queue that keeps its own file and composes an [OutboxLane], and
 * registers itself with the [Outbox] in its own file (`@Provides @IntoSet`).
 */
interface OutboxChannel {
    val id: OutboxChannelId

    /** Sends what waits, oldest first, under [signIn], which the entries read from disk belong to. */
    suspend fun drain(signIn: SignIn)

    /** Gives each parked edit one more try, at the next [drain]: a course upload has landed. */
    suspend fun requeueParked(signIn: SignIn)

    /** The fields ([org.ntust.app.tigerduck.sync.SyncField]) an edit waits for, parked ones included. */
    suspend fun heldFields(): Set<String>

    /** Forgets what this process holds in memory for the account: parked-retry flags, attempts counted in memory. */
    fun forgetMemory()
}
