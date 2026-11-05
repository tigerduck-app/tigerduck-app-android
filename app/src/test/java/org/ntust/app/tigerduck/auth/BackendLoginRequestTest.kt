package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * What [AuthTokenManager.login] sends: the backend proves the account with the Moodle token alone
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/208).
 */
class BackendLoginRequestTest {

    private lateinit var manager: AuthTokenManager
    private val backend = ScriptedBackend(under = "/v4")

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        tokens.clear()
        manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    }

    @Test
    fun `a sign-in sends the student ID and the Moodle token, and no password`() = runTest {
        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null, signIn = manager.session.signIns.current)

        val sent = JSONObject(backend.received(LOGIN).single().body)
        assertEquals("B00000000", sent.getString("student_id"))
        assertEquals("moodle", sent.getString("moodle_token"))
        assertFalse(sent.has("password"))
    }

    @Test
    fun `a sign-in body is sent once, so a redirect does not send the token on`() = runTest {
        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = "private", signIn = manager.session.signIns.current)

        assertTrue(backend.received(LOGIN).single().sentOnce)
    }

    @Test
    fun `a sign-in to a backend of the user's own leaves the private token out`() = runTest {
        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = "private", signIn = manager.session.signIns.current)

        val sent = JSONObject(backend.received(LOGIN).single().body)
        assertEquals("moodle", sent.getString("moodle_token"))
        assertFalse(sent.has("moodle_private_token"))
    }

    @Test
    fun `a sign-in to the default backend carries the private token`() = runTest {
        val context = JvmContext()
        val defaults = AppPreferences(context)
        assertFalse(resolveAnnouncementEndpoint(defaults).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-default-test", Context.MODE_PRIVATE))
        tokens.clear()

        AuthTokenManager(tokens, backend.client, defaults, "device-1")
            .run { login("B00000000", moodleToken = "moodle", moodlePrivateToken = "private", signIn = session.signIns.current) }

        val sent = JSONObject(backend.received(LOGIN).single().body)
        assertEquals("private", sent.getString("moodle_private_token"))
    }

    @Test
    fun `a sign-in without a Moodle token sends nothing`() = runTest {
        val failure = runCatching {
            manager.login("B00000000", moodleToken = "", moodlePrivateToken = null, signIn = manager.session.signIns.current)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is AuthTokenManager.AuthException)
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    private companion object {
        const val LOGIN = "POST /auth/login"
    }
}
