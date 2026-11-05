package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A request that needs a session got no access token, so it was not sent. */
class NotSignedInException : Exception("not authenticated")

/** Keeps the old backend's unregister before its pair is dropped, without ending the school sign-in. */
internal class BackendSessionMove(
    private val owed: OwedUnregister?,
    private val deviceId: String,
    private val now: () -> Long,
    private val enqueue: () -> Unit,
) {
    /**
     * Runs [move] under the unregister's lock, giving it the write to make before dropping a pair.
     * The caller takes the token manager's lock inside this one, as an unregister and a login do.
     * A refused write leaves the pair for another try; cancellation cannot split the handoff.
     */
    suspend fun keepingDeparture(move: (keep: (DepartingSession) -> Boolean) -> Boolean): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val owed = owed ?: return@withContext move { true }
            var saved = false
            val kept = owed.mutex.withLock {
                move { departing ->
                    owed.store.save(PendingUnregister(deviceId, departing, now(), departing.sendsWithoutReply))
                        .also { saved = it }
                }
            }
            // Before a login at the new backend, so a process ended during it still has a retry.
            if (saved) enqueue()
            kept
        }
}

/**
 * The pair, by its refresh token, that an automatic sign-in after a refusal is to replace: a
 * login that finds another stored at its turn sends nothing, and the sign-in counts as done.
 * The coroutine context carries it through AuthService's sign-in to AuthTokenManager.login.
 */
internal class Replacing(val refreshToken: String) : AbstractCoroutineContextElement(Replacing) {
    companion object Key : CoroutineContext.Key<Replacing>
}

/** The same snapshot for a sign-out and a backend move, read under the token manager's lock. */
internal fun V3TokenStore.departingSession(issuer: String, unanswered: Int): DepartingSession? {
    val access = accessToken ?: return null
    val refresh = refreshToken ?: return null
    // Every send that may have reached the backend goes with the debt, including one still on
    // its way. Even an unused reply stays counted after departure: the pair is gone from disk,
    // and a departing refresh must never make the token's third send.
    return DepartingSession(access, refresh, expiresAt, issuer, studentId, maxOf(unanswered, refreshTokenSends))
}
