package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CoroutineScope
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.shared.account.SignInSeries
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three accounts' sessions, one each for the life of the process. They are independent:
 * a school sign-out ends the school's and leaves the other two.
 *
 * Depends on nothing that depends on it, so the token manager can take it with no cycle.
 * The school's session answers [AccountSession.isDemo] from the demo's stored flag as today; the
 * mail and the library sessions answer false until the demo moves into the sessions.
 */
@Singleton
class AccountSessions @Inject constructor(
    fixtures: DebugFixtureStore,
    @ApplicationScope scope: CoroutineScope,
    owed: SignOutOwed,
) {
    // Closed from the start when the last sign-out was cut short; the start finishes it.
    val school = AccountSession(
        SignInSeries(closed = owed.owed, demo = fixtures.demoMode), scope, { fixtures.demoMode }, owed,
    )
    val mail = AccountSession(SignInSeries(), scope)
    val library = AccountSession(SignInSeries(), scope)
}
