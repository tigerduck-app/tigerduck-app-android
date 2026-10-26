package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renewing the Moodle token posts the stored password to NTUST's SSO. Every Moodle call that
 * finds the token dead asks for one, several at a time, so these keep it to one sign-in at a
 * time, none for a while after one that failed, and none at all with a password SSO refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleTokenRenewalTest {

    private var stored: String? = "old"
    private var credentials: Pair<String, String>? = "B11300001" to "pw1"
    private var now = 1_000_000L
    private var harvests = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var harvest: suspend () -> String = { "fresh" }

    private fun kotlinx.coroutines.test.TestScope.renewal() = MoodleTokenRenewal(
        storedToken = { stored },
        credentials = { credentials },
        harvest = { _ ->
            harvests++
            gate?.await()
            harvest().also { stored = it }
        },
        now = { now },
        scope = backgroundScope,
        pauseMs = 300_000L,
    )

    private suspend fun MoodleTokenRenewal.failure(rejected: String?): Throwable? =
        runCatching { renew(rejected) }.exceptionOrNull()

    @Test
    fun `a token another caller already renewed comes back without a sign-in`() = runTest(UnconfinedTestDispatcher()) {
        stored = "newer"
        assertEquals("newer", renewal().renew(rejected = "old"))
        assertEquals(0, harvests)
    }

    @Test
    fun `callers renewing at once share one sign-in`() = runTest(UnconfinedTestDispatcher()) {
        gate = CompletableDeferred()
        val renewal = renewal()
        val calls = (1..4).map { async { renewal.renew("old") } }
        runCurrent()
        gate!!.complete(Unit)

        assertEquals(List(4) { "fresh" }, calls.map { it.await() })
        assertEquals(1, harvests)
    }

    @Test
    fun `callers renewing at once share one failure too`() = runTest(UnconfinedTestDispatcher()) {
        gate = CompletableDeferred()
        harvest = { throw MoodleWebserviceError.MalformedResponse("sso page changed") }
        val renewal = renewal()
        val calls = (1..4).map { async { renewal.failure("old") } }
        runCurrent()
        gate!!.complete(Unit)

        assertTrue(calls.all { it.await() is MoodleWebserviceError.MalformedResponse })
        assertEquals(1, harvests)
    }

    @Test
    fun `a failed sign-in is not tried again until the pause is over`() = runTest(UnconfinedTestDispatcher()) {
        harvest = { throw MoodleWebserviceError.MalformedResponse("sso page changed") }
        val renewal = renewal()
        renewal.failure("old")

        now += 299_000L
        assertTrue(renewal.failure("old") is MoodleWebserviceError.MalformedResponse)
        assertEquals("the failure is answered from memory", 1, harvests)

        now += 2_000L
        harvest = { "fresh" }
        assertEquals("fresh", renewal.renew("old"))
        assertEquals(2, harvests)
    }

    @Test
    fun `a network failure starts no pause`() = runTest(UnconfinedTestDispatcher()) {
        harvest = { throw MoodleWebserviceError.TransientNetwork("offline") }
        val renewal = renewal()
        renewal.failure("old")
        harvest = { "fresh" }

        assertEquals("fresh", renewal.renew("old"))
        assertEquals(2, harvests)
    }

    @Test
    fun `a password SSO refused is not sent again until the credentials change`() = runTest(UnconfinedTestDispatcher()) {
        harvest = { throw MoodleWebserviceError.SsoLoginRejected("wrong password") }
        val renewal = renewal()
        renewal.failure("old")

        now += 24 * 3_600_000L
        assertTrue(renewal.failure("old") is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(1, harvests)

        credentials = "B11300001" to "pw2"
        harvest = { "fresh" }
        assertEquals("fresh", renewal.renew("old"))
        assertEquals(2, harvests)
    }

    @Test
    fun `without stored credentials nothing is sent`() = runTest(UnconfinedTestDispatcher()) {
        credentials = null
        assertTrue(renewal().failure("old") is MoodleWebserviceError.MissingStoredCredentials)
        assertEquals(0, harvests)
    }

    @Test
    fun `a pause holds only for the credentials that failed`() = runTest(UnconfinedTestDispatcher()) {
        harvest = { throw MoodleWebserviceError.MalformedResponse("sso page changed") }
        val renewal = renewal()
        renewal.failure("old")

        credentials = "B11300002" to "pw"
        harvest = { "fresh" }
        assertEquals("another account signs in at once", "fresh", renewal.renew("old"))
        assertEquals(2, harvests)
    }

    @Test
    fun `a caller that gives up does not stop the sign-in the others wait on`() = runTest(UnconfinedTestDispatcher()) {
        gate = CompletableDeferred()
        val renewal = renewal()
        val leaving = async { renewal.renew("old") }
        val staying = async { renewal.renew("old") }
        runCurrent()

        leaving.cancel()
        gate!!.complete(Unit)

        assertEquals("fresh", staying.await())
        assertEquals(1, harvests)
        assertEquals("the token is stored all the same", "fresh", stored)
    }
}
