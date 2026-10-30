@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister.Outcome
import java.io.IOException

/**
 * The sign-out's device unregister, which goes out as the departing account and is kept until the
 * server has it: signed out offline, or with an access token that has expired, the device stayed
 * registered to the account and went on getting its reminders.
 */
class DepartingDeviceUnregisterTest {

    private var now = 1_800_000_000_000L

    private class MemoryStore : PendingUnregisterStore {
        var pending: PendingUnregister? = null

        /**
         * A disk that refuses every write from now on. As SharedPreferences does, the edit still
         * lands in memory, and only the commit reports the failure.
         */
        var refuseSaves = false

        override fun load() = pending
        override fun save(pending: PendingUnregister): Boolean {
            this.pending = pending
            return !refuseSaves
        }
        override fun clear() {
            pending = null
        }
    }

    private val store = MemoryStore()
    private val owed = OwedUnregister(store)

    /** Holds the next DELETE until the test completes it with a status. */
    private var heldDelete: CompletableDeferred<Int>? = null

    /** Each DELETE as "device token". */
    private val deletes = mutableListOf<String>()
    private val deleteReplies = ArrayDeque<Any>()

    /** The refresh tokens sent. */
    private val refreshes = mutableListOf<String>()
    private val refreshReplies = ArrayDeque<DepartingRefresh>()
    private var signedInSince = false

    /** The backend the session [signedInSince] stands for is from. */
    private var sessionBackend = BACKEND

    /** The backend the debt is owed to, which its session came from. */
    private var backend = BACKEND

    /** The backend the API endpoint setting names. */
    private var endpoint = BACKEND

    /** Holds each refresh's reply until the test completes it. */
    private var refreshGate: CompletableDeferred<Unit>? = null

    /** Thrown by each refresh, as a reply that cannot be read would be. */
    private var refreshThrows: Exception? = null

    /** Runs as each refresh is sent, before its reply. */
    private var onRefresh: () -> Unit = {}

    private fun unregister() = DepartingDeviceUnregister(
        owed = owed,
        deleteDevice = { base, device, token ->
            assertEquals("the DELETE goes to the backend that issued the token", backend, base)
            deletes += "$device $token"
            heldDelete?.let { held -> heldDelete = null; return@DepartingDeviceUnregister held.await() }
            when (val reply = deleteReplies.removeFirstOrNull() ?: 204) {
                is Exception -> throw reply
                else -> reply as Int
            }
        },
        refresh = { session, sendsWithoutReply ->
            assertEquals("the refresh goes to the backend that issued the token", backend, session.baseUrl)
            // As AuthTokenManager.refreshDeparting: a token gone twice without a reply is not sent.
            if (sendsWithoutReply >= 2) return@DepartingDeviceUnregister DepartingRefresh.Refused
            refreshes += session.refreshToken
            onRefresh()
            refreshGate?.await()
            refreshThrows?.let { throw it }
            refreshReplies.removeFirstOrNull() ?: DepartingRefresh.Refused
        },
        sessionAt = { base -> signedInSince && base == sessionBackend },
        now = { now },
        endpoint = { endpoint },
    )

    private fun session(access: String, refresh: String, expiresAt: Long) =
        DepartingSession(access, refresh, expiresAt, backend, STUDENT)

    private fun refreshed(access: String, refresh: String) =
        DepartingRefresh.Refreshed(session(access, refresh, now + 900_000))

    private suspend fun owe(expiresAt: Long = now + 600_000) =
        unregister().owe("device-1", session("access-1", "refresh-1", expiresAt))

    @Test
    fun `a token that lasts goes out as it is, and the debt ends with the reply`() = runTest {
        owe()

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1"), deletes)
        assertEquals(emptyList<String>(), refreshes)
        assertNull(store.pending)
    }

    @Test
    fun `an expired token is refreshed first, and the rotated pair is kept`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += refreshed("access-2", "refresh-2")
        deleteReplies += 503

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(listOf("refresh-1"), refreshes)
        assertEquals(listOf("device-1 access-2"), deletes)
        // The retry sends the rotated refresh token, never the one the server replaced.
        assertEquals("refresh-2", store.pending?.session?.refreshToken)
    }

    @Test
    fun `signed out offline, it is kept and goes out on a later try`() = runTest {
        owe()
        deleteReplies += IOException("offline")

        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals("device-1", store.pending?.deviceId)

        assertEquals(Outcome.DONE, unregister().send())
        assertEquals(listOf("device-1 access-1", "device-1 access-1"), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a busy server keeps it, a device it no longer has ends it`() = runTest {
        owe()
        deleteReplies += 429
        assertEquals(Outcome.RETRY, unregister().send())

        deleteReplies += 404
        assertEquals(Outcome.DONE, unregister().send())
        assertNull(store.pending)
    }

    @Test
    fun `a token turned down before its time is refreshed once and sent again`() = runTest {
        owe()
        deleteReplies += 401
        refreshReplies += refreshed("access-2", "refresh-2")

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1", "device-1 access-2"), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a refresh the server turns down ends it, as nothing can go out as the account any more`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.Refused

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a refresh that could not be sent keeps it, uncounted`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.Unused

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertEquals("refresh-1", store.pending?.session?.refreshToken)
        // The backend did not use the token: the send is not held against it.
        assertEquals(0, store.pending?.unansweredRefreshes)
    }

    @Test
    fun `a sign-in since ends it unsent`() = runTest {
        // Sent as the same account, it would delete the new session's device and revoke it.
        owe()
        signedInSince = true

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `nothing owed sends nothing`() = runTest {
        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
    }

    // The API endpoint setting, which the next person on the phone can change. The tokens go to
    // the backend that issued them whatever it names, as the iPhone's do (tigerduck-app#745): a
    // debt dropped there leaves the device registered to the account at that backend.

    @Test
    fun `a session from another backend leaves the debt to this one`() = runTest {
        // Signed in at the endpoint set since: that moved nothing at the backend owed.
        owe()
        signedInSince = true
        sessionBackend = ELSEWHERE

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1"), deletes)
    }

    @Test
    fun `a sign-in at another backend leaves the debt, and does not wait for one on its way`() = runTest {
        owe()
        val held = CompletableDeferred<Int>().also { heldDelete = it }
        val sending = async { unregister().send() }
        runCurrent()

        assertEquals("login", owed.signingIn(STUDENT, ELSEWHERE) { "login" })

        assertEquals("device-1", store.pending?.deviceId)
        held.complete(204)
        assertEquals(Outcome.DONE, sending.await())
        assertNull(store.pending)
    }

    // A sign-in of the same account while the unregister is on its way

    @Test
    fun `a sign-in waits for an unregister on its way, then ends the debt`() = runTest {
        owe()
        val held = CompletableDeferred<Int>().also { heldDelete = it }
        val events = mutableListOf<String>()
        val sending = async { unregister().send().also { events += "unregister $it" } }
        runCurrent()
        val signingIn = async { owed.signingIn(STUDENT, BACKEND) { events += "login" } }
        runCurrent()
        assertEquals("the login waits for the DELETE", emptyList<String>(), events)

        held.complete(503)
        sending.await()
        signingIn.await()

        assertEquals(listOf("unregister RETRY", "login"), events)
        assertNull("signed in, the device is the new session's", store.pending)
    }

    @Test
    fun `another student's sign-in does not wait for the unregister on its way, and ends the debt`() = runTest {
        // Logging in, the backend deletes this device's rows under every other account itself.
        owe()
        val held = CompletableDeferred<Int>().also { heldDelete = it }
        val sending = async { unregister().send() }
        runCurrent()

        owed.signingIn("B11300002", BACKEND) { }

        assertNull(store.pending)
        held.complete(503)
        sending.await()
        assertNull("the unregister on its way does not bring the debt back", store.pending)
    }

    @Test
    fun `a debt from before the student was recorded makes any sign-in wait`() = runTest {
        unregister().owe("device-1", DepartingSession("access-1", "refresh-1", now + 600_000, BACKEND, studentId = null))
        val held = CompletableDeferred<Int>().also { heldDelete = it }
        val sending = async { unregister().send() }
        runCurrent()
        val signingIn = async { owed.signingIn("B11300002", BACKEND) { "login" } }
        runCurrent()
        assertFalse(signingIn.isCompleted)

        held.complete(204)
        sending.await()
        assertEquals("login", signingIn.await())
    }

    @Test
    fun `a sign-in that fails leaves the debt`() = runTest {
        owe()

        runCatching { owed.signingIn(STUDENT, BACKEND) { throw IllegalStateException("login refused") } }

        assertEquals("device-1", store.pending?.deviceId)
    }

    @Test
    fun `a session that appears while the token is refreshed stops the DELETE`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += refreshed("access-2", "refresh-2")
        onRefresh = { signedInSince = true }

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    // What cannot be sent, or is never sent, is not kept for good

    @Test
    fun `a reply that cannot be read keeps the debt for a later try`() = runTest {
        owe(expiresAt = now - 1)
        refreshThrows = JSONException("no access_token")
        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals("device-1", store.pending?.deviceId)

        refreshThrows = null
        refreshReplies += refreshed("access-2", "refresh-2")
        deleteReplies += IllegalArgumentException("unexpected url")
        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals("device-1", store.pending?.deviceId)
    }

    @Test
    fun `an unregister owed for longer than a refresh token lives is dropped unsent`() = runTest {
        owe()
        now += 90L * 24 * 60 * 60 * 1000
        refreshReplies += refreshed("access-2", "refresh-2")

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), refreshes)
        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a stop while the token is refreshed still keeps the pair the server rotated to`() = runTest {
        // WorkManager stops a worker by cancelling it; the refresh it was waiting on may already
        // have rotated the token, and the replaced one would be taken for reuse if sent again.
        owe(expiresAt = now - 1)
        refreshReplies += refreshed("access-2", "refresh-2")
        val gate = CompletableDeferred<Unit>().also { refreshGate = it }
        val sending = launch { unregister().send() }
        runCurrent()

        sending.cancel()
        gate.complete(Unit)
        sending.join()

        assertEquals("refresh-2", store.pending?.session?.refreshToken)
        assertEquals("stopped, it sends nothing more", emptyList<String>(), deletes)
    }

    @Test
    fun `a lost reply is sent once more, and a second is given up`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.NoReply
        refreshReplies += DepartingRefresh.NoReply
        refreshReplies += refreshed("access-2", "refresh-2")

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("refresh-1", "refresh-1"), refreshes)
        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a send is counted before it goes, so a process ended during it never leads to a third`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.NoReply
        // The resend never finishes in this process.
        onRefresh = { if (refreshes.size == 2) throw IllegalStateException("process ended") }
        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals(2, store.pending?.unansweredRefreshes)

        // The next launch.
        onRefresh = {}
        refreshReplies += refreshed("access-2", "refresh-2")
        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("refresh-1", "refresh-1"), refreshes)
        assertEquals(emptyList<String>(), deletes)
    }

    @Test
    fun `a refresh answered with a 5xx stays counted, as the backend may have rotated before a gateway answered`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.Unavailable
        refreshReplies += DepartingRefresh.Unavailable

        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals(1, store.pending?.unansweredRefreshes)
        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals(2, store.pending?.unansweredRefreshes)

        // Counted twice, the token is not sent a third time: the debt ends unsent.
        assertEquals(Outcome.DONE, unregister().send())
        assertEquals(listOf("refresh-1", "refresh-1"), refreshes)
        assertNull(store.pending)
    }

    @Test
    fun `a send whose count the disk does not take is not made`() = runTest {
        // Sent anyway, a process ended during it would come back with the count before it, and a
        // later launch could make the token's third send.
        owe(expiresAt = now - 1)
        store.refuseSaves = true
        refreshReplies += refreshed("access-2", "refresh-2")

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(emptyList<String>(), refreshes)
        assertEquals(emptyList<String>(), deletes)
        assertEquals(0, store.pending?.unansweredRefreshes)
    }

    @Test
    fun `counts the disk refused are not taken for sends once it takes writes again`() = runTest {
        // SharedPreferences keeps a refused edit in memory: left there, two refused tries would
        // read as two sends made, and the next try would give up a debt still owed.
        owe(expiresAt = now - 1)
        store.refuseSaves = true
        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals(emptyList<String>(), refreshes)

        store.refuseSaves = false
        refreshReplies += refreshed("access-2", "refresh-2")
        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("refresh-1"), refreshes)
        assertEquals(listOf("device-1 access-2"), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `an answer that cannot be read counts as one that never came`() = runTest {
        owe(expiresAt = now - 1)
        refreshThrows = JSONException("no access_token")

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(1, store.pending?.unansweredRefreshes)
    }

    // A self-hosted http backend (#593)

    /**
     * The endpoint setting takes plain http to a private address. Once it names another backend,
     * that address may be another network's host: the departing refresh token would go to it in
     * cleartext. So nothing is sent, and the debt waits, as on the iPhone (tigerduck-app#908).
     */
    @Test
    fun `a debt to an http backend no longer in use sends nothing, and waits`() = runTest {
        backend = LAN
        endpoint = BACKEND
        owe(expiresAt = now - 1)

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(emptyList<String>(), refreshes)
        assertEquals(emptyList<String>(), deletes)
        assertEquals("refresh-1", store.pending?.session?.refreshToken)
    }

    /** The only way that backend learns the device left: switched back to, it goes out. */
    @Test
    fun `a debt to an http backend goes out once the setting names it again`() = runTest {
        backend = LAN
        endpoint = BACKEND
        owe()
        assertEquals(Outcome.RETRY, unregister().send())

        endpoint = "http://192.168.1.10:8000/v3/"
        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1"), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a debt to an http backend still past its lifetime is dropped`() = runTest {
        backend = LAN
        endpoint = BACKEND
        owe()

        now += 90L * 24 * 60 * 60 * 1000
        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    /** TLS keeps the token to the host that issued it, wherever the phone is. */
    @Test
    fun `a debt to an https backend no longer in use still goes out to it`() = runTest {
        endpoint = ELSEWHERE
        owe()

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1"), deletes)
    }

    @Test
    fun `giving up drops the debt`() = runTest {
        owe()

        unregister().abandon()

        assertNull(store.pending)
    }

    private companion object {
        const val STUDENT = "B11300001"
        const val BACKEND = "https://backend.example/v3"
        const val ELSEWHERE = "https://elsewhere.example/v3"
        const val LAN = "http://192.168.1.10:8000/v3"
    }
}
