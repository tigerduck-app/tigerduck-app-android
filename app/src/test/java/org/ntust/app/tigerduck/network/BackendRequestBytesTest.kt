package org.ntust.app.tigerduck.network

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.PendingUnregisterPrefs
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.DeviceRegisterRequest
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.push.currentDeviceModel
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Today's backend requests, for each way the API endpoint setting can be spelled: where each one
 * goes, the headers it is built with, and its body. Phase 3 moves how a base is resolved and how two
 * are compared (`ApiBase`), and none of this changed with that, but where each goes since the move
 * to `/v4` (T3.31): a base ending in `/v3` is spoken to at `/v4`, the same backend, with the same
 * headers and bodies.
 *
 * The backend here answers as the first interceptor of the client each request goes through, so it
 * sees a request as its client built it: the headers a client's own interceptors add after that,
 * such as `Accept`, are not in it.
 */
class BackendRequestBytesTest {

    private class Sent(val line: String, val authorization: String?, val contentType: String?, val body: String?)

    /** Answers every request in place of the backend, and keeps each one as it arrived. */
    private class RecordingBackend {
        val sent = CopyOnWriteArrayList<Sent>()
        private var pairs = 1

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()

        private fun answer(request: Request): Response {
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
            sent += Sent("${request.method} ${request.url}", request.header("Authorization"), request.body?.contentType()?.toString(), body)
            val path = request.url.encodedPath
            val (code, reply) = when {
                path.endsWith("/auth/login") || path.endsWith("/auth/refresh") -> synchronized(this) {
                    val n = ++pairs
                    200 to """{"access_token":"access-$n","refresh_token":"refresh-$n","expires_in":900,"device_id":"device-1"}"""
                }
                request.method == "DELETE" -> 204 to ""
                path.endsWith("/sync/revision") -> 200 to """{"revision":7}"""
                else -> 200 to """{"device_id":"device-1","push_token_id":1}"""
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

    private var clock = 1_800_000_000_000L

    @Test
    fun `the endpoint setting resolves to the same base in each spelling`() {
        // The default ends in /v3, as every build's does, and moves with the rest.
        val default = BuildConfig.PUSH_BASE_URL.trimEnd('/').toHttpUrl().toString().trimEnd('/')
            .let { if (it.endsWith("/v3")) it.dropLast(2) + "v4" else it }
        val cases = listOf(
            null to (default to false),
            "https://api.example.test/v3" to ("https://api.example.test/v4" to true),
            "https://API.Example.TEST:443/v3/" to ("https://api.example.test/v4" to true),
            "https://api.example.test/prefix/v3" to ("https://api.example.test/prefix/v4" to true),
            "https://api.example.test/v4" to ("https://api.example.test/v4" to true),
            "https://api.example.test" to ("https://api.example.test" to true),
            "https://api.example.test:8443/v3" to ("https://api.example.test:8443/v4" to true),
            // Kept as given: it does not end in /v3 (ApiBase.of).
            "https://api.example.test/v3?x=1" to ("https://api.example.test/v3?x=1" to true),
            "http://192.168.1.5:40000/v3" to ("http://192.168.1.5:40000/v4" to true),
            "https://v3" to ("https://v3" to true),
            // Plain HTTP to a routable host is refused, and the default is used instead.
            "http://api.example.test/v3" to (default to false),
        )

        cases.forEach { (setting, expected) ->
            val resolved = resolveAnnouncementEndpoint(setting)
            assertEquals("the setting $setting", expected, resolved.url to resolved.overrideApplied)
        }
    }

    @Test
    fun `each backend request goes to the base the setting names, as it always has`() = runTest {
        val bases = listOf(
            null,
            "https://api.example.test/v3",
            "https://API.Example.TEST:443/v3/",
            "https://api.example.test/prefix/v3",
            "https://api.example.test/v4",
            "https://api.example.test",
            "http://192.168.1.5:40000/v3",
        )
        bases.forEach { setting ->
            val base = resolveAnnouncementEndpoint(setting).url
            assertEquals("the setting $setting", transcript(base), session(setting))
        }
    }

    /**
     * A sign-in, a refresh, a read and a write of the session, and the sign-out's unregister, which
     * refreshes the departing session first: what each sends, in order.
     */
    private suspend fun session(setting: String?): List<String> {
        val backend = RecordingBackend()
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = setting }
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens", Context.MODE_PRIVATE))
        val owed = OwedUnregister(PendingUnregisterPrefs(context.getSharedPreferences("credentials", Context.MODE_PRIVATE)))
        val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1", now = { clock }, owedUnregister = owed)
        manager.signInWithStoredAccount = { SignInAttempt.NOTHING_TO_SEND }
        val push = PushApiClient(backend.client, prefs, manager)
        val sync = SyncApiClient(manager, prefs, backend.client)
        val unregister = unregister(owed, push, manager, prefs)
        val signIn = manager.session.signIns.current

        manager.login(STUDENT, moodleToken = "moodle-token", moodlePrivateToken = null, signIn = signIn)
        clock += 3_600_000
        assertEquals(7L, sync.fetchRevision(signIn))
        push.register(DeviceRegisterRequest(clientDeviceId = "device-1"), signIn)
        unregister.owe("device-1", manager.logout()!!)
        clock += 3_600_000
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister.send())

        return backend.sent.flatMap { listOf(it.line, "  authorization: ${it.authorization}", "  type: ${it.contentType}", "  body: ${body(it)}") }
    }

    private fun transcript(base: String): List<String> = listOf(
        "POST $base/auth/login", "  authorization: null", "  type: $JSON", "  body: ${loginBody()}",
        "POST $base/auth/refresh", "  authorization: null", "  type: $JSON", "  body: {\"refresh_token\":\"refresh-2\"}",
        "GET $base/sync/revision", "  authorization: Bearer access-3", "  type: null", "  body: null",
        "POST $base/devices/register", "  authorization: Bearer access-3", "  type: $JSON",
        "  body: {\"client_device_id\":\"device-1\",\"platform\":\"android\"}",
        "POST $base/auth/refresh", "  authorization: null", "  type: $JSON", "  body: {\"refresh_token\":\"refresh-3\"}",
        "DELETE $base/devices/device-1", "  authorization: Bearer access-4", "  type: null", "  body: ",
    )

    // The sign-in's body is checked by its content, as org.json orders its keys by hash: the same
    // fields, the same values, whatever order this JVM writes them in.
    private fun body(sent: Sent): String? =
        if (sent.line.endsWith("/auth/login") && JSONObject(sent.body!!).asMap() == JSONObject(loginBody()).asMap()) loginBody() else sent.body

    private fun JSONObject.asMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key -> get(key).let { if (it is JSONObject) it.asMap() else it } }

    private fun loginBody(): String = JSONObject().apply {
        put("student_id", STUDENT)
        put("moodle_token", "moodle-token")
        put("device_info", JSONObject().apply {
            put("client_device_id", "device-1")
            put("platform", "android")
            put("app_version", BuildConfig.VERSION_NAME)
            put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
            put("device_model", currentDeviceModel())
        })
    }.toString()

    @Test
    fun `a refresh goes to the backend a pair was stored with, in the spelling it was stored in`() = runTest {
        val sent = refreshOfStoredPair(issuedBy = "https://API.Example.TEST:443/v3/")

        assertEquals(listOf("POST https://api.example.test/v4/auth/refresh"), sent)
    }

    @Test
    fun `a refresh of a pair stored before its backend was recorded goes to the endpoint now`() = runTest {
        val sent = refreshOfStoredPair(issuedBy = null)

        assertEquals(listOf("POST https://other.example.test/prefix/v4/auth/refresh"), sent)
    }

    private suspend fun refreshOfStoredPair(issuedBy: String?): List<String> {
        val backend = RecordingBackend()
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://other.example.test/prefix/v3" }
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens", Context.MODE_PRIVATE))
        tokens.save("access-1", "refresh-1", clock - 1, issuedBy = issuedBy, studentId = STUDENT)
        val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1", now = { clock })
        manager.signInWithStoredAccount = { SignInAttempt.NOTHING_TO_SEND }

        manager.requireAuthHeader(manager.session.signIns.current)

        return backend.sent.map { it.line }
    }

    @Test
    fun `an unregister an older build owes goes to the backend it was stored with`() = runTest {
        val backend = RecordingBackend()
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://other.example.test/v3" }
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens", Context.MODE_PRIVATE))
        val store = PendingUnregisterPrefs(context.getSharedPreferences("credentials", Context.MODE_PRIVATE))
        val owed = OwedUnregister(store)
        val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1", now = { clock }, owedUnregister = owed)
        val stored = "https://API.Example.TEST/prefix/v3"
        assertTrue(store.save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", clock - 1, stored, STUDENT), clock)))

        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister(owed, PushApiClient(backend.client, prefs, manager), manager, prefs).send())

        assertEquals(
            listOf("POST https://api.example.test/prefix/v4/auth/refresh", "DELETE https://api.example.test/prefix/v4/devices/device-1"),
            backend.sent.map { it.line },
        )
    }

    /** The unregister as its injected constructor wires it, with this test's clock. */
    private fun unregister(owed: OwedUnregister, push: PushApiClient, manager: AuthTokenManager, prefs: AppPreferences) =
        DepartingDeviceUnregister(
            owed = owed,
            deleteDevice = push::deleteDevice,
            refresh = manager::refreshDeparting,
            sessionAt = manager::hasSessionAt,
            now = { clock },
            endpoint = { resolveAnnouncementEndpoint(prefs).url },
        )

    private companion object {
        const val STUDENT = "B00015999"
        const val JSON = "application/json; charset=utf-8"
    }
}
