package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * What a background run under [signIn] leaves for whoever shows its data: the Live Update, the
 * widgets, then the open screens, in that order, so each reads what the last left. Each can take
 * its time, and a sign-out in one of them stops the rest: what is on disk is no longer this run's,
 * and the sign-out's wipe updates the widgets itself, the same student's next sign-in included.
 */
internal suspend fun refreshAfterRun(
    signIn: SignIn,
    liveUpdate: suspend () -> Unit,
    widgets: suspend () -> Unit,
    screens: () -> Unit,
) {
    liveUpdate()
    if (!signIn.isCurrent) return
    widgets()
    if (signIn.isCurrent) screens()
}
