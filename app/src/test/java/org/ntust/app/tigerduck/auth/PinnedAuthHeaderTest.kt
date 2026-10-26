package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [AuthTokenManager.requireAuthHeader] pinned to a sign-in: a mark one
 * account tapped, sent after it signed out, must not go with the next
 * account's token — a classmate shares the same Moodle assignment ids.
 */
class PinnedAuthHeaderTest {

    private lateinit var tokens: V3TokenStore
    private lateinit var manager: AuthTokenManager
    private val backend = ScriptedBackend()

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-pinned-test", Context.MODE_PRIVATE))
        tokens.save("access-a", "refresh-a", Long.MAX_VALUE)
        manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    }

    @Test
    fun `the sign-in it was pinned to gets its token`() = runTest {
        assertEquals("Bearer access-a", manager.requireAuthHeader(manager.signInGeneration))
    }

    @Test
    fun `after a sign-out the next account's token is not handed to it`() = runTest {
        val pinned = manager.signInGeneration
        manager.logout()
        // The next account signs in.
        tokens.save("access-b", "refresh-b", Long.MAX_VALUE)

        val header = runCatching { manager.requireAuthHeader(pinned) }

        assertTrue("got ${header.getOrNull()}", header.exceptionOrNull() is NotSignedInException)
        assertEquals("Bearer access-b", manager.requireAuthHeader())
    }
}
