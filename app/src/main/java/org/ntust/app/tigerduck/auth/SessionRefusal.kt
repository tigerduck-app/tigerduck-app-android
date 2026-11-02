package org.ntust.app.tigerduck.auth

import org.ntust.app.tigerduck.shared.account.SignIn
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether the backend answered with a 401, or said the session was revoked: the session it ended
 * before its access token ran out keeps drawing these until the app signs in again.
 */
internal fun Throwable.isSessionRefused(): Boolean =
    message?.contains("401") == true || message?.contains("session_revoked") == true

/**
 * Runs [request], and once more after [relogin] signs in again when the backend refused the
 * session ([isSessionRefused]). Throws what the last run threw, and what a refused session threw
 * when the sign-in failed. [request] is sent under [signIn] both times, as the retry is the same
 * request: after a sign-out it refuses, rather than going out as the next account.
 */
internal suspend fun <T> retryingAfterRelogin(
    signIn: SignIn,
    relogin: suspend (SignIn) -> Boolean,
    request: suspend (SignIn) -> T,
): T =
    try {
        request(signIn)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!e.isSessionRefused() || !relogin(signIn)) throw e
        request(signIn)
    }
