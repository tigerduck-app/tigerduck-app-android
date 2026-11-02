package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.follow
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserDataCleaner
import org.ntust.app.tigerduck.data.MoodleColorStore
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.AccountNotifications
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What follows the school account's sign-in and sign-out, wherever they came from: the
 * onboarding wizard, Settings, the full reset. Each used to do its own share: the wizard did
 * none, so a student who signed in there got no background sync until the app was next created
 * from scratch, and the full reset left the reminders and the Live Update running.
 *
 * Follows the account's [AccountEvent]s rather than the pages, and so only its changes: the state
 * the process starts in is not one. MainActivity schedules the background sync for an account
 * already signed in at launch. Never waits on anything slow, the browser's clearing least of
 * all, so no sign-out goes unseen.
 */
@Singleton
class AccountSessionEffects internal constructor(
    private val context: Context,
    private val events: Flow<AccountEvent>,
    /** Whether the school account shows as signed in now. */
    private val signedIn: () -> Boolean,
    private val assignmentReminders: AssignmentNotificationScheduler,
    private val refreshLiveUpdates: () -> Unit,
    private val stopLiveUpdates: () -> Unit,
    /** Clears the TigerDuck browser: the account it was signed in to is not the one now. */
    private val clearBrowser: suspend () -> Unit,
    /** Whether a clearing a process death cut short is still owed; see [BrowserCleanup]. */
    private val browserClearingOwed: () -> Boolean,
    /** Forgets which courses take Moodle's color: the next student's courses are new to the app. */
    private val forgetMoodleColors: suspend () -> Unit = {},
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authService: AuthService,
        sessions: AccountSessions,
        assignmentReminders: AssignmentNotificationScheduler,
        liveActivityManager: LiveActivityManager,
        browserData: BrowserDataCleaner,
        browserCleanup: BrowserCleanup,
        moodleColors: MoodleColorStore,
    ) : this(
        context = context,
        events = sessions.events,
        signedIn = { authService.authState.value },
        assignmentReminders = assignmentReminders,
        refreshLiveUpdates = { liveActivityManager.refresh() },
        stopLiveUpdates = liveActivityManager::stop,
        clearBrowser = browserData::clear,
        browserClearingOwed = browserCleanup::isPending,
        forgetMoodleColors = moodleColors::forgetAccount,
    )

    /**
     * Follows the school account on [scope] for the life of the process. [signOutCutShort] says the
     * start has just finished a sign-out a process death cut short ([AuthService.finishOwedSignOut]):
     * that is no change from here, or was never one if the account was already gone, so what
     * follows a sign-out runs now.
     */
    fun start(scope: CoroutineScope, signOutCutShort: Boolean = false) {
        // Subscribed now, not when the watcher first runs: a sign-out in between is a change.
        scope.follow(events.filter { it.account == Account.SCHOOL }) { event ->
            when (event) {
                is AccountEvent.SignedOut -> onSignedOut(scope)
                is AccountEvent.SignedIn -> when {
                    // The same student typing their password again never turns authState off and on.
                    event.byHand -> clearBrowserSoon(scope)
                    // Seen late, with the sign-out behind it, there is nothing to start.
                    signedIn() -> onSignedIn(scope)
                }
                is AccountEvent.StudentChanged -> Unit
            }
        }
        when {
            signOutCutShort -> scope.launch { onSignedOut(scope) }
            // One a process death cut short, or one whose deletions did not all report, goes again.
            browserClearingOwed() -> clearBrowserSoon(scope)
        }
    }

    private fun onSignedIn(scope: CoroutineScope) {
        BackgroundSyncWorker.schedule(context)
        refreshLiveUpdates()
        // A student switch goes through a sign-out, which cleared it already; the demo account's
        // sign-in may not, and a page left from before either must not show signed in to it.
        clearBrowserSoon(scope)
    }

    private fun onSignedOut(scope: CoroutineScope) {
        BackgroundSyncWorker.cancel(context)
        stopLiveUpdates()
        assignmentReminders.cancelAllTracked()
        // What the departing student was shown stays in the shade otherwise.
        AccountNotifications.cancelAll(context)
        // Moodle's session cookie, the pages and downloads list are the departing student's.
        clearBrowserSoon(scope)
        scope.launch { forgetMoodleColors() }
    }

    /**
     * Clears the browser apart from the watching, which a deletion taking seconds would otherwise
     * hold up. Clearings run one after another, in the order asked. Till the one for the latest
     * change is done, the browser does not open: the change marked it owed before it was made
     * ([BrowserCleanup]).
     */
    private fun clearBrowserSoon(scope: CoroutineScope) {
        scope.launch { clearBrowser() }
    }
}
