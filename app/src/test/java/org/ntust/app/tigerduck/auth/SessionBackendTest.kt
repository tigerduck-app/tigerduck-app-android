package org.ntust.app.tigerduck.auth

import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.DeviceRegisterRequest
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushTokenIn
import org.ntust.app.tigerduck.push.SettingsDocumentApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A backend session goes only to the backend that issued it: neither its access token nor its
 * refresh token reaches the one the API endpoint setting names since, whatever a request read
 * first. The setting's own change signs out before it stores (EndpointChange); this holds for a
 * request already on its way then, as for one queued behind another.
 */
class SessionBackendTest {

    private lateinit var prefs: AppPreferences
    private lateinit var tokens: V3TokenStore
    private var clock = 1_800_000_000_000L
    private val backend = ScriptedBackend()

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = ISSUER }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-session-backend", Context.MODE_PRIVATE))
        tokens.clear()
    }

    private fun manager() = AuthTokenManager(tokens, backend.client, prefs, "device-1", now = { clock }).apply {
        signInWithStoredAccount = { SignInAttempt.NOTHING_TO_SEND }
    }

    private suspend fun signedInAtTheIssuer(): AuthTokenManager = manager().also {
        it.login("B00015999", moodleToken = "moodle", moodlePrivateToken = null)
    }

    @Test
    fun `no client presents a session to another backend than the one that issued it`() = runTest {
        val manager = signedInAtTheIssuer()
        prefs.announcementApiBaseUrlOverride = ELSEWHERE
        val requests: List<suspend () -> Unit> = listOf(
            { PushApiClient(backend.client, prefs, manager).register(DeviceRegisterRequest(clientDeviceId = "device-1", pushToken = PushTokenIn(tokenValue = "fcm-1"))) },
            { BulletinApiClient(backend.client, prefs, manager).fetchSubscriptions() },
            { SettingsDocumentApiClient(backend.client, prefs, manager).read<JsonObject>("notification") },
            { SyncApiClient(manager, prefs, backend.client).fetchFullSync() },
        )

        val failures = requests.map { request -> runCatching { request() }.exceptionOrNull() }

        assertTrue("got $failures", failures.all { it is IOException })
        assertEquals(emptyList<String>(), backend.endpoints().filter { it != LOGIN })
    }

    @Test
    fun `the refresh goes to the backend that issued the session`() = runTest {
        val manager = signedInAtTheIssuer()
        prefs.announcementApiBaseUrlOverride = ELSEWHERE
        clock += 3_600_000

        manager.authHeader()

        assertEquals(listOf("127.0.0.1"), backend.received(REFRESH).map { it.host })
    }

    @Test
    fun `an access token goes only to the backend that issued it, also after a sign-out`() = runTest {
        val manager = signedInAtTheIssuer()
        val header = manager.requireAuthHeader().removePrefix("Bearer ")

        assertTrue(manager.mayPresent(header, "$ISSUER/sync/full".toHttpUrl()))
        assertFalse(manager.mayPresent(header, "$ELSEWHERE/sync/full".toHttpUrl()))

        // A request built before the sign-out, still on its way.
        manager.logout()
        assertTrue(manager.mayPresent(header, "$ISSUER/sync/full".toHttpUrl()))
        assertFalse(manager.mayPresent(header, "$ELSEWHERE/sync/full".toHttpUrl()))
    }

    @Test
    fun `a token a refresh handed out goes to its backend also after a sign-out`() = runTest {
        val manager = signedInAtTheIssuer()
        clock += 3_600_000
        backend.answer(REFRESH, 200, """{"access_token":"access-3","refresh_token":"refresh-3","expires_in":900}""")
        val header = manager.requireAuthHeader().removePrefix("Bearer ")
        assertEquals("access-3", header)

        manager.logout()

        assertTrue(manager.mayPresent(header, "$ISSUER/sync/full".toHttpUrl()))
    }

    @Test
    fun `a token this phone never held goes nowhere`() = runTest {
        val manager = signedInAtTheIssuer()

        assertFalse(manager.mayPresent("someone-else", "$ISSUER/sync/full".toHttpUrl()))
    }

    @Test
    fun `an address that only starts like the issuer's is another backend`() = runTest {
        val manager = signedInAtTheIssuer()
        val header = manager.requireAuthHeader().removePrefix("Bearer ")

        assertFalse(manager.mayPresent(header, "http://127.0.0.1:9/v3x/sync/full".toHttpUrl()))
        assertFalse(manager.mayPresent(header, "http://127.0.0.1:99/v3/sync/full".toHttpUrl()))
    }

    @Test
    fun `the guard checks a request first, and again at every hop a redirect takes`() {
        // Within one origin OkHttp keeps the header on a redirect, past the first check.
        val client = OkHttpClient.Builder().addInterceptor { it.proceed(it.request()) }.guardedBy(manager()).build()

        assertTrue(client.interceptors.first() is SessionHostGuard)
        assertTrue(client.networkInterceptors.any { it is SessionHostGuard })
    }

    private companion object {
        const val ISSUER = "http://127.0.0.1:9/v3"
        const val ELSEWHERE = "http://127.0.0.2:9/v3"
        const val LOGIN = "POST /auth/login"
        const val REFRESH = "POST /auth/refresh"
    }
}
