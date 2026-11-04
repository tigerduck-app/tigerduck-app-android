package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.shared.account.SignIn
import java.io.OutputStream

sealed interface SearchOutcome {
    val messages: List<MailSummary>

    /**
     * The newest page of the matches the search index found, as the server has them now, and
     * [remaining]: the UIDs of every match not fetched yet, newest first, for
     * [SchoolMailRepository.searchPage] to fetch as the list scrolls.
     */
    data class Server(
        override val messages: List<MailSummary>,
        val remaining: List<Long> = emptyList(),
        /** The folder's UIDVALIDITY the matches were found under, which [remaining] only means anything in; null where unknown. */
        val uidValidity: Long? = null,
        /**
         * Whether the index held every mail of the folder when it was searched. False while the
         * first download is still bringing the older mail in: the search may have missed some.
         */
        val complete: Boolean = true,
    ) : SearchOutcome
    /**
     * The demo mailbox, which has no index; these are matches among the folder's newest page, with
     * [uidValidity] the page was read under (null where unknown). The list page substitutes the
     * rows it has loaded, pages back included, where they were read under the same UIDVALIDITY.
     */
    data class LoadedOnly(override val messages: List<MailSummary>, val uidValidity: Long? = null) : SearchOutcome
}

/**
 * A page of the matches a search left to fetch ([SearchOutcome.Server.remaining]), with the
 * folder's UIDVALIDITY it was read under: once that differs from the search's own, the folder was
 * recreated and the UIDs name other mail. Null where unknown.
 */
data class SearchPage(val uidValidity: Long?, val messages: List<MailSummary>)

/**
 * What a write through the repository did to a folder, for a screen that keeps a list of that
 * folder while the write is made on another: the mail list stays as it was on the way back from
 * a mail rather than fetching its page again, so this is how a mail read, moved or deleted there
 * reaches it (see [SchoolMailRepository.changes]).
 */
sealed interface MailChange {
    val folder: String

    /**
     * The sign-in the write was made under, read before it went out: a write can end after another
     * student signed in over the one who made it, and its folder and UID then name a mail of
     * theirs. A list takes only the changes of the account it loaded for.
     */
    val signIn: SignIn

    /** The mail's read flag is now [seen]. */
    data class Seen(override val folder: String, val uid: Long, val seen: Boolean, override val signIn: SignIn) : MailChange

    /** The mail left [folder]: moved elsewhere, deleted, or a draft discarded. */
    data class Removed(override val folder: String, val uid: Long, override val signIn: SignIn) : MailChange

    /**
     * [folder] holds mail the repository cannot describe without a fetch -- one moved in, a sent
     * copy filed, a draft saved -- or was found recreated on the server and its cache dropped.
     * A list of it has to show the server's page again.
     */
    data class Changed(override val folder: String, override val signIn: SignIn) : MailChange
}

/**
 * How a search reads mail that is already loaded, where the server's own SEARCH is not available:
 * the subject and the sender's name and address, case-insensitively.
 */
internal fun matchesLoadedMail(s: MailSummary, q: String) =
    q.isNotEmpty() && (s.subject.contains(q, true) || s.from?.address?.contains(q, true) == true || s.from?.name?.contains(q, true) == true)

interface SchoolMailRepository {
    /** The server's folders, resolved once per sign-in; [stillWanted] as on [loadPage], for the LIST a first call sends. */
    suspend fun folders(stillWanted: () -> Boolean = { true }): ResolvedFolders

    /** The folder's last saved page, read from disk on the IO dispatcher. */
    suspend fun cachedPage(folder: String): MailPage?

    /**
     * Forgets [folder]'s cached page while it is still the one of the mailbox generation
     * [obsoleteValidity]: the folder was found recreated on the server -- an older page, or a page
     * of search results, came back under another UIDVALIDITY than the list's -- so that page names
     * mail that no longer exists and must not be painted again before a first page replaces it. A
     * page already cached under the new generation, by a refresh that then failed on another
     * folder, is left alone: it is the only offline copy of the new mailbox. Nothing to forget for
     * the demo mailbox.
     */
    suspend fun dropCachedPage(folder: String, obsoleteValidity: Long)
    /**
     * @param background true for a warm nobody asked for. Such a call never shortens a cached
     *   page it did not improve on -- see [MailRepository.loadPage].
     * @param stillWanted asked with the session held, right before the command goes out, and
     *   again nowhere else: a request can wait behind another on the one connection, and the
     *   account or the view that asked may be gone by the time it is its turn. Answering false
     *   ends the call with [MailError.Abandoned] and nothing sent -- so a request of a batch that
     *   outlived its account never runs under the next one's credentials, and an answer that
     *   comes back was asked for by whoever is signed in when it was sent.
     */
    suspend fun loadPage(
        folder: String,
        beforeSeq: Int?,
        limit: Int = MailRepository.PAGE_SIZE,
        background: Boolean = false,
        stillWanted: () -> Boolean = { true },
    ): MailPage

    /** The inbox's STATUS; [stillWanted] as on [loadPage]. */
    suspend fun inboxStatus(stillWanted: () -> Boolean = { true }): FolderStatus

    /**
     * [folder]'s STATUS: the cheapest way to ask the server whether a folder is as the page last
     * saw it. [stillWanted] as on [loadPage].
     */
    suspend fun status(folder: String, stillWanted: () -> Boolean = { true }): FolderStatus
    suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags>
    /**
     * Runs [block] with its requests ahead of every other one's, the background download's batches
     * included: see [SessionHolder.ahead]. For what the user is waiting on, and nothing longer.
     */
    suspend fun <T> ahead(block: suspend () -> T): T = block()

    suspend fun summary(folder: String, uid: Long): MailSummary?
    suspend fun body(folder: String, uid: Long): MailBody
    suspend fun setSeen(folder: String, uid: Long, seen: Boolean)

    /**
     * [stillWanted] as on [loadPage], for the COPY and STORE the move sends: a move confirmed for
     * one student that waited for the session while another signed in over them would otherwise
     * move a mail of theirs with the same UID.
     *
     * [uidValidity], when the caller has it, is the UIDVALIDITY [uid] was read under, checked
     * against the server's as the folder is selected for the write ([MailSession.move]): a folder
     * recreated since ends the call with [MailError.FolderChanged] and nothing sent, even when the
     * cache has been replaced by a page of the new folder meanwhile, which the cache's own check
     * would let through.
     */
    suspend fun move(folder: String, uid: Long, target: String, uidValidity: Long? = null, stillWanted: () -> Boolean = { true })

    /** [stillWanted] as on [move], for the LIST and CREATE a missing trash costs. */
    suspend fun deletesPermanently(folder: String, stillWanted: () -> Boolean = { true }): Boolean

    /** [uidValidity] and [stillWanted] as on [move]. */
    suspend fun delete(folder: String, uid: Long, uidValidity: Long? = null, stillWanted: () -> Boolean = { true })

    /**
     * The mail of [folder] whose sender, subject or text holds [query], found in the search index
     * on this phone: the school's server cannot search in Chinese. Mail come in since the index
     * last caught up is added first, the newest few. [stillWanted] as on [loadPage].
     */
    suspend fun search(folder: String, query: String, stillWanted: () -> Boolean = { true }): SearchOutcome

    /**
     * Downloads into the search index every mail it lacks, in every folder, Inbox and Sent first;
     * nothing within [MailRepository.INDEX_RESYNC_MS] of a download that got through. Long: the
     * first run fetches the text of the whole mailbox. [stillWanted] as on [loadPage].
     */
    suspend fun updateSearchIndex(stillWanted: () -> Boolean = { true })

    /** How far [updateSearchIndex] has come while it downloads; null otherwise. */
    val searchIndexProgress: StateFlow<MailIndexProgress?>

    /** The disk the mail downloaded for search and the list takes on the phone, in bytes. */
    suspend fun searchIndexBytes(): Long

    /**
     * Every mail of the signed-in account on the phone: what [updateSearchIndex] downloaded, in
     * every folder, which belongs to one account. The compose screen suggests recipients from it
     * (`RecipientSuggestions`); none once the account has signed out, and none of an account
     * another student signed in over.
     */
    suspend fun correspondence(): List<MailSummary>

    /**
     * Deletes the mail downloaded for search and the list, stopping any download under way. The
     * mail screen downloads it again when it is next opened.
     */
    suspend fun deleteSearchIndex()

    /**
     * The summaries of [uids], matches [search] left in [SearchOutcome.Server.remaining]; mail gone
     * since is left out. [stillWanted] as on [loadPage].
     */
    suspend fun searchPage(folder: String, uids: List<Long>, stillWanted: () -> Boolean = { true }): SearchPage

    /** Cache-first like [body], so revisiting a mail's source never re-downloads it. */
    suspend fun rawSource(folder: String, uid: Long): String
    suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream)
    /**
     * Sends [mail]. Throws only when the mail did not go out; what became of the student's own
     * copy of it comes back as [SentCopy] instead, because by then the mail is already delivered
     * and there is nothing to fail.
     */
    suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?): SentCopy
    suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?)

    /** Permanently removes a draft after it was sent from the compose screen. */
    suspend fun discardDraft(uid: Long)
    fun selfAddress(): MailAddress
    fun release()

    /** The mail screen became active again: cancels a pending idle close so the connection isn't dropped mid-visit. No-op in demo mode. */
    fun acquire()

    /**
     * Downloads mail for search now, as a pull to refresh asks: past the wait after a whole
     * download that [acquire] keeps to, and without waiting for the background run's retry. No
     * second one while one is under way, nor in demo mode or after a rejected password.
     */
    fun downloadNow()

    /**
     * Every write made through the repository, as it lands: what the message and compose screens
     * do to a mail reaches the list that stays behind them, the way the iPhone's list is told
     * (`markSeenLocally` / `removeLocally` there). Only for a write that succeeded, and only to
     * whoever is collecting at the time -- nothing is replayed to a list that loads afresh anyway.
     */
    val changes: SharedFlow<MailChange>
}
