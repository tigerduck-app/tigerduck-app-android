package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.debug.DebugFixtureStore

/** The sessions whose school sign-in the bulletin list asks after; see [AccountSessions]. */
internal fun testSessions(context: Application) = AccountSessions(
    DebugFixtureStore(context),
    CoroutineScope(Dispatchers.Unconfined),
    SignOutOwed(context.getSharedPreferences("account-wipe-test", Context.MODE_PRIVATE)),
)
