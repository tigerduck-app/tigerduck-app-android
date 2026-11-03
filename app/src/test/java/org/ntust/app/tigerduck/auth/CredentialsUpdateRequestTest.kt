package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.JvmContext

/** What [PushApiClient.updateCredentials] sends, by the backend it goes to. */
class CredentialsUpdateRequestTest {

    private val backend = ScriptedBackend(under = "/v4")

    private fun update(override: String?) = runTest {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = override }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied == (override != null))
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-credentials-test", Context.MODE_PRIVATE))
        tokens.clear()
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")

        PushApiClient(backend.client, prefs, manager).updateCredentials("moodle", manager.session.signIns.current, "private")
    }

    @Test
    fun `an update to a backend of the user's own leaves the private token out`() {
        update("http://127.0.0.1:9/v3")

        val sent = backend.received("PATCH /auth/credentials").single().body
        assertTrue(sent, "moodle" in sent)
        assertFalse(sent, "private" in sent)
    }
}
