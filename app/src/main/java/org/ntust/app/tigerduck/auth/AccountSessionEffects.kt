package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserDataCleaner
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
 * Follows [AuthService.accountState] rather than the pages, and only its changes: the state the
 * process starts in is not one. MainActivity schedules the background sync for an account
 * already signed in at launch. Never waits on anything slow, the browser's clearing least of
 * all, and counts the changes rather than reading the value, so no sign-out goes unseen.
 */
@Singleton
class AccountSessionEffects internal constructor(
    private val context: Context,
    private val accountState: StateFlow<AccountState>,
    /** Each sign-in by hand, a student signing in over another included; see [AuthService.signIns]. */
    private val signIns: Flow<Unit>,
    private val assignmentReminders: AssignmentNotificationScheduler,
    private val refreshLiveUpdates: () -> Unit,
    private val stopLiveUpdates: () -> Unit,
    /** Clears the TigerDuck browser: the account it was signed in to is not the one now. */
    private val clearBrowser: suspend () -> Unit,
    /** Whether a clearing a process death cut short is still owed; see [BrowserCleanup]. */
    private val browserClearingOwed: () -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authService: AuthService,
        assignmentReminders: AssignmentNotificationScheduler,
        liveActivityManager: LiveActivityManager,
        browserData: BrowserDataCleaner,
        browserCleanup: BrowserCleanup,
    ) : this(
        context = context,
        accountState = authService.accountState,
        signIns = authService.signIns,
        assignmentReminders = assignmentReminders,
        refreshLiveUpdates = { liveActivityManager.refresh() },
        stopLiveUpdates = liveActivityManager::stop,
        clearBrowser = browserData::clear,
        browserClearingOwed = browserCleanup::isPending,
    )

    /** Follows the sign-in state on [scope] for the life of the process. */
    fun start(scope: CoroutineScope) {
        // Where it starts is read now, not when the watcher first runs: a sign-out in between is a change.
        var seen = accountState.value
        scope.launch {
            accountState.collect { now ->
                val before = seen
                seen = now
                if (now.changes == before.changes) return@collect
                // Seen late, a sign-out and a sign-in in between are two changes to the same value.
                if (before.signedIn || now.changes - before.changes > 1) onSignedOut(scope)
                if (now.signedIn) onSignedIn(scope)
            }
        }
        // A student signing in over another never turns authState off and on.
        scope.launch { signIns.collect { clearBrowserSoon(scope) } }
        // One a process death cut short, or one whose deletions did not all report, goes again.
        if (browserClearingOwed()) clearBrowserSoon(scope)
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
