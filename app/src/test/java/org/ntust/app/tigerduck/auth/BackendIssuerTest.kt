package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.backend.ApiBase
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A token pair `/v3` issued works on `/v4` of the same backend and back (`docs/api-v4.md`, "Auth
 * and refresh"), so the session guard, the session's backend and the unregister a sign-out owes
 * compare backends without the version segment: an update that moves the app to `/v4` signs
 * nobody out, and a debt owed to `/v3` is still the same backend's (Review Focus 2).
 */
class BackendIssuerTest {

    private val backend = ScriptedBackend()
    private val context = JvmContext()

    /** A session [issuer] issued, as a sign-in there stores it, and its access token as handed out. */
    private suspend fun signedInAt(issuer: String): Pair<AuthTokenManager, String> {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = issuer }
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-$issuer", Context.MODE_PRIVATE))
        tokens.save("access-1", "refresh-1", Long.MAX_VALUE, issuedBy = issuer, studentId = STUDENT)
        val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1").apply {
            signInWithStoredAccount = { SignInAttempt.NOTHING_TO_SEND }
        }
        return manager to manager.requireAuthHeader(manager.session.signIns.current).removePrefix("Bearer ")
    }

    private fun AuthTokenManager.answers(token: String, urls: List<String>): Map<String, Boolean> =
        urls.associateWith { mayPresent(token, it.toHttpUrl()) }

    @Test
    fun `a pair issued by v3 may be presented to v4 of the same host, and to nothing else`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test/v3")
        val accepted = listOf(
            "https://api.example.test/v4",
            "https://api.example.test/v4/sync/full",
            "https://api.example.test/v4/devices/device-1?x=1",
            "https://api.example.test/v3/sync/full",
        )
        val refused = listOf(
            "https://api.example.test/v4x/sync/full",
            "https://api.example.test/v5/sync/full",
            "https://api.example.test/prefix/v4/sync/full",
            "https://api.example.test/sync/full",
            "https://other.example.test/v4/sync/full",
            "https://api.example.test:8443/v4/sync/full",
            "http://api.example.test/v4/sync/full",
        )

        assertEquals(accepted.associateWith { true }, manager.answers(token, accepted))
        assertEquals(refused.associateWith { false }, manager.answers(token, refused))
    }

    @Test
    fun `a pair issued under a prefix may be presented to that prefix's other version only`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test/prefix/v3")

        assertEquals(
            mapOf(
                "https://api.example.test/prefix/v4/sync/full" to true,
                "https://api.example.test/v4/sync/full" to false,
                "https://api.example.test/other/v4/sync/full" to false,
            ),
            manager.answers(
                token,
                listOf(
                    "https://api.example.test/prefix/v4/sync/full",
                    "https://api.example.test/v4/sync/full",
                    "https://api.example.test/other/v4/sync/full",
                ),
            ),
        )
    }

    @Test
    fun `a pair issued by v4 may be presented to v3 of the same host`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test/v4")

        assertEquals(true, manager.mayPresent(token, "https://api.example.test/v3/sync/full".toHttpUrl()))
        assertEquals(false, manager.mayPresent(token, "https://other.example.test/v3/sync/full".toHttpUrl()))
    }

    @Test
    fun `hasSessionAt v4 is true for a pair from v3, and the other way round`() = runTest {
        val (fromV3, _) = signedInAt("https://api.example.test/v3")
        val (fromV4, _) = signedInAt("https://api.example.test/v4")

        assertEquals(true, fromV3.hasSessionAt("https://api.example.test/v4"))
        assertEquals(true, fromV3.hasSessionAt("https://API.Example.TEST:443/v4/"))
        assertEquals(false, fromV3.hasSessionAt("https://other.example.test/v4"))
        assertEquals(false, fromV3.hasSessionAt("https://api.example.test/prefix/v4"))
        assertEquals(true, fromV4.hasSessionAt("https://api.example.test/v3"))
    }

    private class MemoryStore(var pending: PendingUnregister?) : PendingUnregisterStore {
        override fun load() = pending
        override fun save(pending: PendingUnregister): Boolean = true.also { this.pending = pending }
        override fun clear() {
            pending = null
        }
    }

    private fun debtTo(base: String) =
        PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", Long.MAX_VALUE, base, STUDENT), owedAt = 0)

    @Test
    fun `a sign-in at v4 ends a debt owed to v3 of the same backend`() = runTest {
        val sameBackend = MemoryStore(debtTo("https://api.example.test/v3"))
        val otherBackend = MemoryStore(debtTo("https://other.example.test/v3"))

        listOf(sameBackend, otherBackend).forEach { store ->
            OwedUnregister(store).signingIn(STUDENT, "https://api.example.test/v4") { Unit }
        }

        assertNull(sameBackend.pending)
        assertNotNull(otherBackend.pending)
    }

    @Test
    fun `an owed unregister stored for v3 is not sent once a session from v4 stands`() = runTest {
        val (manager, _) = signedInAt("https://api.example.test/v4")
        val sent = mutableListOf<String>()
        val store = MemoryStore(debtTo("https://api.example.test/v3"))

        val outcome = unregister(store, sent, endpoint = "https://api.example.test/v4", sessionAt = manager::hasSessionAt).send()

        assertEquals(DepartingDeviceUnregister.Outcome.DONE, outcome)
        assertEquals(emptyList<String>(), sent)
        assertNull(store.pending)
    }

    @Test
    fun `an owed unregister to a plain-HTTP v3 goes while the setting names its v4`() = runTest {
        val sent = mutableListOf<String>()
        val store = MemoryStore(debtTo("http://192.168.1.5:40000/v3"))

        val outcome = unregister(store, sent, endpoint = "http://192.168.1.5:40000/v4").send()

        assertEquals(DepartingDeviceUnregister.Outcome.DONE, outcome)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a session and a debt stored for v3 go out at the API the app speaks`() = runTest {
        val stored = "https://api.example.test/v3"
        val sent = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            sent += "${request.method} ${request.url}"
            val reply = if (request.method == "DELETE") "" else
                """{"access_token":"access-${sent.size}","refresh_token":"refresh-${sent.size}","expires_in":900}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (request.method == "DELETE") 204 else 200)
                .message("").body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = stored }
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-stored", Context.MODE_PRIVATE))
        tokens.save("access-1", "refresh-1", 0, issuedBy = stored, studentId = STUDENT)
        val manager = AuthTokenManager(tokens, client, prefs, "device-1", now = { 1_000 }).apply {
            signInWithStoredAccount = { SignInAttempt.NOTHING_TO_SEND }
        }
        manager.requireAuthHeader(manager.session.signIns.current)
        val debt = MemoryStore(PendingUnregister("device-1", DepartingSession("access-0", "refresh-0", 0, stored, STUDENT), owedAt = 0))
        val unregister = DepartingDeviceUnregister(
            owed = OwedUnregister(debt),
            deleteDevice = PushApiClient(client, prefs, manager)::deleteDevice,
            refresh = manager::refreshDeparting,
            sessionAt = { false },
            now = { 1_000 },
            endpoint = { stored },
        )

        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister.send())

        val target = ApiBase.of(stored)
        assertEquals(listOf("POST $target/auth/refresh", "POST $target/auth/refresh", "DELETE $target/devices/device-1"), sent)
    }

    @Test
    fun `a debt stored for v3 whose token still holds goes out at the API the app speaks`() = runTest {
        val stored = "https://api.example.test/v3"
        val sent = mutableListOf<String>()

        val outcome = unregister(MemoryStore(debtTo(stored)), sent, endpoint = stored).send()

        assertEquals(DepartingDeviceUnregister.Outcome.DONE, outcome)
        assertEquals(listOf(ApiBase.of(stored)), sent)
    }

    private fun unregister(
        store: PendingUnregisterStore,
        sent: MutableList<String>,
        endpoint: String,
        sessionAt: (String) -> Boolean = { false },
    ) = DepartingDeviceUnregister(
        owed = OwedUnregister(store),
        deleteDevice = { base, _, _, mayDepart -> if (mayDepart()) 204.also { sent += base } else throw java.io.IOException() },
        refresh = { _, _, _ -> DepartingRefresh.Refused },
        sessionAt = sessionAt,
        now = { 0 },
        endpoint = { endpoint },
    )

    private companion object {
        const val STUDENT = "B00015999"
    }
}
