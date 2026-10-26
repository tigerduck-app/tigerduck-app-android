package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt

/** What the cache holds about the assignment a reminder fired for. */
data class CachedReminderAssignment(
    val dueAtMs: Long,
    val submitted: Boolean,
    val markedDone: Boolean,
    val ignored: Boolean,
    val courseIgnored: Boolean,
)

/** Moodle's answer to the status call made for a reminder, or why there is none. */
sealed interface ReminderStatusAnswer {
    data class Answered(val lastAttempt: MoodleSubmissionLastAttempt?) : ReminderStatusAnswer
    data object NoToken : ReminderStatusAnswer
    data object TokenRejected : ReminderStatusAnswer

    /** No network, an error, a page from the NTUST edge: anything but an answer. */
    data object NoAnswer : ReminderStatusAnswer

    /** An answer that came after one asked for later, which the cache already has. */
    data object Superseded : ReminderStatusAnswer
}

/** Why a reminder that fired was not shown. */
enum class ReminderDropReason {
    NOT_IN_CACHE,
    SUBMITTED_IN_CACHE,
    MARKED_DONE,
    IGNORED,
    COURSE_IGNORED,
    DUE_DATE_CHANGED,
    NO_TOKEN,
    TOKEN_REJECTED,
    NO_ANSWER,
    SUBMITTED_ON_MOODLE,

    /** No lastattempt, or submission records none of which is new, a draft or reopened. */
    UNKNOWN_STATUS,
    SUBMISSIONS_DISABLED,
    LOCKED,
    EXTENDED,

    /**
     * Moodle's answer came after one asked for later, a page's or a round's: the cache has that
     * one, and the reminder drops as with no answer when the cache does not drop it already.
     */
    SUPERSEDED,

    /** The whole check ran past its budget; the receiver's own reason. */
    TIMED_OUT,

    /** Reminders or notifications were turned off while the check ran; the receiver's own reason. */
    TURNED_OFF,
}

sealed interface ReminderVerdict {
    data object Show : ReminderVerdict
    data class Drop(val reason: ReminderDropReason) : ReminderVerdict
}

/**
 * Whether an assignment reminder that just fired may show: only while the
 * cache and then Moodle both still have the assignment due. A missed reminder
 * is better than one for work already handed in, so anything else drops it.
 */
object AssignmentReminderPolicy {

    private val OPEN_STATUSES = setOf("new", "draft", "reopened")

    /**
     * The cache's half of [decide], which needs no request: why the reminder
     * drops, or null while the cache still has it due. [armedDueAtMs] is the
     * due date the alarm was armed for, null on one armed without it.
     */
    fun cacheDrop(cached: CachedReminderAssignment?, armedDueAtMs: Long?): ReminderDropReason? =
        when {
            cached == null -> ReminderDropReason.NOT_IN_CACHE
            cached.submitted -> ReminderDropReason.SUBMITTED_IN_CACHE
            cached.markedDone -> ReminderDropReason.MARKED_DONE
            cached.ignored -> ReminderDropReason.IGNORED
            cached.courseIgnored -> ReminderDropReason.COURSE_IGNORED
            armedDueAtMs != null && armedDueAtMs != cached.dueAtMs -> ReminderDropReason.DUE_DATE_CHANGED
            else -> null
        }

    fun decide(
        cached: CachedReminderAssignment?,
        armedDueAtMs: Long?,
        answer: ReminderStatusAnswer,
    ): ReminderVerdict {
        if (cached == null) return ReminderVerdict.Drop(ReminderDropReason.NOT_IN_CACHE)
        // Past the cache's half, the due date the alarm was armed for is the cache's.
        val reason = cacheDrop(cached, armedDueAtMs) ?: answerDrop(answer, cached.dueAtMs)
        return if (reason == null) ReminderVerdict.Show else ReminderVerdict.Drop(reason)
    }

    private fun answerDrop(answer: ReminderStatusAnswer, dueAtMs: Long): ReminderDropReason? =
        when (answer) {
            ReminderStatusAnswer.NoToken -> ReminderDropReason.NO_TOKEN
            ReminderStatusAnswer.TokenRejected -> ReminderDropReason.TOKEN_REJECTED
            ReminderStatusAnswer.NoAnswer -> ReminderDropReason.NO_ANSWER
            ReminderStatusAnswer.Superseded -> ReminderDropReason.SUPERSEDED
            is ReminderStatusAnswer.Answered -> attemptDrop(answer.lastAttempt, dueAtMs)
        }

    private fun attemptDrop(attempt: MoodleSubmissionLastAttempt?, dueAtMs: Long): ReminderDropReason? {
        if (attempt == null) return ReminderDropReason.UNKNOWN_STATUS
        val records = listOfNotNull(attempt.submission, attempt.teamsubmission)
        // No record at all is Moodle's "No attempt": nothing saved yet, still to hand in like new.
        val open = records.isEmpty() || records.any { it.status in OPEN_STATUSES }
        val extendedToMs = (attempt.extensionduedate ?: 0L) * 1000
        return when {
            attempt.handedIn != null -> ReminderDropReason.SUBMITTED_ON_MOODLE
            !open -> ReminderDropReason.UNKNOWN_STATUS
            attempt.submissionsenabled == false -> ReminderDropReason.SUBMISSIONS_DISABLED
            attempt.locked == true -> ReminderDropReason.LOCKED
            // [dueAtMs] is the student's own date as the cache has it, an extension it knows of
            // included: one later still is news to the cache, and the next fetch arms for it.
            extendedToMs > dueAtMs -> ReminderDropReason.EXTENDED
            else -> null
        }
    }
}
