package org.ntust.app.tigerduck.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pushes for an account are left alone once it has signed out. Until the unregister a
 * sign-out owes lands, the server still has this phone registered to the departing account.
 */
class AccountPushesTest {

    private val accountPushes = listOf("sync_trigger", "reauth_required")

    // Bulletins and operator pushes reach a signed-out phone on purpose, through its anonymous
    // registration; null is the legacy bulletin, which carries no kind.
    private val devicePushes = listOf("custom_push_bulletin", "custom_push_popup", null)

    @Test
    fun `signed out, the account's pushes are left alone and the device's handled`() {
        assertEquals(listOf(false, false), accountPushes.map { handlesPush(it, signedIn = false) })
        assertEquals(listOf(true, true, true), devicePushes.map { handlesPush(it, signedIn = false) })
    }

    @Test
    fun `signed in, every push is handled`() {
        assertEquals(listOf(true, true, true, true, true), (accountPushes + devicePushes).map { handlesPush(it, signedIn = true) })
    }
}
