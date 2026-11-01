package org.ntust.app.tigerduck.util

import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries

/** A sign-in that stands, for a test that is not about one. */
fun testSignIn(): SignIn = SignInSeries().current
