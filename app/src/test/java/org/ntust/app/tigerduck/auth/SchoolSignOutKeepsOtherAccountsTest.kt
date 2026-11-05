package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The school sign-out takes the school account alone: the school mail and the library account stay
 * signed in, whoever's they are, as each account signs in and out by itself (decided for Android
 * on 2026-10-22). The school's plan needs the Android Keystore, so this reads its source, as
 * ImportedClassTablesClearingTest does.
 */
class SchoolSignOutKeepsOtherAccountsTest {

    /** The school's plan, from its class on: the file holds the other accounts' plans before it. */
    private val source: String = SignOutSource.source(SignOutSource.PLAN).let { it.substring(it.indexOf("internal class SchoolSignOut")) }

    /** The school's sign-out and what it clears, with the stores it clears written out. */
    private fun signOut() = SignOutSource.expanded(
        SignOutSource.body(source, "override fun signOut(") + SignOutSource.body(source, "private fun clearAccount("),
    )

    @Test
    fun `the school sign-out leaves the school mail signed in`() {
        val logout = signOut()
        assertFalse(Regex("""\bmailAccount\.""").containsMatchIn(logout))
    }

    @Test
    fun `the school sign-out leaves the library account signed in`() {
        val logout = signOut()
        assertFalse(Regex("""clearLibraryCredentials|signOutLibrary""").containsMatchIn(logout))
    }
}
