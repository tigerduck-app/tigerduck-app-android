package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
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
        assertEquals("Bearer access-a", manager.requireAuthHeader(manager.session.signIns.current))
    }

    /** Refused as soon as it ends: the sign-out drops the pair later, in its clearing. */
    @Test
    fun `a sign-in that has ended gets no token, its own included`() = runTest {
        val signIn = manager.session.signIns.end()

        val header = runCatching { manager.requireAuthHeader(signIn) }

        assertTrue("got ${header.getOrNull()}", header.exceptionOrNull() is NotSignedInException)
    }

    @Test
    fun `a request of an ended sign-in sets off no sign-in`() = runTest {
        var signIns = 0
        manager.signInWithStoredAccount = {
            signIns++
            SignInAttempt.NOTHING_TO_SEND
        }
        val signIn = manager.session.signIns.current
        manager.logout()
        manager.session.open()

        val header = runCatching { manager.requireAuthHeader(signIn) }

        assertTrue("got ${header.getOrNull()}", header.exceptionOrNull() is NotSignedInException)
        assertEquals(0, signIns)
    }

    /** Neither the next account's token, nor a refresh of its pair for a request that isn't its own. */
    @Test
    fun `a request that set off a sign-in gets nothing when a sign-out lands during it`() = runTest {
        for (expiresAt in listOf(Long.MAX_VALUE, 0L)) {
            manager.session.open()
            tokens.clear()
            val signIn = manager.session.signIns.current
            manager.signInWithStoredAccount = {
                manager.logout()
                manager.session.open()
                tokens.save("access-b", "refresh-b", expiresAt)
                SignInAttempt.SIGNED_IN
            }

            val header = runCatching { manager.requireAuthHeader(signIn) }

            assertTrue("got ${header.getOrNull()}", header.exceptionOrNull() is NotSignedInException)
            assertEquals("no refresh of the next pair", emptyList<String>(), backend.endpoints())
            manager.logout()
        }
    }

    @Test
    fun `after a sign-out the next sign-in's token is not handed to the one that ended`() = runTest {
        val signIn = manager.session.signIns.current
        manager.logout()
        manager.session.open()
        tokens.save("access-b", "refresh-b", Long.MAX_VALUE)

        val header = runCatching { manager.requireAuthHeader(signIn) }

        assertTrue("got ${header.getOrNull()}", header.exceptionOrNull() is NotSignedInException)
        assertEquals("Bearer access-b", manager.requireAuthHeader(manager.session.signIns.current))
    }
}
