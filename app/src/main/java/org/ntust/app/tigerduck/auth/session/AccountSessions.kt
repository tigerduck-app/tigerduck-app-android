package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val stream = accountEventStream()

    /** Every account's sign-ins and sign-outs, in order: see [AccountEvent]. */
    val events: SharedFlow<AccountEvent> = stream.asSharedFlow()

    // Closed from the start when the last sign-out was cut short; the start finishes it.
    val school = AccountSession(
        SignInSeries(closed = owed.owed, demo = fixtures.demoMode), scope, { fixtures.demoMode }, owed, Account.SCHOOL, stream,
    )
    val mail = AccountSession(SignInSeries(), scope, account = Account.MAIL, stream = stream)
    val library = AccountSession(SignInSeries(), scope, account = Account.LIBRARY, stream = stream)

    /** Tells every reactor, without waiting for any: safe under a lock, and from any thread. */
    fun emit(event: AccountEvent) {
        stream.tryEmit(event)
    }

    /** The school account's state moved: it shows as signed in, or a sign-out ended the session's sign-in. */
    fun schoolChanged(signedIn: Boolean) = emit(
        if (signedIn) AccountEvent.SignedIn(Account.SCHOOL, byHand = false)
        else AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, school.signIns.current, address = null),
    )

    /**
     * The school took a sign-in by hand, whoever it is: the same student's leaves the state signed
     * in throughout, and what follows an account, such as the browser's data, follows this too.
     */
    fun schoolTookSignIn() = emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = true))
}
