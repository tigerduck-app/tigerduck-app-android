package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored account's sign-in to the school: again while the session is cold, past a warm one on
 * the retry of a refused password, and never with no account stored.
 */
class SchoolSignInTest {

    private var storedId: String? = "b10000001"
    private var storedPassword: String? = "pw"
    private var warm = false
    private var schoolSignIns = 0
    private var signInsAgain = 0

    /** The student ID each SSO step was given. */
    private val signedInAs = mutableListOf<String>()

    private val signIn = SchoolSignIn(
        studentId = { storedId },
        password = { storedPassword },
        sessionIsWarm = { warm },
        signInToSchool = { studentId, _ ->
            schoolSignIns++
            signedInAs += studentId
            true
        },
        signInAgainToSchool = { studentId, _, _, _ ->
            signInsAgain++
            signedInAs += studentId
            true
        },
    )

    @Test
    fun `a sign-in again signs in with the stored account, its ID in upper case`() = runTest {
        assertTrue(signIn.stored())

        assertEquals(1, schoolSignIns)
        assertEquals(listOf("B10000001"), signedInAs)
    }

    @Test
    fun `a warm session skips the SSO step`() = runTest {
        warm = true

        assertTrue(signIn.stored())

        assertEquals(0, schoolSignIns)
    }

    /**
     * A retry of a refused password asks the school again, as only its answer to a post says
     * whether the password works: a warm session says nothing about it.
     */
    @Test
    fun `a sign-in again past a warm session posts the stored password`() = runTest {
        warm = true

        assertTrue(signIn.storedAgain(stillRefused = { true }))

        assertEquals(1, signInsAgain)
        assertEquals(0, schoolSignIns)
        assertEquals(listOf("B10000001"), signedInAs)
    }

    /**
     * A sign-in by hand that the retry waited behind has settled the password already: posting
     * it again would only throw away the session that sign-in just made.
     */
    @Test
    fun `a sign-in again whose refusal is gone by the time it runs posts nothing`() = runTest {
        assertTrue(signIn.storedAgain(stillRefused = { false }))

        assertEquals(0, signInsAgain)
        assertEquals(0, schoolSignIns)
    }

    @Test
    fun `no stored account signs nothing in again`() = runTest {
        storedPassword = null

        assertFalse(signIn.storedAgain(stillRefused = { true }))

        assertEquals(0, signInsAgain)
    }

    @Test
    fun `no stored account signs nothing in`() = runTest {
        storedId = null

        assertFalse(signIn.stored())

        assertEquals(0, schoolSignIns)
    }
}
