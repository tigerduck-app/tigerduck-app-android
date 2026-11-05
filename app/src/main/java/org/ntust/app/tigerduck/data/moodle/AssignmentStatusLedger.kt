package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.account.SignIn
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
    /** The sign-in the request went out under. */
    val signIn: SignIn,
    /**
     * Epoch milliseconds of the extension a teacher granted the student, `extensionduedate`;
     * null for none, which takes a revoked one off.
     */
    val extensionDueMs: Long? = null,
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
 * Every answer carries the sign-in its request went out under. An answer of a sign-in that has
 * ended is refused, and the answers kept under it go with it, so a departing account's round that
 * writes between the sign-out and the wipe leaves nothing the next account's rounds could inherit.
 */
class AssignmentStatusLedger {
    private data class Accepted(val askedAtMs: Long, val submitted: Boolean, val submittedAtMs: Long?, val extensionDueMs: Long?) {
        constructor(answer: SubmissionStatusAnswer) :
            this(answer.askedAtMs, answer.submitted, answer.submittedAtMs, answer.extensionDueMs)

        /** [assignment] as this answer has it: its submission, and the extension it runs to. */
        fun applyTo(assignment: Assignment) = assignment.copy(
            isCompleted = submitted,
            submittedAt = submittedAtMs?.let(::Date),
            extensionDueDate = extensionDueMs?.let(::Date),
        )
    }

    private val accepted = HashMap<String, Accepted>()

    /** The sign-in the entries in [accepted] were taken under. */
    private var owner: SignIn? = null

    /**
     * The entries, with those of a sign-in that has ended dropped first; null when [asked] has
     * ended, whose answers are refused. An account has one current sign-in, so when [asked] is
     * current the entries left are its own.
     */
    private fun entriesFor(asked: SignIn): HashMap<String, Accepted>? {
        if (owner?.isCurrent != true) accepted.clear()
        if (!asked.isCurrent) return null
        owner = asked
        return accepted
    }

    /**
     * Keeps a page's [answer] as the assignment's accepted one, unless one asked for as late or
     * later is accepted, a round's or another page's, or the account it was asked for has signed
     * out; whether it was kept.
     */
    @Synchronized
    fun offer(answer: SubmissionStatusAnswer): Boolean {
        val entries = entriesFor(answer.signIn) ?: return false
        val current = entries[answer.assignmentId]
        if (current != null && current.askedAtMs >= answer.askedAtMs) return false
        entries[answer.assignmentId] = Accepted(answer)
        return true
    }

    /**
     * [assignments] as a round of [signIn] writes them, [answers] being what Moodle answered
     * the round for each status it asked and got, by id. An assignment whose accepted answer is
     * newer than the round's request for it takes the accepted answer; the rest the round got an
     * answer for take that answer, which is accepted from here on, whatever the caller made of
     * the cached status meanwhile. One the round got no answer for, kept from the cache, takes
     * any accepted answer and is never stamped. A round of a sign-in that has ended writes
     * nothing of its own.
     */
    @Synchronized
    fun reconcile(assignments: List<Assignment>, answers: Map<String, SubmissionStatusAnswer>, signIn: SignIn): List<Assignment> {
        val entries = entriesFor(signIn)
        return assignments.map { assignment ->
            val id = assignment.assignmentId
            val answer = answers[id]?.takeIf { it.signIn == signIn }
            val current = entries?.get(id)
            when {
                current != null && (answer == null || current.askedAtMs > answer.askedAtMs) -> current.applyTo(assignment)
                answer != null && entries != null -> {
                    val taken = Accepted(answer)
                    entries[id] = taken
                    taken.applyTo(assignment)
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
