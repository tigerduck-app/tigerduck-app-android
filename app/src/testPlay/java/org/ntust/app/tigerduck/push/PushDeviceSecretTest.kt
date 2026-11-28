package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The device secret on the device's requests, against a backend that keeps its anonymous
 * registrations as 4.1.0 does (tigerduck-backend#233), as iOS's `PushDeviceSecretTests`
 * (tigerduck-app#953). Play only, as fdroid has no FCM token and never registers. Each [launch] is
 * a process: a new identity, token manager and service over the same stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushDeviceSecretTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    private val credentialPrefs = context.getSharedPreferences("credentials-secret-test", Context.MODE_PRIVATE)
    private val credentials = CredentialManager(credentialPrefs)
    private val backend = SecretBindingBackend()

    @Before
    fun setUp() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "made-up-android-id")
        prefs.hasCompletedOnboarding = true
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private class Launch(val identity: PushIdentity, val service: PushRegistrationService)

    /** A process start: the token manager reads the id first, as the app's does. */
    private fun TestScope.launch(): Launch {
        val identity = PushIdentity(prefs, context, credentials)
        val auth = AuthTokenManager(credentials.v3Tokens, backend.client, prefs, deviceUuid = identity.uuid())
        val api = PushApiClient(backend.client, prefs, auth)
        val service = PushRegistrationService(
            context = context,
            identity = identity,
            api = api,
            authTokenManager = auth,
            appPreferences = prefs,
            uiLanguage = UiLanguageMonitor(prefs),
            scope = backgroundScope,
            departingUnregister = DepartingDeviceUnregister(
                owed = OwedUnregister(credentials.pendingUnregister),
                deleteDevice = api::deleteDevice,
                refresh = auth::refreshDeparting,
                sessionAt = { _ -> auth.isLoggedIn },
                now = System::currentTimeMillis,
                endpoint = { resolveAnnouncementEndpoint(prefs).url },
            ),
        )
        return Launch(identity, service)
    }

    private suspend fun PushRegistrationService.register(token: String) {
        update(token)
        awaitRegistrations()
    }

    private fun signIn() = credentials.v3Tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)

    /** Uninstalled and installed again: every store the app keeps is gone, and the FCM token is new. */
    private fun reinstall() {
        prefs.clearAllPrefs()
        credentialPrefs.edit().clear().commit()
        context.getSharedPreferences("push_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        prefs.hasCompletedOnboarding = true
    }

    @Test
    fun `the announce and the account's registration carry the device secret`() = runTest {
        signIn()
        val app = launch()

        app.service.register("fcm-1")

        val secret = credentials.deviceSecret.load()
        assertEquals(43, secret?.length)
        val announce = backend.bodies(ANNOUNCE).single()
        assertEquals(app.identity.uuid(), announce.getString("device_id"))
        assertEquals(secret, announce.getString("device_secret"))
        val register = backend.bodies(REGISTER).single()
        assertEquals(app.identity.uuid(), register.getString("client_device_id"))
        assertEquals(secret, register.getString("device_secret"))
        assertTrue(app.service.diagnostic.value.isRegistered)
    }

    @Test
    fun `a signed-out push opt-out carries the device secret`() = runTest {
        val app = launch()
        app.service.register("fcm-1")

        assertTrue(app.service.updateServerPushOptOut(true))

        val sent = backend.bodies(ANNOUNCE).last()
        assertEquals(false, sent.getBoolean("server_push_enabled"))
        assertEquals(credentials.deviceSecret.load(), sent.getString("device_secret"))
    }

    @Test
    fun `a refused announce is recorded for the next launch, and an announce turned away otherwise is not`() = runTest {
        val app = launch()
        backend.answerNext(429, """{"error":"too_many_registration_attempts","message":"Try again later."}""")
        app.service.register("fcm-1")
        assertFalse(credentials.deviceSecret.refused)

        backend.answerNext(403, REFUSAL)
        app.service.register("fcm-2")

        assertTrue(credentials.deviceSecret.refused)
        assertNotNull(app.service.diagnostic.value.lastError)
    }

    @Test
    fun `an announce that goes through with the secret ends the refusal`() = runTest {
        credentials.deviceSecret.recordRefusal()
        signIn()
        val app = launch()

        app.service.register("fcm-1")

        assertFalse(credentials.deviceSecret.refused)
    }

    /**
     * The reinstall: the derived id comes back, but not the secret that bound its registration, so
     * the backend refuses the new one. The next launch with no session takes a new id, and its
     * announce registers the device again, with the push opt-out turned off in between.
     */
    @Test
    fun `a reinstall signed out is refused once, and the next launch registers the device again`() = runTest {
        launch().service.register("fcm-1")
        val installedId = backend.bodies(ANNOUNCE).single().getString("device_id")

        reinstall()
        val reinstalled = launch()
        reinstalled.service.register("fcm-2")
        assertEquals(installedId, reinstalled.identity.uuid())
        assertEquals(403, backend.answers.last())
        assertNotNull(reinstalled.service.diagnostic.value.lastError)
        assertTrue("kept for the next announce", reinstalled.service.updateServerPushOptOut(true))
        assertTrue(reinstalled.service.isServerPushOptedOut())

        val next = launch()
        next.service.register("fcm-2")

        assertNotEquals(installedId, next.identity.uuid())
        val announce = backend.bodies(ANNOUNCE).last()
        assertEquals(next.identity.uuid(), announce.getString("device_id"))
        assertEquals(false, announce.getBoolean("server_push_enabled"))
        assertEquals(200, backend.answers.last())
        assertTrue(backend.holds(next.identity.uuid(), "fcm-2"))
        assertNull(next.service.diagnostic.value.lastError)
        assertFalse(credentials.deviceSecret.refused)
    }

    /**
     * Signed in again on the first launch after a reinstall: the account's registration lands under
     * the id the session was issued for, and the id stays while the session does.
     */
    @Test
    fun `a reinstall signed in keeps its id while the session lasts, and the account's registration lands`() = runTest {
        launch().service.register("fcm-1")
        val installedId = backend.bodies(ANNOUNCE).single().getString("device_id")

        reinstall()
        signIn()
        val reinstalled = launch()
        reinstalled.service.register("fcm-2")
        val next = launch()

        assertTrue(credentials.deviceSecret.refused)
        assertTrue(reinstalled.service.diagnostic.value.isRegistered)
        assertEquals(installedId, backend.bodies(REGISTER).single().getString("client_device_id"))
        assertEquals(installedId, next.identity.uuid())
    }

    @Test
    fun `a refused signed-out opt-out while a sign-out's unregister is owed fails, and stays off`() = runTest {
        launch().service.register("fcm-1")
        reinstall()
        val app = launch()
        app.service.register("fcm-2")
        credentials.pendingUnregister.save(
            PendingUnregister(
                app.identity.uuid(),
                DepartingSession("access-1", "refresh-1", Long.MAX_VALUE, "https://api.example.test/v4"),
                1L,
            ),
        )

        assertFalse(app.service.updateServerPushOptOut(true))

        assertFalse(app.service.isServerPushOptedOut())
    }

    private companion object {
        const val ANNOUNCE = "/devices/anonymous"
        const val REGISTER = "/devices/register"
    }
}

/**
 * The backend's anonymous registrations as 4.1.0 keeps them (`register_anonymous_device`): a row
 * with a secret changes only with that secret; one without takes a secret from a request that names
 * the push token it stores, or when it has none; a refusal is 403 `device_secret_invalid` and writes
 * nothing. Every other request is answered 200, unless [answerNext] queued another answer.
 */
private class SecretBindingBackend {
    private class Row(var secret: String?, var token: String?)

    private val rows = HashMap<String, Row>()
    private val queued = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val received = CopyOnWriteArrayList<Pair<String, JSONObject>>()

    /** Each answer's status, in order. */
    val answers = CopyOnWriteArrayList<Int>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()

    fun answerNext(code: Int, body: String) {
        queued += code to body
    }

    fun bodies(path: String): List<JSONObject> = received.filter { it.first == path }.map { it.second }

    fun holds(deviceId: String, token: String): Boolean = synchronized(rows) { rows[deviceId]?.token == token }

    private fun answer(request: Request): Response {
        val path = "/" + request.url.encodedPath.substringAfter("/v4/")
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        received += path to json
        val (code, reply) = queued.poll() ?: when (path) {
            "/devices/anonymous" -> if (anonymous(json)) 200 to """{"registered":true}""" else 403 to REFUSAL
            else -> 200 to """{"device_id":"server-device","push_token_id":1}"""
        }
        answers += code
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun anonymous(request: JSONObject): Boolean = synchronized(rows) {
        val id = request.getString("device_id")
        val secret = request.optString("device_secret").ifEmpty { null }
        val token = request.optString("push_token").ifEmpty { null }
        val row = rows[id] ?: return Row(secret, token).let { rows[id] = it; true }
        val allowed = when {
            row.secret != null -> secret == row.secret
            secret != null && row.token == null -> true.also { row.secret = secret }
            secret != null && token != null -> (token == row.token).also { if (it) row.secret = secret }
            else -> true
        }
        if (allowed && token != null) row.token = token
        allowed
    }
}

/** The backend's refusal of a device secret, in `/v4`'s error envelope. */
private const val REFUSAL =
    """{"error":"device_secret_invalid","message":"The device secret is missing or does not match."}"""
