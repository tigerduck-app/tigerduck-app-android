package org.ntust.app.tigerduck.ui.screen.signin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the TigerSync status asks the user to sign in again
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/203).
 */
class AsksToSignInAgainTest {

    @Test
    fun `it asks once the automatic sign-in failed and left no session`() {
        assertTrue(ask())
    }

    @Test
    fun `it does not ask with sync off, signed out of the school, with a session, or with nothing failed`() {
        assertFalse(ask(syncEnabled = false))
        assertFalse(ask(credentialsReadable = false))
        assertFalse(ask(hasBackendSession = true))
        assertFalse(ask(lastSignInFailed = false))
    }

    private fun ask(
        syncEnabled: Boolean = true,
        credentialsReadable: Boolean = true,
        hasBackendSession: Boolean = false,
        lastSignInFailed: Boolean = true,
    ) = asksToSignInAgain(syncEnabled, credentialsReadable, hasBackendSession, lastSignInFailed)
}
