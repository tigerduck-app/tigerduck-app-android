package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.smtp.SentCopy
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
    account: MailAccount,
    @ApplicationScope scope: CoroutineScope,
) {
    /**
     * The account's generation and the notice posted under it, as one value. [report] checks the
     * one and publishes the other in a single compare-and-set, and the account changing moves the
     * one and clears the other the same way, so the cleanup -- which runs on [scope]'s dispatcher,
     * not the sender's -- lands before a report or after it, never between its check and its
     * publication, where it used to leave the departing account's notice to the next sign-in.
     */
    private data class Posting(val generation: Int, val notice: PostedSentCopyNotice?)

    private val posting = MutableStateFlow(Posting(0, null))

    /** The notice last posted under the signed-in account, if any; a screen's own is [noticeFor]. */
    val posted: PostedSentCopyNotice? get() = posting.value.notice

    /**
     * Moved each time the account changes. A send reads it as it starts and hands it to [report],
     * so a send the account left while it was going out reports nothing to the next one.
     */
    val accountGeneration: Int get() = posting.value.generation

    init {
        // Subscribed before this returns, as the repository's own sign-out collector is.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            merge(account.signOuts, account.studentChanges).collect {
                posting.update { Posting(it.generation + 1, null) }
            }
        }
    }

    /**
     * Runs inside [report] once the account was found unchanged, before the notice is published:
     * a test's seam for landing a sign-out exactly there. Null in the app.
     */
    internal var onReportChecked: (() -> Unit)? = null

    /** Posts [notice] for [origin], unless the account has changed since [accountGeneration] was read. */
    fun report(notice: SentCopyNotice, origin: SentCopyOrigin, accountGeneration: Int) {
        posting.update { current ->
            if (current.generation != accountGeneration) return@update current
            onReportChecked?.invoke()
            // Published only if nothing moved [posting] since [current] was read; an account that
            // changed meanwhile fails this compare-and-set, and the next pass sees its generation.
            current.copy(notice = PostedSentCopyNotice(notice, origin))
        }
    }

    fun dismiss() {
        posting.update { it.copy(notice = null) }
    }

    /** The notice for [origin]'s screen alone, kept current in [scope]. */
    fun noticeFor(origin: SentCopyOrigin, scope: CoroutineScope): StateFlow<SentCopyNotice?> =
        posting.map { it.notice?.takeIf { p -> p.origin == origin }?.notice }
            .stateIn(scope, SharingStarted.Eagerly, posted?.takeIf { it.origin == origin }?.notice)
}
