package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A refused revision poll carries its HTTP status, so the poller can tell a
 * 401, which gets one relogin and then stops polling, from a failure it backs
 * off from (tigerduck-app-android#154).
 */
class SyncRevisionStatusTest {

    private val backend = ScriptedBackend()
    private lateinit var client: SyncApiClient

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-revision-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        client = SyncApiClient(AuthTokenManager(tokens, backend.client, prefs, "device-1"), prefs, backend.client)
    }

    private suspend fun statusOfFailedPoll(): Int? =
        (runCatching { client.fetchRevision() }.exceptionOrNull() as? PushApiException)?.statusCode

    @Test
    fun `a 401 says so`() = runTest {
        backend.answer(REVISION, 401)

        assertEquals(401, statusOfFailedPoll())
    }

    @Test
    fun `a server error says which`() = runTest {
        backend.answer(REVISION, 503)

        assertEquals(503, statusOfFailedPoll())
    }

    private companion object {
        const val REVISION = "GET /sync/revision"
    }
}
