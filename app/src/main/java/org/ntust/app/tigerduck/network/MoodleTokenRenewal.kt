package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * When the Moodle token is renewed, which posts the stored password to NTUST's SSO. Every Moodle
 * call that finds the token dead asks, often several at once (a course detail refreshes four
 * kinds of data side by side), so this keeps it, as iOS's `refreshTokenIfNeeded` does, to:
 * - one sign-in at a time, whose answer, a failure included, every caller waiting shares;
 * - none for [pauseMs] after one that failed for a reason other than the network, whose error
 *   is handed back instead;
 * - none at all with credentials SSO refused, until they change. Retrying a wrong password is
 *   what locks an NTUST account.
 *
 * [harvest] signs in and stores the token it gets. It runs in [scope], so a caller that goes away
 * does not cancel a sign-in others wait on. Which credentials SSO refused is kept in [refusal].
 */
internal class MoodleTokenRenewal(
    storedToken: () -> String?,
    /** The stored student ID and password, or null with none stored. */
    credentials: () -> Pair<String, String>?,
    /** Signs in for a token in place of the rejected one it is handed. */
    harvest: suspend (rejected: String?) -> String,
    now: () -> Long,
    scope: CoroutineScope,
    pauseMs: Long,
    refusal: StoredPasswordRefusal = StoredPasswordRefusal.InMemory(),
) {
    private val gate = MoodleSignInGate(
        kept = { storedToken()?.takeIf { it.isNotEmpty() } },
        credentials = credentials,
        signIn = harvest,
        now = now,
        scope = scope,
        pauseMs = pauseMs,
        refusal = refusal,
    )

    /** A token in place of [rejected], which is null when none was stored. */
    suspend fun renew(rejected: String?): String = gate.take(rejected)

    /** Forgets what failed, for a sign-in the user made by hand. */
    suspend fun reset() = gate.reset()
}

/**
 * The rules every automatic SSO sign-in with the stored password keeps, whatever it signs in for
 * (a Moodle token, [MoodleTokenRenewal]):
 * - one at a time, whose answer, a failure included, every caller waiting shares;
 * - none for [pauseMs] after one that failed for a reason other than the network, whose error
 *   is handed back instead;
 * - none at all with credentials SSO refused, until they change.
 *
 * [kept] is what an earlier sign-in left that a caller may take instead of signing in, unless it
 * is the one the caller was refused ([take]'s `rejected`); null for nothing. [signIn] runs in
 * [scope], so a caller that goes away does not cancel a sign-in others wait on.
 */
internal class MoodleSignInGate<T : Any>(
    private val kept: () -> T?,
    private val credentials: () -> Pair<String, String>?,
    private val signIn: suspend (rejected: T?) -> T,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val pauseMs: Long,
    private val refusal: StoredPasswordRefusal,
) {
    private val lock = Mutex()
    /**
     * The sign-in under way. It completes with its failure as a value rather than failing, so it
     * never cancels the scope it runs in, whatever kind of scope that is.
     */
    private var inFlight: Deferred<Result<T>>? = null

    /** The last failure that was not the network's, with when and for which credentials. */
    private var lastFailure: Failure? = null

    private class Failure(val atMs: Long, val error: Throwable, val credentials: String)

    /** What [kept] holds unless it is [rejected], else a sign-in's answer. */
    suspend fun take(rejected: T?): T {
        val pending = lock.withLock {
            inFlight?.takeIf { it.isActive } ?: run {
                val stored = kept()
                if (stored != null && stored != rejected) return stored
                val key = credentials()?.let(::fingerprint)
                    ?: throw MoodleWebserviceError.MissingStoredCredentials()
                if (refusal.isRefused(key)) throw MoodleWebserviceError.SsoLoginRejected(null)
                lastFailure?.let { failure ->
                    // Measured from the failure, so a clock set back does not stretch the pause.
                    val age = now() - failure.atMs
                    if (failure.credentials == key && age in 0 until pauseMs) throw failure.error
                }
                scope.async {
                    try {
                        Result.success(signInFor(key, rejected))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }.also { inFlight = it }
            }
        }
        return pending.await().getOrThrow()
    }

    /** Forgets what failed, for a sign-in the user made by hand. */
    suspend fun reset() = lock.withLock {
        lastFailure = null
        refusal.clear()
    }

    private suspend fun signInFor(key: String, rejected: T?): T = try {
        signIn(rejected).also {
            lock.withLock { lastFailure = null }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MoodleWebserviceError.SsoLoginRejected) {
        // A sign-in with a new password can finish while this one runs on the old.
        lock.withLock { if (credentials()?.let(::fingerprint) == key) refusal.markRefused(key) }
        throw e
    } catch (e: Exception) {
        // Offline the sign-in mostly stops before the password goes out, and a pause would
        // outlast the network coming back.
        if (!MoodleWebserviceError.isNetwork(e)) {
            lock.withLock {
                if (credentials()?.let(::fingerprint) == key) lastFailure = Failure(now(), e, key)
            }
        }
        throw e
    }

    private fun fingerprint(credentials: Pair<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${credentials.first.trim().uppercase()}\u0000${credentials.second}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Which stored credentials NTUST's SSO refused, so they are not sent again until they change.
 * Each is named by a fingerprint of the student ID and password, never the password itself.
 *
 * The fingerprint is an unsalted SHA-256, fit only to compare in memory, and never written
 * anywhere. The app keeps the refusal in the app-wide StoredPasswordLatch, a count
 * ([LatchedRefusal]); [InMemory] is for tests.
 */
internal interface StoredPasswordRefusal {
    fun isRefused(fingerprint: String): Boolean
    fun markRefused(fingerprint: String)
    /** For a sign-in the user made by hand. */
    fun clear()

    /** Kept for as long as the process lives. */
    class InMemory : StoredPasswordRefusal {
        @Volatile private var refused: String? = null
        override fun isRefused(fingerprint: String) = refused == fingerprint
        override fun markRefused(fingerprint: String) {
            refused = fingerprint
        }
        override fun clear() {
            refused = null
        }
    }
}
