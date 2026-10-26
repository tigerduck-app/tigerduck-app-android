package org.ntust.app.tigerduck.auth

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
 * when the sign-in failed.
 */
internal suspend fun <T> retryingAfterRelogin(relogin: suspend () -> Boolean, request: suspend () -> T): T =
    try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!e.isSessionRefused() || !relogin()) throw e
        request()
    }
