package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.follow
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why a mail that went out has no copy in the sent folder, in the three ways the user can act on
 * differently: there is no sent folder to keep one in, filing it failed (and may work next time),
 * or the app could not tell whether the server keeps its own and declined to guess.
 */
sealed interface SentCopyNotice {
    data object NoFolder : SentCopyNotice
    data class Failed(val error: MailError) : SentCopyNotice
    data object Unknown : SentCopyNotice

    companion object {
        /** Null when a copy is known to be in the sent folder: nothing to say. */
        fun of(copy: SentCopy): SentCopyNotice? = when (copy) {
            SentCopy.Filed, SentCopy.ServerFiledItself -> null
            SentCopy.NotAttempted -> NoFolder
            is SentCopy.Failed -> Failed(copy.error)
            SentCopy.Unknown -> Unknown
        }
    }
}

/** Where a mail went out from, and so the one screen its [SentCopyNotice] belongs on. */
sealed interface SentCopyOrigin {
    /** A new mail or a draft, which compose closes back to the list. */
    data object List : SentCopyOrigin

    /** A reply or forward of the mail [uid] in [folder], or a `mailto:` link tapped in it. */
    data class Message(val folder: String, val uid: Long) : SentCopyOrigin
}

data class PostedSentCopyNotice(val notice: SentCopyNotice, val origin: SentCopyOrigin)

/**
 * The last sent copy's notice, from the compose screen that sent the mail to the screen it goes
 * back to ([SentCopyOrigin]): the list for a new mail, the mail being answered for a reply. The
 * send succeeded, so this is never a failure of anything: it stays until the user dismisses it,
 * and nothing else clears it but the account changing -- a sign-out, or another student signing
 * in over this one -- as the next student's screens must not carry it.
 */
@Singleton
class SentCopyNotices @Inject constructor(
    private val account: MailAccount,
    @ApplicationScope scope: CoroutineScope,
) {
    /**
     * The notice posted, which [report] publishes in a compare-and-set once it has asked after the
     * student. No data class: a new one is a change even with no notice on either side, so the
     * cleanup of the account changing -- which runs on [scope]'s dispatcher, not the sender's --
     * lands before a report or after it, never between its check and its publication, where it
     * used to leave the departing account's notice to the next sign-in.
     */
    private class Posting(val notice: PostedSentCopyNotice?)

    private val posting = MutableStateFlow(Posting(null))

    /** The notice last posted under the signed-in account, if any; a screen's own is [noticeFor]. */
    val posted: PostedSentCopyNotice? get() = posting.value.notice

    init {
        // Subscribed before this returns, as the repository's own sign-out collector is.
        scope.follow(account.events) { if (it !is AccountEvent.SignedIn) posting.value = Posting(null) }
    }

    /**
     * Runs inside [report] once the account was found unchanged, before the notice is published:
     * a test's seam for landing a sign-out exactly there. Null in the app.
     */
    internal var onReportChecked: (() -> Unit)? = null

    /**
     * Posts [notice] for [origin], unless the student the send began under, as [signIn], has left:
     * a send they left during reports nothing to the next one. A password typed again leaves them
     * here, and their notice with them.
     */
    fun report(notice: SentCopyNotice, origin: SentCopyOrigin, signIn: SignIn) {
        posting.update { current ->
            if (account.hasLeft(signIn)) return@update current
            onReportChecked?.invoke()
            // Published only if nothing moved [posting] since [current] was read; an account that
            // changed meanwhile fails this compare-and-set, and the next pass finds them gone.
            Posting(PostedSentCopyNotice(notice, origin))
        }
    }

    fun dismiss() {
        posting.value = Posting(null)
    }

    /** The notice for [origin]'s screen alone, kept current in [scope]. */
    fun noticeFor(origin: SentCopyOrigin, scope: CoroutineScope): StateFlow<SentCopyNotice?> =
        posting.map { it.notice?.takeIf { p -> p.origin == origin }?.notice }
            .stateIn(scope, SharingStarted.Eagerly, posted?.takeIf { it.origin == origin }?.notice)
}
