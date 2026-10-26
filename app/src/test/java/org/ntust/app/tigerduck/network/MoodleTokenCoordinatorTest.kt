package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch

/** How MoodleTokenService gets, renews and stores the token around its SSO sign-in. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleTokenCoordinatorTest {

    private class Tokens : MoodleTokenCoordinator.Tokens {
        override var token: String? = null
        override var studentId: String? = "B11300001"
        override var password: String? = "pw"
        override fun storeIfSignedIn(studentId: String, token: String): Boolean {
            if (this.studentId != studentId) return false
            this.token = token
            return true
        }
    }

    private val tokens = Tokens()
    private val latch = InMemoryStoredPasswordLatch()
    private var now = 1_000_000L
    private val logins = mutableListOf<String>()
    private var loginGate: CompletableDeferred<Unit>? = null
    private var login: suspend (String, String) -> String = { id, _ -> "token-$id-${logins.size}" }

    private fun kotlinx.coroutines.test.TestScope.coordinator() = MoodleTokenCoordinator(
        tokens = tokens,
        login = { id, password, posting ->
            logins += id
            loginGate?.await()
            posting.post { login(id, password) }
        },
        scope = backgroundScope,
        now = { now },
        pauseMs = 300_000L,
        passwordLatch = latch,
    )

    @Test
    fun `a renewal waiting behind the sign-in takes the token it got, with no second sign-in`() = runTest(UnconfinedTestDispatcher()) {
        loginGate = CompletableDeferred()
        val coordinator = coordinator()
        // The sign-in harvests a token; a Moodle call finds none yet and asks for one meanwhile.
        val signIn = async { coordinator.obtain("b11300001", "pw") }
        runCurrent()
        val renewal = async { coordinator.renew(rejected = null) }
        runCurrent()
        loginGate!!.complete(Unit)

        assertEquals(signIn.await(), renewal.await())
        assertEquals(listOf("B11300001"), logins)
    }

    @Test
    fun `a renewal stores the token it got`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val fresh = coordinator().renew(rejected = "dead")

        assertEquals(fresh, tokens.token)
        assertEquals(1, logins.size)
    }

    @Test
    fun `a sign-in by hand lets renewals try again after a failure`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        login = { _, _ -> throw MoodleWebserviceError.SsoLoginRejected("wrong password") }
        runCatching { coordinator.renew("dead") }
        assertTrue(runCatching { coordinator.renew("dead") }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("refused credentials are not sent twice", 1, logins.size)

        login = { _, _ -> "fresh" }
        coordinator.obtain("B11300001", "pw")
        tokens.token = "dead-again"

        assertEquals("fresh", coordinator.renew("dead-again"))
        assertEquals(3, logins.size)
    }

    @Test
    fun `a token harvested for an account that signed out meanwhile is not kept`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        login = { _, _ ->
            tokens.studentId = null
            "departing"
        }

        val error = runCatching { coordinator().renew("dead") }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.MissingStoredCredentials)
        assertEquals("dead", tokens.token)
        assertNull(tokens.studentId)
    }

    @Test
    fun `a renewal with a password SSO refused sends nothing`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        login = { _, _ -> throw MoodleWebserviceError.SsoLoginRejected("wrong password") }
        runCatching { coordinator.renew("dead") }

        assertTrue(runCatching { coordinator.renew("dead") }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("the refused password is not sent again", 1, logins.size)
    }

    @Test
    fun `a renewal is paused after a failed sign-in`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        login = { _, _ -> throw MoodleWebserviceError.SiteMaintenance() }
        runCatching { coordinator.renew("dead") }

        assertTrue(runCatching { coordinator.renew("dead") }.exceptionOrNull() is MoodleWebserviceError.SiteMaintenance)
        assertEquals(1, logins.size)

        now += 300_000L
        login = { _, _ -> "fresh" }
        assertEquals("fresh", coordinator.renew("dead"))
        assertEquals("the pause over, it signs in", 2, logins.size)
    }

    @Test
    fun `refusals are kept in the app-wide latch, which a sign-in by hand lifts`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val first = MoodleTokenCoordinator(tokens, { id, _, posting -> logins += id; posting.post { throw MoodleWebserviceError.SsoLoginRejected("wrong") } }, backgroundScope, { now }, 300_000L, latch)
        runCatching { first.renew("dead") }
        assertEquals("wrong", latch.rejection.value?.reason)

        // Another coordinator over the same latch, as after the app restarts with the persisted one.
        val second = MoodleTokenCoordinator(tokens, { id, _, _ -> logins += id; "fresh" }, backgroundScope, { now }, 300_000L, latch)
        assertTrue(runCatching { second.renew("dead") }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(1, logins.size)

        second.obtain("B11300001", "pw")
        assertNull("a sign-in by hand lifts it", latch.rejection.value)
    }

    @Test
    fun `a refusal latched while a renewal waits to post stops it before the password goes out`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        var posted = false
        val coordinator = MoodleTokenCoordinator(
            tokens,
            { _, _, posting ->
                // Another sign-in's refusal lands while this one is on its way to the form.
                latch.markRejected(latch.generation, "B11300001", "pw", "Locked")
                posting.post {
                    posted = true
                    "fresh"
                }
            },
            backgroundScope, { now }, 300_000L, latch,
        )

        val error = runCatching { coordinator.renew("dead") }.exceptionOrNull()

        assertTrue("got $error", error is MoodleWebserviceError.SsoLoginRejected)
        assertTrue("nothing posted", !posted)
        assertEquals("dead", tokens.token)
    }

    @Test
    fun `a sign-in by hand posts what was typed, latch or not`() = runTest(UnconfinedTestDispatcher()) {
        latch.markRejected(latch.generation, "B11300001", "old", "Wrong")

        assertEquals("token-B11300001-1", coordinator().obtain("B11300001", "new"))
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a sign-out during a sign-in by hand stores nothing of the departing account`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = null
        login = { _, _ ->
            tokens.studentId = null
            "departing"
        }

        val error = runCatching { coordinator().obtain("B11300001", "pw") }.exceptionOrNull()

        assertTrue("got $error", error is MoodleWebserviceError.MissingStoredCredentials)
        assertNull(tokens.token)
    }

    @Test
    fun `a sign-out right after a renewal checked the account leaves no token behind`() = runTest(UnconfinedTestDispatcher()) {
        var loggedIn = false
        var signedOut = false
        val racing = object : MoodleTokenCoordinator.Tokens {
            private var id: String? = "B11300001"
            override var token: String? = null
            override var password: String? = "pw"
            // The sign-out lands the moment the account is read after the SSO answer.
            override val studentId: String? get() = id.also { if (loggedIn && !signedOut) signOut() }
            // Atomic with a sign-out, as the credential store makes it.
            override fun storeIfSignedIn(studentId: String, token: String): Boolean {
                if (id != studentId) return false
                this.token = token
                return true
            }
            fun signOut() {
                signedOut = true
                id = null
                password = null
                token = null
            }
        }
        val coordinator = MoodleTokenCoordinator(racing, { id, _, _ -> logins += id; loggedIn = true; "departing" }, backgroundScope, { now }, 300_000L, latch)

        runCatching { coordinator.renew(null) }
        if (!signedOut) racing.signOut()

        assertNull("the departing account's token stays out of the store", racing.token)
    }
}
