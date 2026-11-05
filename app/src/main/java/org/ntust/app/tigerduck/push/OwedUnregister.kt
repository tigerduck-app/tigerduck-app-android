package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.backend.ApiBase
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device unregister a sign-out owes, and the lock everything that sends it or ends it takes:
 * [DepartingDeviceUnregister] while it refreshes and sends, and a backend sign-in
 * ([org.ntust.app.tigerduck.auth.AuthTokenManager.login]) while its login is on the way.
 */
@Singleton
class OwedUnregister internal constructor(internal val store: PendingUnregisterStore) {
    @Inject
    constructor(credentials: CredentialManager) : this(credentials.pendingUnregister)

    internal val mutex = Mutex()

    /**
     * Runs a backend sign-in of [studentId]'s [login], and ends the debt once it signed in.
     * Logging in moves the device to the account that signed in: the server deletes the device's
     * rows under every other account, and takes up its row again for the same one, which an
     * unregister sent after would delete, revoking the new session. So the same student's login
     * runs under the lock, with no unregister on its way while it is. Another student's has
     * nothing to wait for: the server ends the departing account's row itself, and an unregister
     * still on its way finds it gone. A debt from before the student was recorded is taken for
     * the same student's. A login that fails leaves the debt: the server moved nothing, as far as
     * this phone can tell. A login at another backend than the debt's, [base] being the login's,
     * moves nothing there: the debt stands, and the login does not wait for it. A login at the
     * debt's backend under another API version is at that backend ([ApiBase.sameBackend]).
     */
    suspend fun <T> signingIn(studentId: String, base: String, login: suspend () -> T): T {
        val debts = runCatching { store.load() }.getOrNull()
        val pending = debts?.firstOrNull { ApiBase.sameBackend(it.session.baseUrl, base) }
        if (debts?.isNotEmpty() == true && pending == null) return login()
        val owedBy = pending?.session?.studentId
        if (owedBy != null && owedBy != studentId) {
            val result = login()
            // Not under the lock: an unregister on its way only finds the row gone.
            clear(base)
            return result
        }
        return mutex.withLock {
            val result = login()
            clear(base)
            result
        }
    }

    private fun clear(base: String) {
        runCatching { store.clear(base) }
            .onFailure { Log.w(TAG, "owed unregister not cleared after a sign-in: ${it.logSummary()}") }
    }

    private companion object {
        const val TAG = "OwedUnregister"
    }
}
