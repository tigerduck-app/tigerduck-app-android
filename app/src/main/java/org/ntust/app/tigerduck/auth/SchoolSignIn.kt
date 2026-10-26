package org.ntust.app.tigerduck.auth

/**
 * The stored account's SSO sign-in to the school, apart from [AuthService] so a test can run it;
 * [AuthService] holds its lock around each call. It signs nothing in to the library or the school
 * mail, which are signed in to by themselves (decided for Android on 2026-10-22).
 */
internal class SchoolSignIn(
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val sessionIsWarm: () -> Boolean,
    /** The SSO step; see [org.ntust.app.tigerduck.network.SsoLoginService.ensureServiceLogin]. */
    private val signInToSchool: suspend (studentId: String, password: String) -> Boolean,
    /** The SSO step past a warm session; see [org.ntust.app.tigerduck.network.SsoLoginService.signInAgain]. */
    private val signInAgainToSchool: suspend (
        studentId: String,
        password: String,
        accepted: () -> Unit,
        stillRefused: () -> Boolean,
    ) -> Boolean,
) {
    /**
     * The stored account again: false without one, and true at once while the
     * session is still warm. Throws what reading the stored account or the SSO
     * step throws.
     */
    suspend fun stored(): Boolean {
        val studentId = studentId() ?: return false
        val password = password() ?: return false
        if (sessionIsWarm()) return true
        return signInToSchool(studentId.trim().uppercase(), password)
    }

    /**
     * The stored account posted to the school whatever the session: the retry of a refused
     * password, which a warm session says nothing about, as only the school's answer to a post
     * does. False without one. True without a post once [stillRefused] says the refusal is gone,
     * as a sign-in by hand the retry waited behind settles it. [accepted] hears of the school
     * taking the password posted.
     */
    suspend fun storedAgain(accepted: () -> Unit = {}, stillRefused: () -> Boolean): Boolean {
        if (!stillRefused()) return true
        val studentId = studentId() ?: return false
        val password = password() ?: return false
        return signInAgainToSchool(studentId.trim().uppercase(), password, accepted, stillRefused)
    }
}
