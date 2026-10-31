package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a background sync stops for its account: made-up student IDs. */
class BackgroundRunAccountTest {

    private var student: String? = "B00015999"
    private var signIn = 4

    private var refuse = false

    private fun run() = BackgroundRunAccount(
        "B00015999", 4, { student }, { signIn },
        runIfSignedIn = { _, block -> if (refuse) false else { block(); true } },
    )

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

    @Test
    fun `a stamp is written for a current run`() {
        var stamped = false
        assertTrue(run().writeIfCurrent { stamped = true })
        assertTrue(stamped)
    }

    @Test
    fun `no stamp is written when the sign-in lock refuses it`() {
        // A sign-out that fell between the run's check and its write.
        refuse = true
        var stamped = false
        assertFalse(run().writeIfCurrent { stamped = true })
        assertFalse(stamped)
    }

    @Test
    fun `no stamp is written for an ended run`() {
        val run = run()
        signIn = 5
        var stamped = false
        assertFalse(run.writeIfCurrent { stamped = true })
        assertFalse(stamped)
    }
}
