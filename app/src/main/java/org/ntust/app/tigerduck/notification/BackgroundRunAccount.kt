package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * The account a background sync began under, which the run asks after each phase and before each
 * stamp whether it still serves. The student ID alone is not the account: a sign-out and a
 * sign-in as the same student pass it, and the run would then stamp the new session as freshly
 * synced and refresh over its files. [signIn] ends at the sign-out, so the run is over when
 * either differs.
 *
 * The student is read through [currentStudentId], so a test can move it without building the
 * worker.
 */
internal class BackgroundRunAccount(
    private val studentId: String?,
    private val signIn: SignIn,
    private val currentStudentId: () -> String?,
) {
    /** Whether the account signed out, or signed out and in again, since the run began. */
    fun ended(): Boolean = currentStudentId() != studentId || !signIn.isCurrent

    /**
     * Runs [write] if the run still serves its account, in a step a sign-out waits for, so the
     * sign-out's clearing cannot fall between the check and the write: what [write] stores lands
     * before it, or not at all. For a short write only. Says whether it ran.
     */
    fun writeIfCurrent(write: () -> Unit): Boolean = !ended() && signIn.ifCurrent(write) != null
}
