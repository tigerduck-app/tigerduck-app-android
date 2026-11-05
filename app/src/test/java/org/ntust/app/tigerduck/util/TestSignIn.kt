package org.ntust.app.tigerduck.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries

/** A sign-in that stands, for a test that is not about one. */
fun testSignIn(): SignIn = SignInSeries().current

/** A session of its own, for a test that only asks it whether the account is the demo. */
fun testSession(demo: Boolean = false) = AccountSession(SignInSeries(demo = demo), CoroutineScope(SupervisorJob()))
