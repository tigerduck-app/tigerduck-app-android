package org.ntust.app.tigerduck.mail.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailStateStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class CheckSource { FOREGROUND, ALARM, WORKER }

sealed interface CheckOutcome {
    val label: String get() = javaClass.simpleName

    data object NotSignedIn : CheckOutcome
    data object Disabled : CheckOutcome
    data object Throttled : CheckOutcome
    data object Busy : CheckOutcome
    data object Demo : CheckOutcome
    data object NoChange : CheckOutcome
    data class Baseline(val uidNext: Long) : CheckOutcome
    data class NewMail(val notified: Int) : CheckOutcome
    data class Failed(val error: MailError) : CheckOutcome {
        override val label: String get() = "Failed:${error.javaClass.simpleName}"
    }
}

fun interface MailClock {
    fun now(): Long
}

/**
 * The new-mail check (spec §8.5), shared by every trigger. One at a time:
 * a check that finds another running returns [CheckOutcome.Busy] at once.
 * Opens its own short-lived connection.
 */
@Singleton
class MailChecker @Inject constructor(
    private val account: MailAccount,
    private val state: MailStateStore,
    private val sessions: MailSessionFactory,
    private val notifier: MailNotifier,
    private val cache: MailCache,
    private val clock: MailClock,
) {
    private val mutex = Mutex()

    // Held around each read-and-write of the inbox marker: a check's and the list's.
    private val markerLock = Any()

    suspend fun check(source: CheckSource): CheckOutcome {
        val generation = account.accountGeneration
        val outcome = if (!mutex.tryLock()) {
            CheckOutcome.Busy
        } else {
            try {
                run(source)
            } finally {
                mutex.unlock()
            }
        }
        // The inbox answered, whatever it held -- unless the account it answered has signed out
        // since, and the date would land in the state its sign-out just cleared.
        val reached = outcome is CheckOutcome.Baseline || outcome is CheckOutcome.NoChange || outcome is CheckOutcome.NewMail
        if (reached) state.setFetchedAtIf(INBOX, clock.now()) { account.accountGeneration == generation }
        record(source, outcome)
        return outcome
    }

    /**
     * When [folder] (a server name) last answered this account, epoch ms, or 0: a check that
     * reached the inbox, or the list page reading the folder ([noteFetchedByPage]). What the
     * School Mail page's "Last synced" row dates, for the folder on screen; cleared with the rest
     * of the mail state at sign-out.
     */
    fun fetchedAt(folder: String): Long = state.fetchedAt(folder)

    /**
     * The list page read [folder] from the server, for the account whose
     * [MailAccount.accountGeneration] was [generation] when it asked; says whether that dated it.
     * Not the demo mailbox, which has no server: like every other page on the demo account, it
     * was never synced. Nor for an account that has left since -- a sign-out, or another sign-in,
     * even the same student's: the fetch was the sign-in before's, and would date folders nothing
     * was fetched for. Asked under the store's lock, which the sign-out's clear takes too
     * ([MailStateStore.setFetchedAtIf]), so a date it allows goes with that clear.
     */
    fun noteFetchedByPage(folder: String, generation: Int): Boolean {
        if (account.isDemo) return false
        return state.setFetchedAtIf(folder, clock.now()) { account.accountGeneration == generation }
    }

    /**
     * The list page showed the inbox up to [status]; those mails must never notify later. For the
     * account at [generation] when the page asked: a status that lands after its sign-out says
     * nothing about the next account's inbox, and must not move its marker.
     */
    fun noteSeenByPage(status: FolderStatus, generation: Int) = synchronized(markerLock) {
        if (account.accountGeneration != generation) return@synchronized
        if (status.uidValidity != state.inboxUidValidity) {
            state.inboxUidValidity = status.uidValidity
            state.inboxSeenUidNext = status.uidNext
        } else if (status.uidNext > state.inboxSeenUidNext) {
            state.inboxSeenUidNext = status.uidNext
        }
    }

    private suspend fun run(source: CheckSource): CheckOutcome = withContext(Dispatchers.IO) {
        // The account with its generation, read together: a sign-out or a sign-in from here on,
        // even the same student's again, makes this check's results the sign-in before's.
        val signedIn = account.snapshot() ?: return@withContext CheckOutcome.NotSignedIn
        val generation = signedIn.generation
        val credentials = signedIn.credentials
        if (state.authFailed || !state.notificationsEnabled) return@withContext CheckOutcome.Disabled
        if (source == CheckSource.FOREGROUND) {
            val now = clock.now()
            if (now - state.lastForegroundCheckAt < FOREGROUND_THROTTLE_MS) return@withContext CheckOutcome.Throttled
            state.lastForegroundCheckAt = now
        }
        if (account.isDemo) return@withContext CheckOutcome.Demo
        // The state, the cache and the notifications belong to whoever is signed in now. A check
        // that outlives its account -- a sign-out, or another student signing in meanwhile --
        // writes, files and posts nothing more, so it asks again right before each of those.
        val stillSignedIn = { account.accountGeneration == generation }
        try {
            sessions.open(credentials).use { session ->
                val status = session.status(INBOX)
                val marker = synchronized(markerLock) {
                    if (!stillSignedIn()) return@withContext CheckOutcome.NotSignedIn
                    val stored = state.inboxSeenUidNext
                    if (status.uidValidity != state.inboxUidValidity || stored <= 0) {
                        state.inboxUidValidity = status.uidValidity
                        state.inboxSeenUidNext = status.uidNext
                        return@withContext CheckOutcome.Baseline(status.uidNext)
                    }
                    stored
                }
                if (status.uidNext <= marker) return@withContext CheckOutcome.NoChange
                val fresh = session.fetchSince(INBOX, marker).filter { it.uid >= marker }
                // Max fetched UID + 1, not STATUS's UIDNEXT: mail that arrived between
                // the two commands is then neither skipped nor notified twice.
                val advanced = fresh.maxOfOrNull { it.uid + 1 } ?: status.uidNext
                // Muted mail (quiet hours, mute rules) is passed over like a notified one: the
                // marker below moves past it, so it never notifies later, and the summary
                // notification leaves it out of its count. Judged by when it arrived, or by now
                // where the server gave no INTERNALDATE.
                val mute = state.muteSettings
                val checkedAt = Instant.ofEpochMilli(clock.now())
                val toNotify = fresh.filter {
                    !it.flags.seen && !it.flags.deleted && !mute.mutes(it, it.receivedAt ?: checkedAt)
                }
                // Posted after the sign-out, the departing student's sender and subject would
                // stay on the lock screen.
                if (!stillSignedIn()) return@withContext CheckOutcome.NotSignedIn
                if (toNotify.isNotEmpty()) {
                    notifier.postNewMail(INBOX, toNotify)
                    // A sign-out during the post may have run its cleanup before it landed.
                    if (!stillSignedIn()) {
                        notifier.cancelPosted(toNotify)
                        return@withContext CheckOutcome.NotSignedIn
                    }
                }
                // Spec §8.5 order: notify, then advance -- and never backwards. The page
                // poll's noteSeenByPage can move the marker further while this check runs;
                // overwriting it would re-notify mail the list has already shown. It can also
                // adopt a new UIDVALIDITY, whose marker this check's UIDs say nothing about.
                synchronized(markerLock) {
                    if (stillSignedIn() && state.inboxUidValidity == status.uidValidity) {
                        state.inboxSeenUidNext = maxOf(state.inboxSeenUidNext, advanced)
                    }
                }
                // Last, and deliberately after the marker. Warming bodies is a convenience the
                // check's real job does not depend on, and it is the slowest thing here -- up to
                // five fetches on a connection that may be on a train. Run before the advance, a
                // process death anywhere in it would leave the marker where it was and notify
                // every one of these mails again on the next check.
                prefetchBodies(session, status.uidValidity, toNotify, stillSignedIn)
                CheckOutcome.NewMail(toNotify.size)
            }
        } catch (e: CancellationException) {
            // The one place in the package that used to let a cancellation through to classify,
            // which has no case for one: the check was recorded as Failed:Protocol before the
            // surrounding withContext rethrew it anyway.
            throw e
        } catch (e: Exception) {
            // A rejection of an account that has signed out would stop the next one's checks.
            if (!stillSignedIn()) return@withContext CheckOutcome.NotSignedIn
            val error = MailErrors.classify(e)
            if (error is MailError.AuthFailed) {
                // Asked again in the same step as the mark: a sign-in since is the server's
                // answer to a password it took.
                if (!account.onAuthFailure(generation)) return@withContext CheckOutcome.NotSignedIn
                notifier.postAuthFailure()
            }
            CheckOutcome.Failed(error)
        }
    }

    private fun record(source: CheckSource, outcome: CheckOutcome) {
        state.diagnostics = listOf("${clock.now()}|${source.name}|${outcome.label}") + state.diagnostics
    }

    /**
     * Pulls the newest arrivals' bodies down on the connection this check already has open, so
     * tapping the notification opens a mail that is already there rather than a spinner.
     *
     * Bounded to [BODY_PREFETCH_LIMIT]: a burst of mail must not turn a check that should take a
     * second into a long one, and the alarm that scheduled it is not a good place to be slow.
     * Newest first, because that is the one the notification is about.
     *
     * Never throws. This is a convenience on top of a check whose real job -- notifying, and
     * moving the marker -- has already succeeded by the time it runs; a body that will not come
     * down means only that opening that mail is as slow as it used to be.
     */
    private fun prefetchBodies(
        session: MailSession,
        uidValidity: Long,
        arrivals: List<MailSummary>,
        stillSignedIn: () -> Boolean,
    ) {
        arrivals.sortedByDescending { it.uid }.take(BODY_PREFETCH_LIMIT).forEach { summary ->
            // Filed after the sign-out emptied the cache, the departing student's mail would
            // stay on disk for whoever signs in next.
            if (!stillSignedIn()) return
            try {
                val body = session.fetchBody(INBOX, summary.uid)
                // Asked under the cache's lock, which the sign-out's clear takes too.
                if (!cache.saveBodyIf(INBOX, summary.uid, uidValidity, body, stillSignedIn)) return
            } catch (e: CancellationException) {
                // Not runCatching: that catches Throwable, so a cancellation would be swallowed
                // here and the loop would carry on fetching for a check nobody is waiting for.
                // The same rule SchoolMailListViewModel's two prefetches follow.
                throw e
            } catch (e: Exception) {
                // Silent by design (see the doc above).
            }
        }
    }

    private companion object {
        const val INBOX = "INBOX"
        const val FOREGROUND_THROTTLE_MS = 60_000L
        const val BODY_PREFETCH_LIMIT = 5
    }
}
