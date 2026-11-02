package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Which backend a session and an owed unregister belong to, as today's rules decide it for bases
 * that differ in more than their version segment: the guard's answer for each address, whether a
 * session is held at a base, whether a sign-in ends a debt, and whether a debt to a plain-HTTP
 * backend waits. Phase 3 compares backends without the version segment (`ApiBase`); every answer
 * here stays as it is.
 */
class SessionBackendMatchTest {

    private val backend = ScriptedBackend()
    private val context = JvmContext()

    /** A session [issuer] issued, as a sign-in there stores it, and its access token as handed out. */
    private suspend fun signedInAt(issuer: String): Pair<AuthTokenManager, String> {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://setting.example.test/v3" }
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-$issuer", Context.MODE_PRIVATE))
        tokens.save("access-1", "refresh-1", Long.MAX_VALUE, issuedBy = issuer, studentId = STUDENT)
        val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1").apply {
            signInWithStoredAccount = { SignInAttempt.NOTHING_TO_SEND }
        }
        val token = manager.requireAuthHeader(manager.session.signIns.current).removePrefix("Bearer ")
        assertEquals(emptyList<String>(), backend.endpoints())
        return manager to token
    }

    private fun AuthTokenManager.answers(token: String, urls: List<String>): Map<String, Boolean> =
        urls.associateWith { mayPresent(token, it.toHttpUrl()) }

    @Test
    fun `a session from a versioned base goes under that base and nowhere else`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test/v3")
        val accepted = listOf(
            "https://api.example.test/v3",
            "https://api.example.test/v3/sync/full",
            "https://api.example.test/v3/devices/device-1?x=1",
        )
        val refused = listOf(
            "https://api.example.test/v3x/sync/full",
            "https://api.example.test/sync/full",
            "https://api.example.test/links/name",
            "https://api.example.test/prefix/v3/sync/full",
            "https://api.example.test/v5/sync/full",
            "https://api.example.test/v4x/sync/full",
            "http://api.example.test/v3/sync/full",
            "https://api.example.test:8443/v3/sync/full",
            "https://other.example.test/v3/sync/full",
        )

        assertEquals(accepted.associateWith { true }, manager.answers(token, accepted))
        assertEquals(refused.associateWith { false }, manager.answers(token, refused))
    }

    @Test
    fun `a session from a base under a prefix goes under that prefix only`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test/prefix/v3")
        val refused = listOf(
            "https://api.example.test/prefix/sync/full",
            "https://api.example.test/v3/sync/full",
            "https://api.example.test/prefix/v3x/sync/full",
            "https://api.example.test/other/v3/sync/full",
        )

        assertTrue(manager.mayPresent(token, "https://api.example.test/prefix/v3/sync/full".toHttpUrl()))
        assertEquals(refused.associateWith { false }, manager.answers(token, refused))
    }

    @Test
    fun `a session from a base without a version goes anywhere under it`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test")
        val accepted = listOf(
            "https://api.example.test/sync/full",
            "https://api.example.test/v3/sync/full",
            "https://api.example.test/v4/sync/full",
            "https://api.example.test/links/name",
        )

        assertEquals(accepted.associateWith { true }, manager.answers(token, accepted))
        assertEquals(false, manager.mayPresent(token, "https://other.example.test/sync/full".toHttpUrl()))
    }

    @Test
    fun `a session is held at its base in any spelling, and at no other base`() = runTest {
        val (manager, _) = signedInAt("https://api.example.test/v3")
        val answers = listOf(
            "https://api.example.test/v3" to true,
            "https://API.Example.TEST:443/v3/" to true,
            "https://other.example.test/v3" to false,
            "https://api.example.test/prefix/v3" to false,
            "https://api.example.test" to false,
            "https://api.example.test:8443/v3" to false,
            "http://api.example.test/v3" to false,
        )

        answers.forEach { (base, held) -> assertEquals("held at $base", held, manager.hasSessionAt(base)) }
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
    fun `a sign-in ends a debt to its backend in any spelling, and leaves one to another`() = runTest {
        val sameBackend = MemoryStore(debtTo("https://API.Example.TEST:443/v3/"))
        val otherHost = MemoryStore(debtTo("https://other.example.test/v3"))
        val otherPrefix = MemoryStore(debtTo("https://api.example.test/prefix/v3"))
        val unversioned = MemoryStore(debtTo("https://api.example.test"))

        listOf(sameBackend, otherHost, otherPrefix, unversioned).forEach { store ->
            OwedUnregister(store).signingIn(STUDENT, "https://api.example.test/v3") { Unit }
        }

        assertNull(sameBackend.pending)
        assertNotNull(otherHost.pending)
        assertNotNull(otherPrefix.pending)
        assertNotNull(unversioned.pending)
    }

    @Test
    fun `bases that differ by an empty segment before the version are two backends`() = runTest {
        val (manager, token) = signedInAt("https://api.example.test/tenant/v3")
        val debt = MemoryStore(debtTo("https://api.example.test/tenant//v3"))

        OwedUnregister(debt).signingIn(STUDENT, "https://api.example.test/tenant/v3") { Unit }

        assertNotNull("a debt to the other backend stands", debt.pending)
        assertEquals(false, manager.hasSessionAt("https://api.example.test/tenant//v3"))
        assertEquals(false, manager.mayPresent(token, "https://api.example.test/tenant//v3/sync/full".toHttpUrl()))
    }

    @Test
    fun `a debt to a plain-HTTP backend waits while the setting names another`() = runTest {
        val outcomes = listOf(
            "http://192.168.1.5:40000/v3" to "http://192.168.1.5:40000/v3/",
            "http://192.168.1.5:40000/v3" to "http://192.168.1.6:40000/v3",
            "http://192.168.1.5:40000/v3" to "http://192.168.1.5:40001/v3",
            "http://192.168.1.5:40000/v3" to "http://192.168.1.5:40000/prefix/v3",
            "http://192.168.1.5:40000/v3" to "http://192.168.1.5:40000",
        ).map { (debt, endpoint) ->
            val sent = mutableListOf<String>()
            val unregister = DepartingDeviceUnregister(
                owed = OwedUnregister(MemoryStore(debtTo(debt))),
                deleteDevice = { base, _, _, mayDepart -> if (mayDepart()) 204.also { sent += base } else throw java.io.IOException() },
                refresh = { _, _, _ -> DepartingRefresh.Refused },
                sessionAt = { false },
                now = { 0 },
                endpoint = { endpoint },
            )
            Triple(endpoint, unregister.send(), sent)
        }

        assertEquals(
            listOf(
                Triple("http://192.168.1.5:40000/v3/", DepartingDeviceUnregister.Outcome.DONE, listOf("http://192.168.1.5:40000/v3")),
                Triple("http://192.168.1.6:40000/v3", DepartingDeviceUnregister.Outcome.RETRY, emptyList<String>()),
                Triple("http://192.168.1.5:40001/v3", DepartingDeviceUnregister.Outcome.RETRY, emptyList<String>()),
                Triple("http://192.168.1.5:40000/prefix/v3", DepartingDeviceUnregister.Outcome.RETRY, emptyList<String>()),
                Triple("http://192.168.1.5:40000", DepartingDeviceUnregister.Outcome.RETRY, emptyList<String>()),
            ),
            outcomes,
        )
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
