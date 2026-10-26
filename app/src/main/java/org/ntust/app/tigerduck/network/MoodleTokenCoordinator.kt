package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.PasswordPosting
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.auth.guardAutomaticHarvest

/**
 * When [MoodleTokenService] signs in to SSO for a token, and where it keeps it. Apart from the
 * service's SSO flow so a test can run it: [login] is that flow, a student ID and password in, a
 * wstoken out. It posts the password, and reads SSO's answer to it, through the
 * [PasswordPosting] it is handed.
 *
 * A renewal signs in with the stored password the user did not just type, so it runs under
 * [passwordLatch]: none at all while a refusal of that password waits, a check again right before
 * the password goes out under the lock every automatic post takes, and a refusal counted for the
 * whole app. The sign-in the user makes ([obtain]) posts what they typed.
 *
 * One sign-in at a time: the sign-in a user makes and a renewal both take [mutex]. A renewal goes
 * through [MoodleTokenRenewal], so it keeps its pause and its wait after a refused password, and
 * checks the stored token again once it holds the lock: one that waited behind a sign-in takes the
 * token that sign-in got rather than signing in a second time.
 */
internal class MoodleTokenCoordinator(
    private val tokens: Tokens,
    private val login: suspend (
        studentId: String,
        password: String,
        posting: PasswordPosting,
    ) -> String,
    scope: CoroutineScope,
    now: () -> Long,
    pauseMs: Long,
    private val passwordLatch: StoredPasswordLatch,
) {
    /** Where the token and the account it is harvested with are stored. */
    interface Tokens {
        var token: String?
        val studentId: String?
        val password: String?

        /**
         * Stores [token] only while [studentId] is still the stored account, as one step with a
         * sign-out's clear, so a sign-out can never land between the check and the store.
         */
        fun storeIfSignedIn(studentId: String, token: String): Boolean
    }

    private val mutex = Mutex()

    private val renewal = MoodleTokenRenewal(
        storedToken = { tokens.token },
        credentials = {
            val id = tokens.studentId
            val password = tokens.password
            if (id == null || password == null) null else id to password
        },
        harvest = { rejected ->
            mutex.withLock {
                val stored = tokens.token
                if (!stored.isNullOrEmpty() && stored != rejected) stored else harvestUnlocked()
            }
        },
        now = now,
        scope = scope,
        pauseMs = pauseMs,
        refusal = LatchedRefusal(passwordLatch),
    )

    /** A token in place of [rejected]; see [MoodleTokenRenewal]. */
    suspend fun renew(rejected: String?): String = renewal.renew(rejected)

    /**
     * A token with credentials the user just entered, which clears what held renewals back. It is
     * stored only while [studentId] is still the stored account, as one step with a sign-out's
     * clear: a sign-out during the sign-in leaves none of the departing student's behind.
     */
    suspend fun obtain(studentId: String, password: String): String = mutex.withLock {
        val normalized = studentId.trim().uppercase()
        login(normalized, password, PasswordPosting.Typed).also {
            renewal.reset()
            if (!tokens.storeIfSignedIn(normalized, it)) throw MoodleWebserviceError.MissingStoredCredentials()
        }
    }

    private suspend fun harvestUnlocked(): String {
        val sid = tokens.studentId ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val pwd = tokens.password ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val token = passwordLatch.guardAutomaticHarvest(sid, pwd) { posting ->
            login(sid.trim().uppercase(), pwd, posting)
        }
        // Signed out while it ran: the token is the departing account's, and stored it would
        // answer for whoever signs in next.
        if (!tokens.storeIfSignedIn(sid, token)) throw MoodleWebserviceError.MissingStoredCredentials()
        return token
    }
}

/**
 * [StoredPasswordRefusal] as the app-wide [StoredPasswordLatch], a count, so no fingerprint of the
 * credentials is ever kept. Refused while a refusal's wait holds. [guardAutomaticHarvest] counts
 * each, with the school's message and only for the password still stored; a post the school
 * takes and a sign-in by hand end the count.
 */
internal class LatchedRefusal(private val latch: StoredPasswordLatch) : StoredPasswordRefusal {
    override fun isRefused(fingerprint: String): Boolean = latch.postMustWait()
    override fun markRefused(fingerprint: String) = Unit
    override fun clear() = latch.clear()
}
