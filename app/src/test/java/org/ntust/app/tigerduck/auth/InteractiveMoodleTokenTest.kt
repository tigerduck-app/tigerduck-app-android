package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Moodle token the sign-in the user makes sends to the backend: the one it harvested, or the
 * same student's stored one when the harvest fails, and never one another account left
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/300).
 */
class InteractiveMoodleTokenTest {

    private var current = true

    private suspend fun token(
        harvest: suspend () -> String = { "fresh" },
        stored: String? = "stored",
        sameStudent: Boolean = true,
    ) = interactiveMoodleToken(harvest, stored = { stored }, sameStudent = sameStudent, stillCurrent = { current })

    @Test
    fun `it sends the token it harvested`() = runTest {
        assertEquals("fresh", token())
    }

    @Test
    fun `when the harvest fails, the same student's stored token goes`() = runTest {
        assertEquals("stored", token(harvest = { throw IllegalStateException("Moodle down") }))
    }

    @Test
    fun `when the harvest fails, another student's stored token never goes`() = runTest {
        assertNull(token(harvest = { throw IllegalStateException("Moodle down") }, sameStudent = false))
    }

    @Test
    fun `after a sign-out during the harvest, no token goes`() = runTest {
        assertNull(
            token(harvest = {
                current = false
                throw IllegalStateException("signed out")
            }),
        )
    }
}
