package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The school's sign-out lifts the stored-password latch, as the refusal belonged to the password
 * leaving with the account. AuthService needs the Android Keystore, which no JVM test can open, so
 * this reads the sign-out's source, with the stores it clears written out ([SignOutSource]), as
 * ImportedClassTablesClearingTest does; StoredPasswordLatchTest covers what the call does. A sign-in
 * lifts it as it stores the new password: TypedAccountSignInTest.
 */
class AuthServiceLatchClearingTest {

    private val clearing = SignOutSource.expanded(
        SignOutSource.body(SignOutSource.source(SignOutSource.PLAN), "private fun clearAccount()"),
    )

    @Test
    fun `the sign-out lifts the latch`() {
        assertEquals(1, Regex("""\bstoredPasswordLatch\.clear\(\)""").findAll(clearing).count())
    }
}
