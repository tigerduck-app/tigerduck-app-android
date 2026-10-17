package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.data.model.Assignment
import java.util.Date

/**
 * What Moodle answered for one assignment's submission status, to a page or a round, with when
 * the request went out and the account it went out under.
 */
data class SubmissionStatusAnswer(
    val assignmentId: String,
    val submitted: Boolean,
    /** Epoch milliseconds; null for one not handed in. */
    val submittedAtMs: Long?,
    /** A monotonic clock's reading before the request went out. */
    val askedAtMs: Long,
    /** The cache's generation when the request went out; see `DataCache.userDataGeneration`. */
    val generation: Int,
)

/**
 * The newest answer accepted for each cached assignment's status, with when Moodle was asked for
 * it, so an older answer never overwrites a newer one: a round's answers against a page's, and
 * one page's against another's. Times come from a monotonic clock, as a wall clock set back would
 * make a later answer look older. Ported from iOS `AppServiceBridge.statusAskedAt`.
 *
 * A round's answers are written by the screen that ran it, some time after the round, so each
 * round's list is reconciled against the accepted answers as it is written ([reconcile]), by the
 * round's own request times, rather than the page waiting the round out. Both the round's write
 * and a page's run under the cache's lock, so the two cannot pass each other.
 *
 * Every answer carries the cache's generation its request went out under, and the wipe at a
 * sign-out moves that generation under the same lock. An answer of a generation that has gone is
 * refused, and the answers kept under it go with it, so a departing account's round that writes
 * between the sign-out and the wipe leaves nothing the next account's rounds could inherit.
 */
class AssignmentStatusLedger(
    /** The cache's generation now, which the wipe at a sign-out moves under the cache's lock. */
    private val currentGeneration: () -> Int,
) {
    private data class Accepted(val askedAtMs: Long, val submitted: Boolean, val submittedAtMs: Long?)

    private val accepted = HashMap<String, Accepted>()

    /** The generation the entries in [accepted] were taken under. */
    private var generation: Int? = null

    /**
     * The entries, with those of a generation that has gone dropped first; null when [asked] is
     * not the current generation, whose answers are refused.
     */
    private fun entriesFor(asked: Int): HashMap<String, Accepted>? {
        val current = currentGeneration()
        if (generation != current) {
            accepted.clear()
            generation = current
        }
        return accepted.takeIf { asked == current }
    }

    /**
     * Keeps a page's [answer] as the assignment's accepted one, unless one asked for as late or
     * later is accepted, a round's or another page's, or the account it was asked for has signed
     * out; whether it was kept.
     */
    @Synchronized
    fun offer(answer: SubmissionStatusAnswer): Boolean {
        val entries = entriesFor(answer.generation) ?: return false
        val current = entries[answer.assignmentId]
        if (current != null && current.askedAtMs >= answer.askedAtMs) return false
        entries[answer.assignmentId] = Accepted(answer.askedAtMs, answer.submitted, answer.submittedAtMs)
        return true
    }

    /**
     * [assignments] as a round of [generation] writes them, [answers] being what Moodle answered
     * the round for each status it asked and got, by id. An assignment whose accepted answer is
     * newer than the round's request for it takes the accepted answer; the rest the round got an
     * answer for take that answer, which is accepted from here on, whatever the caller made of
     * the cached status meanwhile. One the round got no answer for, kept from the cache, takes
     * any accepted answer and is never stamped. A round of a generation that has gone writes
     * nothing of its own.
     */
    @Synchronized
    fun reconcile(assignments: List<Assignment>, answers: Map<String, SubmissionStatusAnswer>, generation: Int): List<Assignment> {
        val entries = entriesFor(generation)
        return assignments.map { assignment ->
            val id = assignment.assignmentId
            val answer = answers[id]?.takeIf { it.generation == generation }
            val current = entries?.get(id)
            when {
                current != null && (answer == null || current.askedAtMs > answer.askedAtMs) ->
                    assignment.copy(isCompleted = current.submitted, submittedAt = current.submittedAtMs?.let(::Date))
                answer != null && entries != null -> {
                    entries[id] = Accepted(answer.askedAtMs, answer.submitted, answer.submittedAtMs)
                    assignment.copy(isCompleted = answer.submitted, submittedAt = answer.submittedAtMs?.let(::Date))
                }
                else -> assignment
            }
        }
    }

    /** Forgets everything, at a sign-out: the next account's answers are not ordered against this one's. */
    @Synchronized
    fun clear() {
        accepted.clear()
    }
}
