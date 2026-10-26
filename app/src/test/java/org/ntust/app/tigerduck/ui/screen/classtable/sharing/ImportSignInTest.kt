package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An import saves only under the sign-in it started under, signed out included. */
class ImportSignInTest {

    private var signedIn = false
    private var studentId: String? = null

    private fun check() = ClassTableImportViewModel.sameSignIn({ signedIn }, { studentId })

    @Test
    fun `an import started signed out stays current until someone signs in`() {
        val current = check()
        assertTrue(current())

        signedIn = true
        studentId = "B11300000"
        assertFalse(current())
    }

    @Test
    fun `a sign-out, or another account signing in, ends an import started signed in`() {
        signedIn = true
        studentId = "B11300000"
        val current = check()
        assertTrue(current())

        signedIn = false
        studentId = null
        assertFalse(current())

        signedIn = true
        studentId = "B11399999"
        assertFalse(current())
    }
}
