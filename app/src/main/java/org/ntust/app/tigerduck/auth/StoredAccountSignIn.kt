package org.ntust.app.tigerduck.auth

import android.util.Log
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.util.logSummary
import java.net.HttpURLConnection
import kotlin.coroutines.cancellation.CancellationException

/**
 * The automatic backend sign-in with the stored account, which [AuthTokenManager] runs as
 * [AuthTokenManager.signInWithStoredAccount]. Apart from [AuthService] so a test can run it.
 *
 * [harvestMoodleToken] gets a fresh token with the stored account, in place of the one the
 * backend refused (null when none was stored), and stores it only while that account is still
 * signed in. It posts the stored password to NTUST's SSO, and posts none while a refusal of it
 * waits (StoredPasswordLatch). [afterSignIn] is not a suspending call: the sign-in can run
 * inside a request that PushRegistrationService makes while it holds the lock a registration
 * takes.
 */
internal class StoredAccountSignIn(
    private val tokens: AuthTokenManager,
    private val isDemo: () -> Boolean,
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val storedMoodleToken: () -> String?,
    private val harvestMoodleToken: suspend (refused: String?) -> String,
    private val afterSignIn: () -> Unit,
) {
    /**
     * Signs in with [signIn], the generation read before the credentials, so a sign-out during
     * the harvest keeps the login from storing the departing account's pair.
     *
     * The stored Moodle token goes first, as on the iPhone: it proves the account without the
     * password going anywhere. Harvesting a fresh one on every automatic sign-in posted the
     * password to SSO each time, and after a password change each of those counted toward the
     * school's lockout. A token is harvested only when none is stored or the backend refused it.
     */
    suspend fun signIn(signIn: Int): SignInAttempt {
        if (isDemo()) return SignInAttempt.NOTHING_TO_SEND
        val studentId = studentId() ?: return SignInAttempt.NOTHING_TO_SEND
        val password = password()
        val stored = storedMoodleToken()?.takeIf { it.isNotEmpty() }
        if (stored == null && password == null) return SignInAttempt.NOTHING_TO_SEND
        if (stored != null) {
            when (logIn(studentId, stored, signIn)) {
                Login.SIGNED_IN -> return SignInAttempt.SIGNED_IN
                Login.FAILED -> return SignInAttempt.FAILED
                // Moodle may have dropped it; a fresh one may do.
                Login.TOKEN_REFUSED -> if (password == null) return SignInAttempt.FAILED
            }
        }
        val fresh = try {
            harvestMoodleToken(stored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "auto sign-in: Moodle token harvest failed: ${e.logSummary()}")
            return SignInAttempt.FAILED
        }
        // The refused token back again would only be refused again, and each
        // login counts against the backend's limit.
        if (fresh.isEmpty() || fresh == stored) return SignInAttempt.FAILED
        val signedIn = logIn(studentId, fresh, signIn) == Login.SIGNED_IN
        return if (signedIn) SignInAttempt.SIGNED_IN else SignInAttempt.FAILED
    }

    private suspend fun logIn(studentId: String, moodleToken: String, signIn: Int): Login {
        // Signed out meanwhile: the token read since may be the next account's,
        // and must not go out with this one's ID.
        if (tokens.signInGeneration != signIn) return Login.FAILED
        return try {
            tokens.login(
                studentId = studentId,
                moodleToken = moodleToken,
                moodlePrivateToken = null,
                signInGeneration = signIn,
            )
            Log.i(TAG, "auto sign-in: v3 JWT obtained")
            afterSignIn()
            Login.SIGNED_IN
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthTokenManager.AuthException) {
            Log.w(TAG, "auto sign-in failed: ${e.logSummary()}")
            // The backend's answer when Moodle does not vouch for the token.
            val refused = e.status == HttpURLConnection.HTTP_UNAUTHORIZED
            if (refused) Login.TOKEN_REFUSED else Login.FAILED
        } catch (e: Exception) {
            Log.w(TAG, "auto sign-in failed: ${e.logSummary()}")
            Login.FAILED
        }
    }

    private enum class Login { SIGNED_IN, TOKEN_REFUSED, FAILED }

    private companion object {
        const val TAG = "AuthService"
    }
}
