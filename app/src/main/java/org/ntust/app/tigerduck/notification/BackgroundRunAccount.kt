package org.ntust.app.tigerduck.notification

/**
 * The account a background sync began under, which the run asks after each phase and before each
 * stamp whether it still serves. The student ID alone is not the account: a sign-out and a
 * sign-in as the same student pass it, and the run would then stamp the new session as freshly
 * synced and refresh over its files. The sign-in generation ([signIn], `AuthTokenManager.signInGeneration`)
 * moves on every sign-out and sign-in, so the run is over when either differs.
 *
 * Both are read from the current session by [currentStudentId] and [currentSignIn], so a test
 * can move them without building the worker.
 */
internal class BackgroundRunAccount(
    private val studentId: String?,
    private val signIn: Int,
    private val currentStudentId: () -> String?,
    private val currentSignIn: () -> Int,
) {
    /** Whether the account signed out, or signed out and in again, since the run began. */
    fun ended(): Boolean = currentStudentId() != studentId || currentSignIn() != signIn
}
