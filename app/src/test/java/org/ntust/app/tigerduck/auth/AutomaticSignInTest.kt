package org.ntust.app.tigerduck.auth

import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.AnonymousDeviceRequest
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore
import org.ntust.app.tigerduck.push.DeviceRegisterRequest
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.UnregisterHeldBack
import org.ntust.app.tigerduck.push.PushTokenIn
import org.ntust.app.tigerduck.push.SettingsDocumentApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The backend sign-in [AuthTokenManager] makes on its own: before a request that needs a session
 * finds none, and on each return to the app
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/202).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSignInTest {

    private lateinit var prefs: AppPreferences
    private lateinit var tokens: V3TokenStore
    private var clock = 1_800_000_000_000L
    private val backend = ScriptedBackend()

    /** Whether an account is stored for the stand-in sign-in to send. */
    private var accountStored = true

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        tokens.clear()
    }

    // No request without a token

    @Test
    fun `a protected request with no session signs in first and goes out with the new session`() = runTest {
        val api = PushApiClient(backend.client, prefs, manager())

        api.register(registerRequest())

        assertEquals(listOf(LOGIN, REGISTER), backend.endpoints())
        assertEquals("Bearer access-2", backend.received(REGISTER).single().authorization)
    }

    @Test
    fun `a protected request with no session sends nothing when the sign-in fails`() = runTest {
        backend.answer(LOGIN, 503)
        val api = PushApiClient(backend.client, prefs, manager())

        val failure = runCatching { api.register(registerRequest()) }.exceptionOrNull()

        assertTrue("got $failure", failure is NotSignedInException)
        assertEquals(listOf(LOGIN), backend.endpoints())
    }

    @Test
    fun `no client sends a request that needs a session without one`() = runTest {
        accountStored = false
        val manager = manager()
        val requests: List<suspend () -> Unit> = listOf(
            { PushApiClient(backend.client, prefs, manager).updateCredentials("moodle") },
            { BulletinApiClient(backend.client, prefs, manager).fetchSubscriptions() },
            { SettingsDocumentApiClient(backend.client, prefs, manager).read<JsonObject>("notification") },
            { SyncApiClient(manager, prefs, backend.client).fetchFullSync() },
        )

        val failures = requests.map { request -> runCatching { request() }.exceptionOrNull() }

        assertTrue("got $failures", failures.all { it is NotSignedInException })
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `the announce goes out without a header and waits for no sign-in`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val api = PushApiClient(backend.client, prefs, manager())
        api.registerAnonymous(announceRequest())
        // Signed out, with an account whose sign-in would fail.
        tokens.clear()
        backend.answer(LOGIN, 503)

        api.registerAnonymous(announceRequest())

        assertEquals(listOf(ANNOUNCE, ANNOUNCE), backend.endpoints())
        assertEquals(listOf<String?>(null, null), backend.received(ANNOUNCE).map { it.authorization })
    }

    /**
     * The unregister's own check, asked by the real client on its I/O thread right before the
     * DELETE goes (#593): told no, nothing reaches the backend.
     */
    @Test
    fun `an unregister told not to depart sends nothing`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val manager = manager()
        val departing = manager.departingSession()
        accountStored = false
        manager.logout()

        val failure = runCatching {
            PushApiClient(backend.client, prefs, manager)
                .deleteDevice(departing!!.baseUrl, "device-1", departing.accessToken) { false }
        }.exceptionOrNull()

        assertTrue(failure is UnregisterHeldBack)
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `the unregister after a sign-out goes out with the token taken before the tokens were wiped`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val manager = manager()
        // In the order AuthService.logout() takes them.
        val departing = manager.departingSession()
        accountStored = false
        manager.logout()

        val status = PushApiClient(backend.client, prefs, manager)
            .deleteDevice(departing!!.baseUrl, "device-1", departing.accessToken)

        assertEquals(200, status)
        assertEquals(listOf(UNREGISTER), backend.endpoints())
        assertEquals("Bearer access-1", backend.received(UNREGISTER).single().authorization)
    }

    @Test
    fun `a sign-out with no session takes none to unregister with`() = runTest {
        val manager = manager()

        assertNull(manager.departingSession())
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `the departing session is refreshed without storing the pair`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = 0L)
        val manager = manager()
        val departing = manager.departingSession()!!
        accountStored = false
        manager.logout()

        val reply = manager.refreshDeparting(departing, sendsWithoutReply = 0)

        assertEquals("access-2", (reply as DepartingRefresh.Refreshed).session.accessToken)
        assertEquals("refresh-2", reply.session.refreshToken)
        assertEquals("refresh-1", JSONObject(backend.received(REFRESH).single().body).getString("refresh_token"))
        // The session here is whoever signs in next, and nobody has.
        assertNull(tokens.refreshToken)
        assertFalse(manager.isLoggedIn)
    }

    @Test
    fun `a departing refresh the backend turns down is refused, and a busy one left for later`() = runTest {
        val manager = manager()
        val departing = DepartingSession("access-1", "refresh-1", 0L, resolveAnnouncementEndpoint(prefs).url)
        backend.answer(REFRESH, 401)
        assertEquals(DepartingRefresh.Refused, manager.refreshDeparting(departing, sendsWithoutReply = 0))

        backend.answer(REFRESH, 503)
        assertEquals(DepartingRefresh.Unavailable, manager.refreshDeparting(departing, sendsWithoutReply = 0))
    }

    @Test
    fun `the departing tokens go back to the backend that issued them after the endpoint changes`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = 0L)
        val manager = manager()
        val departing = manager.departingSession()!!
        accountStored = false
        manager.logout()
        // The next person on the phone points the app at their own backend.
        prefs.announcementApiBaseUrlOverride = "http://10.0.0.9:9/v3"
        assertEquals("10.0.0.9", resolveAnnouncementEndpoint(prefs).url.toHttpUrl().host)

        val refreshed = manager.refreshDeparting(departing, sendsWithoutReply = 0) as DepartingRefresh.Refreshed
        PushApiClient(backend.client, prefs, manager)
            .deleteDevice(refreshed.session.baseUrl, "device-1", refreshed.session.accessToken)

        assertEquals(listOf("127.0.0.1", "127.0.0.1"), backend.received(REFRESH).map { it.host } + backend.received(UNREGISTER).map { it.host })
    }

    // The departing session names the backend and the student its sign-in was for

    @Test
    fun `the departing session goes back to the backend that issued it, recorded at sign-in`() = runTest {
        val manager = manager()
        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)
        // Changed while signed in: the pair was still issued by the first backend.
        prefs.announcementApiBaseUrlOverride = "http://10.0.0.9:9/v3"

        val departing = manager.departingSession()!!

        assertEquals("127.0.0.1", departing.baseUrl.toHttpUrl().host)
        assertEquals("B00000000", departing.studentId)
    }

    @Test
    fun `a pair stored before the backend was recorded falls back to the endpoint set`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)

        val departing = manager().departingSession()!!

        assertEquals(resolveAnnouncementEndpoint(prefs).url, departing.baseUrl)
        assertNull(departing.studentId)
    }

    // A sign-in and the unregister a sign-out still owes

    private fun owed(): Pair<OwedUnregister, () -> PendingUnregister?> {
        var pending: PendingUnregister? = PendingUnregister(
            "device-1",
            DepartingSession("access-0", "refresh-0", Long.MAX_VALUE, resolveAnnouncementEndpoint(prefs).url),
            owedAt = 0L,
        )
        val store = object : PendingUnregisterStore {
            override fun load() = pending
            override fun save(pending: PendingUnregister) = true
            override fun clear() {
                pending = null
            }
        }
        return OwedUnregister(store) to { pending }
    }

    @Test
    fun `a sign-in that lands ends the owed unregister, one turned down keeps it`() = runTest {
        val (owed, pending) = owed()
        val manager = manager(owedUnregister = owed)

        backend.answer(LOGIN, 503)
        runCatching { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null) }
        assertEquals("device-1", pending()?.deviceId)

        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)
        assertNull(pending())
    }

    @Test
    fun `a sign-in at another backend leaves the unregister owed to the one before`() = runTest {
        val (owed, pending) = owed()
        val manager = manager(owedUnregister = owed)
        // The endpoint set since the sign-out: logging in there moves nothing at the backend owed.
        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:10/v3"

        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

        assertEquals("device-1", pending()?.deviceId)
    }

    @Test
    fun `a debt owed to the backend spelled another way is still that backend's`() = runTest {
        // As a build that kept the address as typed stored it.
        var pending: PendingUnregister? = PendingUnregister(
            "device-1",
            DepartingSession("access-0", "refresh-0", Long.MAX_VALUE, "HTTP://127.0.0.1:9/v3/"),
            owedAt = 0L,
        )
        val store = object : PendingUnregisterStore {
            override fun load() = pending
            override fun save(pending: PendingUnregister) = true
            override fun clear() {
                pending = null
            }
        }
        val manager = manager(owedUnregister = OwedUnregister(store))

        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

        assertNull("signed in at the same backend, the device is the new session's", pending)
        assertTrue(manager.hasSessionAt("HTTP://127.0.0.1:9/v3/"))
    }

    @Test
    fun `a session is the backend's that issued it, whatever the endpoint names since`() = runTest {
        val manager = manager()
        val issuer = resolveAnnouncementEndpoint(prefs).url
        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:10/v3"

        assertTrue(manager.hasSessionAt(issuer))
        assertFalse(manager.hasSessionAt("http://127.0.0.1:10/v3"))
    }

    @Test
    fun `a sign-in waits for the owed unregister's lock before its login goes out`() = runTest {
        val (owed, _) = owed()
        val manager = manager(owedUnregister = owed)
        owed.mutex.lock()

        val signingIn = async(Dispatchers.Default) {
            manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)
        }
        Thread.sleep(300)
        assertEquals(emptyList<String>(), backend.endpoints())

        owed.mutex.unlock()
        signingIn.await()
        assertEquals(listOf(LOGIN), backend.endpoints())
    }

    @Test
    fun `the unregister's DELETE gives up within a bound and is never sent again by OkHttp on its own`() {
        val client = PushApiClient(backend.client, prefs, manager()).deleteClient

        assertFalse(client.retryOnConnectionFailure)
        assertEquals(20_000, client.callTimeoutMillis)
    }

    // Signing in again, and the pause after a failure

    @Test
    fun `a return to the app after a failed relogin signs in again once the pause has passed, and not before`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = 0L)
        backend.answer(REFRESH, 401)
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertNull(manager.authHeader())
        assertFalse(manager.isLoggedIn)

        // What MainActivity.onResume calls.
        clock += 299_000
        assertFalse(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)

        clock += 1_000
        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `callers that find no session at once share one sign-in`() = runTest {
        val held = backend.hold(LOGIN)
        val manager = manager()

        val first = async { manager.authHeader() }
        val second = async { manager.authHeader() }
        held.arrived.await()
        held.release(200)

        assertEquals(listOf("Bearer access-2", "Bearer access-2"), listOf(first.await(), second.await()))
        assertEquals(1, backend.received(LOGIN).size)
    }

    @Test
    fun `a request whose sign-in a sign-out overtook gets no token, not even the next account's`() = runTest {
        val held = backend.hold(LOGIN)
        val manager = manager()

        val request = async { manager.authHeader() }
        held.arrived.await()
        // The user signs out, and the next account signs in, while the sign-in is out.
        manager.logout()
        tokens.save("access-b", "refresh-b", clock + 900_000)
        held.release(200)

        assertNull(request.await())
        assertEquals("refresh-b", tokens.refreshToken)
    }

    @Test
    fun `a request waiting on a refresh that a sign-out overtook gets no token, not even the next account's`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = 0L)
        val held = backend.hold(REFRESH)
        val manager = manager()

        val first = async { manager.authHeader() }
        held.arrived.await()
        val second = async { manager.authHeader() }
        runCurrent()
        manager.logout()
        tokens.save("access-b", "refresh-b", clock + 900_000)
        held.release(200)

        assertEquals(listOf<String?>(null, null), listOf(first.await(), second.await()))
        // The next account's refresh token never went out, which would rotate it unseen.
        assertEquals(1, backend.received(REFRESH).size)
        assertEquals("refresh-b", tokens.refreshToken)
    }

    @Test
    fun `an interactive sign-in ignores the pause`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        assertTrue(manager.signInInteractively { manager.loginAsTheUser(it) })

        assertEquals(2, backend.received(LOGIN).size)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `the try of a refused stored password signs in without waiting out the pause`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        assertTrue(manager.signInIfSignedOutNow())

        assertEquals(2, backend.received(LOGIN).size)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `an automatic sign-in that starts during an interactive one waits for it and sends no login`() = runTest {
        val held = backend.hold(LOGIN)
        val manager = manager()

        val interactive = async { manager.signInInteractively { manager.loginAsTheUser(it) } }
        held.arrived.await()
        // Home's pull, once the account shows as signed in, waiting before the answer comes.
        val automatic = async { manager.signInIfSignedOut() }
        runCurrent()
        held.release(200)

        assertEquals(listOf(true, true), listOf(interactive.await(), automatic.await()))
        assertEquals(1, backend.received(LOGIN).size)
    }

    @Test
    fun `an interactive sign-in that fails holds the automatic one off`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInInteractively { manager.loginAsTheUser(it) })

        // The registration the sign-in asks for, a moment later.
        assertFalse(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)

        clock += 300_000
        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
    }

    @Test
    fun `an interactive sign-in that throws passes the exception on and leaves no sign-in under way`() = runTest {
        val manager = manager()

        val failure = runCatching {
            manager.signInInteractively { throw IllegalStateException("account unwritable") }
        }.exceptionOrNull()

        // AuthService.login() reports it, rather than a sign-in that went through.
        assertTrue("got $failure", failure is IllegalStateException)
        assertTrue(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)
    }

    @Test
    fun `a sign-out ends the pause`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        manager.logout()

        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
    }

    @Test
    fun `a sign-in that a sign-out overtook starts no pause`() = runTest {
        val manager = manager()
        backend.onLogin = { manager.logout() }
        assertFalse(manager.signInIfSignedOut())
        backend.onLogin = {}

        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
    }

    @Test
    fun `a sign-in that throws reads as no session and holds the next one off`() = runTest {
        var attempts = 0
        val manager = manager().apply {
            signInWithStoredAccount = {
                attempts++
                throw IllegalStateException("account unreadable")
            }
        }

        assertFalse(manager.signInIfSignedOut())
        assertNull(manager.authHeader())

        assertEquals(1, attempts)
    }

    @Test
    fun `with no account stored nothing goes out and no pause starts`() = runTest {
        accountStored = false
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        accountStored = true

        assertTrue(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)
    }

    // What the TigerSync status reads (issue #203)

    @Test
    fun `a failed sign-in is reported until one succeeds`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.lastSignInFailed.value)

        assertFalse(manager.signInIfSignedOut())
        assertTrue(manager.lastSignInFailed.value)

        clock += 300_000
        assertTrue(manager.signInIfSignedOut())
        assertFalse(manager.lastSignInFailed.value)
    }

    @Test
    fun `a sign-in under way is not reported as failed, as it may yet succeed`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        manager.signInIfSignedOut()
        clock += 300_000
        val held = backend.hold(LOGIN)

        val signIn = async { manager.signInIfSignedOut() }
        held.arrived.await()
        assertFalse(manager.lastSignInFailed.value)

        held.release(503)
        assertFalse(signIn.await())
        assertTrue(manager.lastSignInFailed.value)
    }

    @Test
    fun `an interactive sign-in that fails is reported too, and a sign-out clears it`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInInteractively { manager.loginAsTheUser(it) })
        assertTrue(manager.lastSignInFailed.value)

        manager.logout()

        assertFalse(manager.lastSignInFailed.value)
    }

    @Test
    fun `a sign-in with nothing to send is no failure`() = runTest {
        accountStored = false
        val manager = manager()

        assertFalse(manager.signInIfSignedOut())

        assertFalse(manager.lastSignInFailed.value)
    }

    private fun manager(owedUnregister: OwedUnregister? = null) = AuthTokenManager(
        tokens,
        backend.client,
        prefs,
        "device-1",
        now = { clock },
        owedUnregister = owedUnregister,
    ).apply {
        // Stands in for AuthService's, which sends the stored account through login().
        signInWithStoredAccount = { signIn ->
            if (accountStored) loginAsTheUser(signIn) else SignInAttempt.NOTHING_TO_SEND
        }
    }

    /** What AuthService.login() sends to the backend once the school took the password. */
    private suspend fun AuthTokenManager.loginAsTheUser(signIn: Int): SignInAttempt = try {
        login("B00000000", "moodle", moodlePrivateToken = null, signInGeneration = signIn)
        SignInAttempt.SIGNED_IN
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SignInAttempt.FAILED
    }

    private fun registerRequest() = DeviceRegisterRequest(
        clientDeviceId = "device-1",
        pushToken = PushTokenIn(tokenValue = "fcm-1"),
    )

    private fun announceRequest() = AnonymousDeviceRequest(deviceId = "device-1", deviceClass = "phone")

    private companion object {
        const val LOGIN = "POST /auth/login"
        const val REFRESH = "POST /auth/refresh"
        const val REGISTER = "POST /devices/register"
        const val ANNOUNCE = "POST /devices/anonymous"
        const val UNREGISTER = "DELETE /devices/device-1"
    }
}
