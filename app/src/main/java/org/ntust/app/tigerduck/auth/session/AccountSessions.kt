package org.ntust.app.tigerduck.auth.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.shared.account.SignInSeries
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three accounts' sessions, one each for the life of the process. They are independent:
 * a school sign-out ends the school's and leaves the other two. One read crosses them, here and
 * nowhere else: mail counts as the demo while the school is ([AccountSession.isDemo]), since
 * [org.ntust.app.tigerduck.debug.DemoModeInterceptor] covers OkHttp, not IMAP or SMTP.
 *
 * Depends on nothing that depends on it, so the token manager can take it with no cycle.
 * Each series starts from its stored flag, [DebugFixtureStore.demoMode] and the mailbox's own
 * [MailStateStore.demoMailbox], which stay the persisted truth, so a demo started before this
 * process keeps being one.
 */
@Singleton
class AccountSessions @Inject constructor(
    fixtures: DebugFixtureStore,
    @ApplicationScope scope: CoroutineScope,
    owed: SignOutOwed,
    mailState: MailStateStore,
) {
    private val stream = accountEventStream()

    /** Every account's sign-ins and sign-outs, in order: see [AccountEvent]. */
    val events: SharedFlow<AccountEvent> = stream.asSharedFlow()

    // Closed from the start when the last sign-out was cut short; the start finishes it.
    val school = AccountSession(
        SignInSeries(closed = owed.owed, demo = fixtures.demoMode), scope, owed = owed, account = Account.SCHOOL, stream = stream,
    )
    val mail = AccountSession(
        SignInSeries(demo = mailState.demoMailbox), scope, alsoDemoWhile = school, account = Account.MAIL, stream = stream,
    )
    val library = AccountSession(SignInSeries(), scope, account = Account.LIBRARY, stream = stream)

    /** Tells every reactor, without waiting for any: safe under a lock, and from any thread. */
    fun emit(event: AccountEvent) {
        stream.tryEmit(event)
    }

    /**
     * The school account's state moved: it shows as signed in, or a sign-out given [reason] ended
     * the session's sign-in.
     */
    fun schoolChanged(signedIn: Boolean, reason: SignOutReason) = emit(
        if (signedIn) AccountEvent.SignedIn(Account.SCHOOL, byHand = false)
        else AccountEvent.SignedOut(Account.SCHOOL, reason, school.signIns.current, address = null),
    )

    /**
     * The school took a sign-in by hand, whoever it is: the same student's leaves the state signed
     * in throughout, and what follows an account, such as the browser's data, follows this too.
     */
    fun schoolTookSignIn() = emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = true))
}
