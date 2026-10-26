package org.ntust.app.tigerduck.auth

import android.util.Log
import org.ntust.app.tigerduck.util.logSummary
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Moodle token the sign-in the user makes sends to the backend: the one [harvest] gets, or,
 * when that fails, the token [stored] from before, when it is the same student's ([sameStudent]).
 * Never another account's, and none once [stillCurrent] says the account signed out meanwhile.
 */
internal suspend fun interactiveMoodleToken(
    harvest: suspend () -> String,
    stored: () -> String?,
    sameStudent: Boolean,
    stillCurrent: () -> Boolean,
): String? = try {
    harvest()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("AuthService", "Moodle token harvest failed: ${e.logSummary()}")
    if (sameStudent && stillCurrent()) stored() else null
}
