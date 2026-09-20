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

    suspend fun check(source: CheckSource): CheckOutcome {
        val outcome = if (!mutex.tryLock()) {
            CheckOutcome.Busy
        } else {
            try {
                run(source)
            } finally {
                mutex.unlock()
            }
        }
        record(source, outcome)
        return outcome
    }

    /** The list page showed the inbox up to [status]; those mails must never notify later. */
    fun noteSeenByPage(status: FolderStatus) {
        if (status.uidValidity != state.inboxUidValidity) {
            state.inboxUidValidity = status.uidValidity
            state.inboxSeenUidNext = status.uidNext
        } else if (status.uidNext > state.inboxSeenUidNext) {
            state.inboxSeenUidNext = status.uidNext
        }
    }

    private suspend fun run(source: CheckSource): CheckOutcome = withContext(Dispatchers.IO) {
        val credentials = account.credentialsOrNull() ?: return@withContext CheckOutcome.NotSignedIn
        if (state.authFailed || !state.notificationsEnabled) return@withContext CheckOutcome.Disabled
        if (source == CheckSource.FOREGROUND) {
            val now = clock.now()
            if (now - state.lastForegroundCheckAt < FOREGROUND_THROTTLE_MS) return@withContext CheckOutcome.Throttled
            state.lastForegroundCheckAt = now
        }
        if (account.isDemo) return@withContext CheckOutcome.Demo
        try {
            sessions.open(credentials).use { session ->
                val status = session.status(INBOX)
                val marker = state.inboxSeenUidNext
                if (status.uidValidity != state.inboxUidValidity || marker <= 0) {
                    state.inboxUidValidity = status.uidValidity
                    state.inboxSeenUidNext = status.uidNext
                    return@withContext CheckOutcome.Baseline(status.uidNext)
                }
                if (status.uidNext <= marker) return@withContext CheckOutcome.NoChange
                val fresh = session.fetchSince(INBOX, marker).filter { it.uid >= marker }
                // Max fetched UID + 1, not STATUS's UIDNEXT: mail that arrived between
                // the two commands is then neither skipped nor notified twice.
                val advanced = fresh.maxOfOrNull { it.uid + 1 } ?: status.uidNext
                val toNotify = fresh.filter { !it.flags.seen && !it.flags.deleted }
                if (toNotify.isNotEmpty()) notifier.postNewMail(INBOX, toNotify)
                prefetchBodies(session, status.uidValidity, toNotify)
                // Spec §8.5 order: notify, then advance -- and never backwards. The page
                // poll's noteSeenByPage can move the marker further while this check runs;
                // overwriting it would re-notify mail the list has already shown.
                state.inboxSeenUidNext = maxOf(state.inboxSeenUidNext, advanced)
                CheckOutcome.NewMail(toNotify.size)
            }
        } catch (e: CancellationException) {
            // The one place in the package that used to let a cancellation through to classify,
            // which has no case for one: the check was recorded as Failed:Protocol before the
            // surrounding withContext rethrew it anyway.
            throw e
        } catch (e: Exception) {
            val error = MailErrors.classify(e)
            if (error is MailError.AuthFailed) {
                account.onAuthFailure()
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
    private fun prefetchBodies(session: MailSession, uidValidity: Long, fresh: List<MailSummary>) {
        fresh.sortedByDescending { it.uid }.take(BODY_PREFETCH_LIMIT).forEach { summary ->
            runCatching { cache.saveBody(INBOX, summary.uid, uidValidity, session.fetchBody(INBOX, summary.uid)) }
        }
    }

    private companion object {
        const val INBOX = "INBOX"
        const val FOREGROUND_THROTTLE_MS = 60_000L
        const val BODY_PREFETCH_LIMIT = 5
    }
}
