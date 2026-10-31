package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a background sync stops for its account: made-up student IDs. */
class BackgroundRunAccountTest {

    private var student: String? = "B00015999"
    private var signIn = 4

    private fun run() = BackgroundRunAccount("B00015999", 4, { student }, { signIn })

    @Test
    fun `a run under the same sign-in goes on`() {
        assertFalse(run().ended())
    }

    @Test
    fun `a sign-out and sign-in as the same student ends the run`() {
        val run = run()
        // The student is the same again; only the sign-in moved.
        signIn = 5
        assertTrue(run.ended())
    }

    @Test
    fun `a sign-out ends the run`() {
        val run = run()
        student = null
        assertTrue(run.ended())
    }

    @Test
    fun `a sign-in as another student ends the run`() {
        val run = run()
        student = "B00016000"
        signIn = 5
        assertTrue(run.ended())
    }
}
