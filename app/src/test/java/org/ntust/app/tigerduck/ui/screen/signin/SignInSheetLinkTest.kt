package org.ntust.app.tigerduck.ui.screen.signin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a link that opens another page does to the "Sign in again" sheet, as on iOS
 * (tigerduck-app/tigerduck-app#672).
 */
class SignInSheetLinkTest {

    @Test
    fun `a link closes the sheet, as a tap outside it does`() {
        assertTrue(signInSheetClosesForLink(open = true, signingIn = false))
    }

    @Test
    fun `a sign-in under way holds the sheet open until it ends`() {
        assertFalse(signInSheetClosesForLink(open = true, signingIn = true))
    }

    /** Nor does it clear the error of a sign-in elsewhere, such as Settings' own. */
    @Test
    fun `with no sheet open there is nothing to close`() {
        assertFalse(signInSheetClosesForLink(open = false, signingIn = false))
    }
}
