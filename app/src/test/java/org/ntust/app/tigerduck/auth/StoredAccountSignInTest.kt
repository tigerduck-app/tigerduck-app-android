package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [StoredAccountSignIn], the automatic sign-in AuthService hands [AuthTokenManager], against a
 * scripted backend, with stand-ins for the stored account and the Moodle token harvest.
 */
class StoredAccountSignInTest {

    private lateinit var manager: AuthTokenManager
    private val backend = ScriptedBackend()

    private var demo = false
    private var studentId: String? = "B00000000"
    private var password: String? = "password"
    private var storedToken: String? = "stored"
    private var harvest: suspend () -> String = { "fresh" }
    private var harvests = 0
    private val harvestedInPlaceOf = mutableListOf<String?>()
    private var afterSignIns = 0

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        tokens.clear()
        manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    }

    @Test
    fun `it signs in with the stored Moodle token, sending no password to harvest another`() = runTest {
        assertEquals(SignInAttempt.SIGNED_IN, signIn())

        assertEquals(listOf<String?>("stored"), backend.sentMoodleTokens())
        assertEquals(0, harvests)
        assertTrue(manager.isLoggedIn)
        assertEquals(1, afterSignIns)
    }

    @Test
    fun `with no token stored, it signs in with one harvested for the purpose`() = runTest {
        storedToken = null

        assertEquals(SignInAttempt.SIGNED_IN, signIn())

        assertEquals(listOf<String?>("fresh"), backend.sentMoodleTokens())
        assertEquals(listOf<String?>(null), harvestedInPlaceOf)
    }

    @Test
    fun `a stored token the backend refuses is replaced by a harvested one`() = runTest {
        backend.answer(LOGIN, 401)

        assertEquals(SignInAttempt.SIGNED_IN, signIn())

        assertEquals(listOf<String?>("stored", "fresh"), backend.sentMoodleTokens())
        assertEquals(1, afterSignIns)
        // So the harvest replaces it rather than handing it back.
        assertEquals(listOf<String?>("stored"), harvestedInPlaceOf)
    }

    @Test
    fun `a harvest that hands the refused token back sends no second login`() = runTest {
        backend.answer(LOGIN, 401)
        harvest = { "stored" }

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(listOf<String?>("stored"), backend.sentMoodleTokens())
    }

    @Test
    fun `a sign-in that fails for another reason harvests nothing`() = runTest {
        backend.answer(LOGIN, 503)

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(0, harvests)
        assertEquals(1, backend.received(LOGIN).size)
    }

    @Test
    fun `a refused stored token and a harvest that fails is a failure`() = runTest {
        backend.answer(LOGIN, 401)
        harvest = { throw IllegalStateException("Moodle unavailable") }

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(listOf<String?>("stored"), backend.sentMoodleTokens())
    }

    @Test
    fun `a harvest the stored-password latch stops is a failure and sends no login`() = runTest {
        storedToken = null
        harvest = { throw MoodleWebserviceError.SsoLoginRejected(null) }

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `with a stored token and no password, it signs in with the token`() = runTest {
        password = null

        assertEquals(SignInAttempt.SIGNED_IN, signIn())

        assertEquals(listOf<String?>("stored"), backend.sentMoodleTokens())
        assertEquals(0, harvests)
    }

    @Test
    fun `with no account, the demo account, or neither a password nor a token, nothing goes out`() = runTest {
        studentId = null
        assertEquals(SignInAttempt.NOTHING_TO_SEND, signIn())

        studentId = "B00000000"
        demo = true
        assertEquals(SignInAttempt.NOTHING_TO_SEND, signIn())

        demo = false
        password = null
        storedToken = null
        assertEquals(SignInAttempt.NOTHING_TO_SEND, signIn())

        assertEquals(0, harvests)
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `a harvest that fails with no token stored is a failure and sends no login`() = runTest {
        harvest = { throw IllegalStateException("Moodle unavailable") }
        storedToken = null

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `a sign-out during the harvest sends no login`() = runTest {
        storedToken = null
        val generation = manager.signInGeneration
        harvest = {
            manager.logout()
            // The next account's sign-in stored its token meanwhile, and the
            // harvest, which stores nothing for an account that left, fails.
            storedToken = "next-account"
            throw IllegalStateException("signed out")
        }

        assertEquals(SignInAttempt.FAILED, signIn(generation))

        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `a refused login is a failure and starts nothing after it`() = runTest {
        storedToken = null
        backend.answer(LOGIN, 503)

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(1, backend.received(LOGIN).size)
        assertEquals(0, afterSignIns)
    }

    private suspend fun signIn(generation: Int = manager.signInGeneration): SignInAttempt = StoredAccountSignIn(
        tokens = manager,
        isDemo = { demo },
        studentId = { studentId },
        password = { password },
        storedMoodleToken = { storedToken },
        harvestMoodleToken = { refused ->
            harvests++
            harvestedInPlaceOf += refused
            harvest()
        },
        afterSignIn = { afterSignIns++ },
    ).signIn(generation)

    private companion object {
        const val LOGIN = "POST /auth/login"
    }
}
