package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.DeviceRegisterRequest
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushTokenIn
import org.ntust.app.tigerduck.util.JvmContext

/** A stored session from another backend is no session at the endpoint now resolved. */
class SessionBackendMoveTest {
    private lateinit var prefs: AppPreferences
    private lateinit var tokens: V3TokenStore
    private val backend = ScriptedBackend(under = "/v4")
    private val store = MemoryStore()
    private val owed = OwedUnregister(store)
    private var clock = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = CURRENT }
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens = V3TokenStore(context.getSharedPreferences("session-backend-move", Context.MODE_PRIVATE))
        tokens.save("access-a", "refresh-a", clock + 900_000, ISSUER, STUDENT)
    }

    private fun manager() = AuthTokenManager(
        tokens, backend.client, prefs, "device-1", now = { clock }, owedUnregister = owed,
    ).apply {
        val storedAccount = StoredAccountSignIn(
            tokens = this,
            studentId = { STUDENT },
            password = { "fake-school-password" },
            storedMoodleToken = { "fake-moodle-token" },
            harvestMoodleToken = { error("the stored Moodle token is enough") },
            afterSignIn = {},
        )
        signInWithStoredAccount = storedAccount::signIn
    }

    @Test
    fun `a header at the new backend signs in there with the stored school account`() = runTest {
        val manager = manager()

        assertEquals("Bearer access-2", manager.requireAuthHeader(manager.session.signIns.current))
        PushApiClient(backend.client, prefs, manager).register(
            DeviceRegisterRequest(clientDeviceId = "device-1", pushToken = PushTokenIn(tokenValue = "fake-fcm")),
            manager.session.signIns.current,
        )

        assertEquals(listOf(LOGIN, REGISTER), backend.endpoints())
        assertEquals("127.0.0.2", backend.received(LOGIN).single().host)
        assertEquals(STUDENT, JSONObject(backend.received(LOGIN).single().body).getString("student_id"))
        assertEquals(listOf("fake-moodle-token"), backend.sentMoodleTokens())
        assertEquals("Bearer access-2", backend.received(REGISTER).single().authorization)
        assertFalse(manager.mayPresent("access-a", "$CURRENT/sync/full".toHttpUrl()))
    }

    @Test
    fun `the next automatic sign-in signs in at the current backend`() = runTest {
        val manager = manager()
        assertTrue("any stored pair still counts as logged in", manager.isLoggedIn)

        assertTrue(manager.signInIfSignedOut())

        assertEquals(listOf(LOGIN), backend.endpoints())
        assertTrue(manager.hasSessionAt(CURRENT))
        assertFalse(manager.hasSessionAt(ISSUER))
    }

    @Test
    fun `the stored-password retry signs in at the current backend too`() = runTest {
        val manager = manager()

        assertTrue(manager.signInIfSignedOutNow())

        assertEquals(listOf(LOGIN), backend.endpoints())
        assertTrue(manager.hasSessionAt(CURRENT))
    }

    @Test
    fun `the new backend's login keeps the unregister owed to the issuing backend`() = runTest {
        tokens.saveRefreshTokenSends(1)
        val manager = manager()
        val departing = manager.departingSession()!!
        backend.onLogin = { assertEquals("refresh-a", store.pending!!.session.refreshToken) }

        assertTrue(manager.signInIfSignedOut())

        val pending = store.pending!!
        assertEquals("device-1", pending.deviceId)
        assertEquals(clock, pending.owedAt)
        assertEquals(departing.accessToken, pending.session.accessToken)
        assertEquals(departing.refreshToken, pending.session.refreshToken)
        assertEquals(departing.accessExpiresAt, pending.session.accessExpiresAt)
        assertEquals(departing.baseUrl, pending.session.baseUrl)
        assertEquals(STUDENT, pending.session.studentId)
        assertEquals(1, pending.session.sendsWithoutReply)
        assertEquals(1, pending.unansweredRefreshes)
        val api = PushApiClient(backend.client, prefs, manager)
        val unregister = DepartingDeviceUnregister(
            owed, api::deleteDevice, manager::refreshDeparting, manager::hasSessionAt,
            now = { clock }, endpoint = { resolveAnnouncementEndpoint(prefs).url },
        )
        // A plain-HTTP debt stays until its backend is selected again.
        assertEquals(DepartingDeviceUnregister.Outcome.RETRY, unregister.send())
        assertSame(pending, store.pending)
        prefs.announcementApiBaseUrlOverride = ISSUER
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister.send())
        assertEquals("127.0.0.1", backend.received(UNREGISTER).single().host)
        assertEquals("Bearer access-a", backend.received(UNREGISTER).single().authorization)
    }

    @Test
    fun `a refresh of the old pair landing after the new login cannot replace it`() = runTest {
        prefs.announcementApiBaseUrlOverride = ISSUER
        tokens.save("access-a", "refresh-a", 0, ISSUER, STUDENT)
        val manager = manager()
        val held = backend.hold(REFRESH).apply {
            body = """{"access_token":"access-a-new","refresh_token":"refresh-a-new","expires_in":900}"""
        }
        val refreshing = async { manager.authHeader() }
        held.arrived.await()
        prefs.announcementApiBaseUrlOverride = CURRENT

        val signedIn = manager.signInIfSignedOut()
        held.release(200)
        val header = refreshing.await()

        assertTrue(signedIn)
        assertEquals("Bearer access-2", header)
        assertEquals("refresh-2", tokens.refreshToken)
        assertTrue(manager.hasSessionAt(CURRENT))
        assertEquals("127.0.0.1", backend.received(REFRESH).single().host)
        assertEquals(1, store.pending!!.unansweredRefreshes)
        val departing = manager.refreshDeparting(store.pending!!.session, sendsWithoutReply = 1)
        assertEquals("refresh-a-new", (departing as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(1, backend.received(REFRESH).size)
    }

    @Test
    fun `a refresh reply before the new login goes out does not make that login moot`() = runTest {
        prefs.announcementApiBaseUrlOverride = ISSUER
        tokens.save("access-a", "refresh-a", 0, ISSUER, STUDENT)
        val manager = manager()
        val refresh = backend.hold(REFRESH).apply {
            body = """{"access_token":"access-a-new","refresh_token":"refresh-a-new","expires_in":900}"""
        }
        val refreshing = async { manager.authHeader() }
        refresh.arrived.await()
        prefs.announcementApiBaseUrlOverride = CURRENT
        val asked = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val storedSignIn = manager.signInWithStoredAccount!!
        manager.signInWithStoredAccount = {
            asked.complete(Unit)
            proceed.await()
            storedSignIn(it)
        }
        val signingIn = async { manager.signInIfSignedOut() }
        // Today's code skips the login altogether, so don't wait for it to start.
        signingIn.invokeOnCompletion { asked.complete(Unit) }
        asked.await()
        refresh.release(200)
        refreshing.await()
        proceed.complete(Unit)

        assertTrue(signingIn.await())
        assertEquals(listOf(LOGIN), backend.received(LOGIN).map { it.endpoint })
        assertTrue(manager.hasSessionAt(CURRENT))
    }

    @Test
    fun `an old login on its way cannot prevent the next automatic login at the new backend`() = runTest {
        prefs.announcementApiBaseUrlOverride = ISSUER
        val manager = manager()
        val held = backend.hold(LOGIN).apply {
            body = """{"access_token":"access-a-new","refresh_token":"refresh-a-new","expires_in":900,"device_id":"device-1"}"""
        }
        val oldLogin = async {
            manager.login(STUDENT, "fake-moodle-token", null, manager.session.signIns.current)
        }
        held.arrived.await()
        prefs.announcementApiBaseUrlOverride = CURRENT
        val signingIn = async { manager.signInIfSignedOut() }
        held.release(200)
        oldLogin.await()

        assertTrue(signingIn.await())
        assertEquals(listOf("127.0.0.1", "127.0.0.2"), backend.received(LOGIN).map { it.host })
        assertEquals("refresh-2", tokens.refreshToken)
        assertEquals("refresh-a-new", store.pending!!.session.refreshToken)
    }

    @Test
    fun `a failed login at the new backend leaves no header and keeps the old debt`() = runTest {
        val manager = manager()
        backend.answer(LOGIN, 503)

        assertNull(manager.authHeader())
        assertFalse(manager.signInIfSignedOut())

        assertEquals(listOf(LOGIN), backend.endpoints())
        assertNotNull(store.pending)
        assertTrue(manager.lastSignInFailed.value)
    }

    @Test
    fun `a relogin after the endpoint moved also owes the old backend its unregister`() = runTest {
        val manager = manager()

        assertTrue(manager.signInAgain(manager.session.signIns.current))

        assertEquals(listOf(LOGIN), backend.endpoints())
        assertEquals("refresh-a", store.pending!!.session.refreshToken)
        assertTrue(manager.hasSessionAt(CURRENT))
    }

    @Test
    fun `a refused refresh after the endpoint moved hands over the old session before relogin`() = runTest {
        prefs.announcementApiBaseUrlOverride = ISSUER
        tokens.save("access-a", "refresh-a", 0, ISSUER, STUDENT)
        val manager = manager()
        val held = backend.hold(REFRESH)
        val refreshing = async { manager.authHeader() }
        held.arrived.await()
        prefs.announcementApiBaseUrlOverride = CURRENT
        held.release(401)

        assertEquals("Bearer access-2", refreshing.await())

        assertEquals("refresh-a", store.pending!!.session.refreshToken)
        assertEquals("127.0.0.2", backend.received(LOGIN).single().host)
    }

    @Test
    fun `a departure the disk refuses keeps the pair and makes no login`() = runTest {
        val manager = manager()
        store.acceptSaves = false

        assertNull(manager.authHeader())
        assertFalse(manager.signInIfSignedOut())

        assertEquals("refresh-a", tokens.refreshToken)
        assertEquals(emptyList<String>(), backend.endpoints())
        store.acceptSaves = true
        assertTrue(manager.signInIfSignedOut())
        assertEquals("refresh-a", store.pending!!.session.refreshToken)
    }

    @Test
    fun `a current backend's pair makes no extra login or unregister`() = runTest {
        tokens.save("access-b", "refresh-b", Long.MAX_VALUE, CURRENT, STUDENT)
        val manager = manager()

        assertEquals("Bearer access-b", manager.authHeader())
        assertTrue(manager.signInIfSignedOut())
        assertTrue(manager.signInIfSignedOutNow())

        assertEquals(emptyList<String>(), backend.endpoints())
        assertNull(store.pending)
    }

    @Test
    fun `the school account and its sign-in stay current through the backend move`() = runTest {
        val manager = manager()
        val school = SignedInState(initial = true)
        manager.session.signedInFrom(school.signedIn)
        val signIn = manager.session.signIns.current

        assertEquals("Bearer access-2", manager.requireAuthHeader(signIn))

        assertTrue(school.signedIn.value)
        assertTrue(signIn.isCurrent)
        assertSame(signIn, manager.session.signIns.current)
    }

    @Test
    fun `a changed build default signs in there without an override`() = runTest {
        prefs.announcementApiBaseUrlOverride = null
        val current = resolveAnnouncementEndpoint(prefs)
        assertFalse(current.overrideApplied)
        val manager = manager()

        assertEquals("Bearer access-2", manager.authHeader())

        assertTrue(manager.hasSessionAt(current.url))
        assertEquals(current.url.toHttpUrl().host, backend.received(LOGIN).single().host)
        assertEquals("refresh-a", store.pending!!.session.refreshToken)
    }

    @Test
    fun `a rejected stored override signs in at the resolved default`() = runTest {
        prefs.announcementApiBaseUrlOverride = "http://old.example.test/v3"
        val current = resolveAnnouncementEndpoint(prefs)
        assertFalse(current.overrideApplied)
        val manager = manager()

        assertTrue(manager.signInIfSignedOut())

        assertTrue(manager.hasSessionAt(current.url))
        assertEquals(current.url.toHttpUrl().host, backend.received(LOGIN).single().host)
    }

    private class MemoryStore : PendingUnregisterStore {
        var pending: PendingUnregister? = null
        var acceptSaves = true
        override fun load() = pending
        override fun save(pending: PendingUnregister): Boolean = acceptSaves.also { if (it) this.pending = pending }
        override fun clear() { pending = null }
    }

    private companion object {
        const val ISSUER = "http://127.0.0.1:9/v3"
        const val CURRENT = "http://127.0.0.2:9/v3"
        const val STUDENT = "B00015999"
        const val LOGIN = "POST /auth/login"
        const val REFRESH = "POST /auth/refresh"
        const val REGISTER = "POST /devices/register"
        const val UNREGISTER = "DELETE /devices/device-1"
    }
}
