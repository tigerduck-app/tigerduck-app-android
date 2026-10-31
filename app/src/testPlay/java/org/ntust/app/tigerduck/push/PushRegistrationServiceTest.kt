package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.PendingUnregisterPrefs
import org.ntust.app.tigerduck.data.preferences.clearAllButOwedUnregister
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint

/**
 * [PushRegistrationService] registering the device again after a registration failed:
 * https://github.com/tigerduck-app/tigerduck-app-android/issues/194. Play only, as fdroid has
 * no FCM token and never registers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushRegistrationServiceTest {

    private lateinit var context: Application
    private lateinit var prefs: AppPreferences
    private lateinit var tokens: V3TokenStore
    private val backend = FakeBackend()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context)
        prefs.hasCompletedOnboarding = true
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /** A session whose access token needs no refresh. */
    private fun signIn() {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
    }

    /** With [signsIn], a request that finds no session signs in as AuthService's sign-in does. */
    private fun TestScope.service(
        signedIn: Boolean = true,
        signsIn: Boolean = false,
        owedStore: PendingUnregisterStore = owed,
    ): PushRegistrationService {
        if (signedIn) signIn()
        val auth = AuthTokenManager(tokens, backend.client, prefs, deviceUuid = "device-1")
        if (signsIn) {
            auth.signInWithStoredAccount = { signIn ->
                runCatching {
                    auth.login("B00000000", "moodle", moodlePrivateToken = null, signInGeneration = signIn)
                }.fold(onSuccess = { SignInAttempt.SIGNED_IN }, onFailure = { SignInAttempt.FAILED })
            }
        }
        return PushRegistrationService(
            context = context,
            identity = PushIdentity(prefs, context),
            api = PushApiClient(backend.client, prefs, auth),
            authTokenManager = auth,
            appPreferences = prefs,
            uiLanguage = UiLanguageMonitor(prefs),
            scope = backgroundScope,
            fixtures = DebugFixtureStore(context),
            departingUnregister = DepartingDeviceUnregister(
                owed = OwedUnregister(owedStore),
                deleteDevice = PushApiClient(backend.client, prefs, auth)::deleteDevice,
                refresh = auth::refreshDeparting,
                sessionAt = { _ -> auth.isLoggedIn },
                now = System::currentTimeMillis,
                endpoint = { resolveAnnouncementEndpoint(prefs).url },
            ),
        )
    }

    /** The unregister a sign-out owes, kept in memory. */
    private val owed = object : PendingUnregisterStore {
        var pending: PendingUnregister? = null
        override fun load() = pending
        override fun save(pending: PendingUnregister): Boolean {
            this.pending = pending
            return true
        }
        override fun clear() {
            pending = null
        }
    }

    /** Registers "fcm-1" while the backend refuses it the way it refuses a request with no session. */
    private suspend fun PushRegistrationService.failRegistration() {
        backend.answer(REGISTER, 401)
        update("fcm-1")
        awaitRegistrations()
        assertNotNull("the registration failed", diagnostic.value.lastError)
    }

    // Turning TigerSync on

    @Test
    fun `turning TigerSync on after a failed registration registers again and clears the error`() = runTest {
        val service = service()
        service.failRegistration()

        service.updateCloudSyncEnabled(true)
        service.awaitRegistrations()

        assertEquals(2, backend.received(REGISTER).size)
        assertNull(service.diagnostic.value.lastError)
        assertTrue(service.diagnostic.value.isRegistered)
    }

    @Test
    fun `turning TigerSync on registers again when the server refuses the switch, and still reports it`() = runTest {
        val service = service()
        service.failRegistration()
        // A device the server holds no row for: only a registration adds one.
        backend.answer(PREFERENCES, 404)

        val refused = runCatching { service.updateCloudSyncEnabled(true) }.exceptionOrNull()
        service.awaitRegistrations()

        assertTrue("pushCloudSyncEnabled records the switch only on success", refused is PushApiException)
        assertEquals(2, backend.received(REGISTER).size)
        assertNull(service.diagnostic.value.lastError)
    }

    @Test
    fun `turning TigerSync on while a registration with it off is on the wire registers it on`() = runTest {
        prefs.cloudSyncEnabled = false
        val service = service()
        service.failRegistration()
        val held = backend.hold(REGISTER)
        service.onSignedIn()
        held.arrived.await()

        // What the TigerSync switch does: the preference first, then the PATCH, which finds no row.
        prefs.cloudSyncEnabled = true
        backend.answer(PREFERENCES, 404)
        runCatching { service.updateCloudSyncEnabled(true) }
        held.release(200)
        service.awaitRegistrations()

        val sent = backend.received(REGISTER).map { JSONObject(it.body).getBoolean("cloud_sync_enabled") }
        assertEquals(listOf(false, false, true), sent)
    }

    @Test
    fun `turning TigerSync on after a registration went through sends no second one`() = runTest {
        val service = service()
        service.update("fcm-1")
        service.awaitRegistrations()

        service.updateCloudSyncEnabled(true)
        service.awaitRegistrations()

        assertEquals(1, backend.received(REGISTER).size)
    }

    @Test
    fun `turning TigerSync on starts the automatic retry's backoff over`() = runTest {
        val service = service()
        service.failRegistration()
        // Two more failures put the next automatic retry half an hour out.
        backend.answer(REGISTER, 401, 401)
        repeat(2) {
            service.onSignedIn()
            service.awaitRegistrations()
        }

        // The registration this makes fails as well.
        backend.answer(REGISTER, 401)
        service.updateCloudSyncEnabled(true)
        service.awaitRegistrations()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        service.retryRegistrationIfDue { "fcm-1" }
        service.awaitRegistrations()

        assertEquals("after one failure the retry is due in 30 s", 5, backend.received(REGISTER).size)
    }

    // A registration asked for while one is running

    @Test
    fun `a registration asked for while one is on the wire is not sent alongside it`() = runTest {
        val service = service()
        val held = backend.hold(REGISTER)
        service.update("fcm-1")
        held.arrived.await()

        service.onSignedIn()
        held.release(200)
        service.awaitRegistrations()

        assertEquals(1, backend.received(ANNOUNCE).size)
        assertEquals(1, backend.received(REGISTER).size)
        assertNull(service.diagnostic.value.lastError)
    }

    @Test
    fun `a registration that fails after another was asked for is followed at once`() = runTest {
        val service = service()
        val held = backend.hold(REGISTER)
        service.update("fcm-1")
        held.arrived.await()

        service.onSignedIn()
        held.release(401)
        service.awaitRegistrations()

        assertEquals(2, backend.received(REGISTER).size)
        assertNull(service.diagnostic.value.lastError)
        assertTrue(service.diagnostic.value.isRegistered)
    }

    @Test
    fun `a token that arrives while a registration is on the wire is registered after it`() = runTest {
        val service = service()
        val held = backend.hold(REGISTER)
        service.update("fcm-1")
        held.arrived.await()

        service.update("fcm-2")
        held.release(200)
        service.awaitRegistrations()

        val sent = backend.received(REGISTER).map {
            JSONObject(it.body).getJSONObject("push_token").getString("token_value")
        }
        assertEquals(listOf("fcm-1", "fcm-2"), sent)
    }

    @Test
    fun `a sign-in while a signed-out registration is on the wire registers the account once`() = runTest {
        val service = service(signedIn = false)
        val held = backend.hold(ANNOUNCE)
        service.update("fcm-1")
        held.arrived.await()

        signIn()
        service.onSignedIn()
        held.release(200)
        service.awaitRegistrations()

        // The pass reads the session after its announce, so it registers the account itself.
        val registrations = backend.received(REGISTER)
        assertEquals(1, registrations.size)
        assertEquals("Bearer access-1", registrations.single().authorization)
        assertTrue(service.diagnostic.value.isRegistered)
    }

    // A registration that finds no session

    @Test
    fun `a registration that finds no session signs in first and registers the account`() = runTest {
        val service = service(signedIn = false, signsIn = true)

        service.update("fcm-1")
        service.awaitRegistrations()

        assertEquals(1, backend.received(LOGIN).size)
        assertEquals(listOf<String?>(null), backend.received(ANNOUNCE).map { it.authorization })
        assertEquals("Bearer access-2", backend.received(REGISTER).single().authorization)
        assertTrue(service.diagnostic.value.isRegistered)
    }

    @Test
    fun `turning server push off with no session signs in and sends it on the account's row`() = runTest {
        val service = service(signedIn = false, signsIn = true)

        assertTrue(service.updateServerPushOptOut(true))

        assertEquals(1, backend.received(LOGIN).size)
        val patch = backend.received(PREFERENCES).single()
        assertEquals("Bearer access-2", patch.authorization)
        assertEquals(false, JSONObject(patch.body).getBoolean("server_push_enabled"))
        assertEquals(emptyList<FakeBackend.Received>(), backend.received(ANNOUNCE))
    }

    // The sign-out's unregister

    private fun departing() = DepartingSession(
        "access-1",
        "refresh-1",
        accessExpiresAt = Long.MAX_VALUE,
        baseUrl = resolveAnnouncementEndpoint(prefs).url.trimEnd('/'),
    )

    private fun deleteEndpoint() = "DELETE /v3/devices/" + PushIdentity(prefs, context).uuid()

    private fun deletes() = backend.received(deleteEndpoint())

    /** The unregister's request runs on the I/O dispatcher, out of the test scheduler's reach. */
    private suspend fun awaitUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private fun unregisterWork(): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DepartingUnregisterWorker.UNIQUE_NAME).get().map { it.state }

    @Test
    fun `a sign-out's unregister the server cannot take yet is kept and queued for later`() = runTest {
        val service = service(signedIn = false)
        backend.answer(deleteEndpoint(), 503)

        service.unregister(departing())
        awaitUntil { deletes().isNotEmpty() }

        assertEquals(listOf("Bearer access-1"), deletes().map { it.authorization })
        assertEquals("access-1", owed.pending?.session?.accessToken)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), unregisterWork())
    }

    @Test
    fun `a sign-out's unregister the server takes leaves nothing owed`() = runTest {
        val service = service(signedIn = false)

        service.unregister(departing())
        awaitUntil { owed.pending == null && deletes().isNotEmpty() }

        assertEquals(1, deletes().size)
        assertNull(owed.pending)
    }

    @Test
    fun `a full reset after an unregister that failed keeps it owed and queued`() = runTest {
        // The credential store the reset wipes, plainly: Robolectric has no keystore.
        val credentials = context.getSharedPreferences("credentials-test", Context.MODE_PRIVATE)
        val store = PendingUnregisterPrefs(credentials)
        val service = service(signedIn = false, owedStore = store)
        backend.answer(deleteEndpoint(), 503)

        service.unregister(departing())
        awaitUntil { deletes().isNotEmpty() && unregisterWork().isNotEmpty() }
        // What the full reset runs after the sign-out: the sign-out's effects stop the
        // background sync, and CredentialManager.clearAll() wipes the credentials.
        BackgroundSyncWorker.cancel(context)
        clearAllButOwedUnregister(credentials)

        assertEquals("access-1", store.load()?.session?.accessToken)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), unregisterWork())
    }

    // Signing in again after a sign-out, in the same process

    private fun registrations() = backend.received(REGISTER)

    @Test
    fun `signing in again after a sign-out registers the device at once`() = runTest {
        val service = service()
        service.update("fcm-1")
        service.awaitRegistrations()
        assertEquals(1, registrations().size)

        // The sign-out: the session goes, then the unregister.
        tokens.clear()
        service.unregister(departing())
        awaitUntil { deletes().isNotEmpty() && owed.pending == null }
        testScheduler.advanceUntilIdle()
        // The next sign-in: AuthService stores the session, then asks for a registration.
        signIn()
        service.onSignedIn()
        service.awaitRegistrations()

        assertEquals(2, registrations().size)
        assertEquals("fcm-1", JSONObject(registrations().last().body).getJSONObject("push_token").getString("token_value"))
        assertTrue(service.diagnostic.value.isRegistered)
    }

    @Test
    fun `a sign-in during the sign-out's unregister registers the device once the DELETE is done`() = runTest {
        val service = service()
        service.update("fcm-1")
        service.awaitRegistrations()
        tokens.clear()
        val held = backend.hold(deleteEndpoint())

        service.unregister(departing())
        held.arrived.await()
        signIn()
        service.onSignedIn()
        testScheduler.advanceUntilIdle()
        // Not while the DELETE is out: it would delete the row the registration adds.
        assertEquals(1, registrations().size)

        held.release(204)
        awaitUntil { owed.pending == null }
        testScheduler.advanceUntilIdle()
        service.awaitRegistrations()

        assertEquals(2, registrations().size)
        assertTrue(service.diagnostic.value.isRegistered)
    }

    @Test
    fun `a sign-out with no session owes nothing`() = runTest {
        val service = service(signedIn = false)

        service.unregister(null)
        testScheduler.advanceUntilIdle()

        assertEquals(0, deletes().size)
        assertEquals(emptyList<WorkInfo.State>(), unregisterWork())
    }

    private companion object {
        const val LOGIN = "POST /auth/login"
        const val ANNOUNCE = "POST /devices/anonymous"
        const val REGISTER = "POST /devices/register"
        const val PREFERENCES = "PATCH /devices/preferences"
    }
}

/**
 * Answers every request in place of the backend, which nothing reaches: the interceptor never
 * calls proceed(). Each endpoint answers 200 unless a test queued another status for it, and a
 * [Held] answer keeps its request waiting until the test releases it.
 */
private class FakeBackend {
    class Received(val endpoint: String, val body: String, val authorization: String?)

    class Held {
        val arrived = CompletableDeferred<Unit>()
        private val reply = CompletableFuture<Int>()

        fun release(code: Int) {
            reply.complete(code)
        }

        fun await(): Int = reply.get(10, TimeUnit.SECONDS)
    }

    private val received = CopyOnWriteArrayList<Received>()
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedQueue<Any>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    fun answer(endpoint: String, vararg codes: Int) {
        codes.forEach { queue(endpoint).add(it) }
    }

    fun hold(endpoint: String): Held = Held().also { queue(endpoint).add(it) }

    fun received(endpoint: String): List<Received> = received.filter { it.endpoint == endpoint }

    private fun queue(endpoint: String) = queued.getOrPut(endpoint) { ConcurrentLinkedQueue() }

    private fun answer(request: Request): Response {
        val path = request.url.encodedPath
        val endpoint = request.method + " " + when {
            path.endsWith("/devices/anonymous") -> "/devices/anonymous"
            path.endsWith("/devices/register") -> "/devices/register"
            path.endsWith("/auth/login") -> "/auth/login"
            // Its path holds the install's device id.
            path.endsWith("/preferences") -> "/devices/preferences"
            else -> path
        }
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        received += Received(endpoint, body, request.header("Authorization"))
        val code = when (val next = queued[endpoint]?.poll()) {
            is Held -> {
                next.arrived.complete(Unit)
                next.await()
            }
            is Int -> next
            else -> 200
        }
        val reply = when {
            code !in 200..299 -> """{"detail":"refused"}"""
            endpoint == "POST /auth/login" ->
                """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":900,"device_id":"device-1"}"""
            else -> """{"device_id":"device-1","push_token_id":1,"cloud_sync_enabled":true}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
