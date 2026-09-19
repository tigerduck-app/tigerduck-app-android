package org.ntust.app.tigerduck.mail.smtp

import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.mail.MailError

/**
 * What the dedupe probe found in the sent folder before the APPEND.
 *
 * Three values, not a `Boolean`: Mail2000's CAPABILITY banner promises no
 * particular SEARCH keys, so "the server says no such mail is there" and "the
 * server would not answer" are different facts that a `Boolean` reports as the
 * same `false`.
 */
enum class SentCopyProbe { FOUND, NOT_FOUND, UNKNOWN }

/**
 * What became of the user's own copy of a mail that was already sent.
 *
 * The SMTP half completes before any of this: none of these values means the
 * mail did not go out. They describe only whether the student can find it again
 * in their sent folder.
 */
sealed interface SentCopy {
    /** The app APPENDed it. */
    data object Filed : SentCopy

    /** The probe found it already there -- the server files its own copy. */
    data object ServerFiledItself : SentCopy

    /** There is no sent folder and none could be created, so nothing was tried. */
    data object NotAttempted : SentCopy

    /** The probe or the APPEND failed outright. */
    data class Failed(val error: MailError) : SentCopy

    /** The probe could not answer, so the APPEND was deliberately not attempted. */
    data object Unknown : SentCopy

    /** True only when a copy is known to be in the sent folder; the three silent-loss cases are all false. */
    val filed: Boolean get() = this == Filed || this == ServerFiledItself
}

/** A completed send: the Message-ID that went out, and what became of the sent copy. */
data class SendResult(val messageId: String, val sentCopy: SentCopy)

/** The sent-copy decisions, kept pure so they can be tested without a mail server. */
object SentCopyPolicy {
    /**
     * Runs the dedupe lookup and reports its three possible answers. A lookup that
     * throws is [SentCopyProbe.UNKNOWN] -- not [SentCopyProbe.NOT_FOUND], which is
     * the server actually answering.
     *
     * Cancellation is not a probe failure and always propagates.
     */
    fun probe(lookUp: () -> Boolean): SentCopyProbe = try {
        if (lookUp()) SentCopyProbe.FOUND else SentCopyProbe.NOT_FOUND
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SentCopyProbe.UNKNOWN
    }

    /**
     * Whether to APPEND our own copy.
     *
     * Only a server that answered "not there" gets one. An unanswered probe
     * ([SentCopyProbe.UNKNOWN]) deliberately does **not** append, and this is not
     * an oversight to be "fixed" the other way round: appending blind risks a
     * second copy of every mail in the student's sent folder, which they then have
     * to find and delete by hand, while not appending risks a missing copy --
     * quiet, and recoverable from the notice the compose screen shows. The
     * cheaper mistake wins.
     */
    fun appends(probe: SentCopyProbe): Boolean = probe == SentCopyProbe.NOT_FOUND

    /** The outcome to report for a probe [appends] declined to act on. */
    fun declined(probe: SentCopyProbe): SentCopy =
        if (probe == SentCopyProbe.FOUND) SentCopy.ServerFiledItself else SentCopy.Unknown
}
