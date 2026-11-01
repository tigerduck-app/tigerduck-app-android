package org.ntust.app.tigerduck.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignInSeries
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** When a background sync stops for its account: made-up student IDs. */
class BackgroundRunAccountTest {

    private var student: String? = "B00015999"
    private val series = SignInSeries()

    private fun run() = BackgroundRunAccount("B00015999", series.current) { student }

    private fun signOutAndBackIn() {
        series.end()
        series.open()
    }

    @Test
    fun `a run under the same sign-in goes on`() {
        assertFalse(run().ended())
    }

    @Test
    fun `a sign-out and sign-in as the same student ends the run`() {
        val run = run()
        // The student is the same again; only the sign-in moved.
        signOutAndBackIn()
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
        signOutAndBackIn()
        assertTrue(run.ended())
    }

    @Test
    fun `a stamp is written for a current run`() {
        var stamped = false
        assertTrue(run().writeIfCurrent { stamped = true })
        assertTrue(stamped)
    }

    @Test
    fun `a stamp a sign-out overtakes waits for neither, and is refused`() {
        val run = run()
        series.end()
        var stamped = false
        assertFalse(run.writeIfCurrent { stamped = true })
        assertFalse(stamped)
    }

    /** The sign-out waits for the write: what the write stores lands before the clearing that follows it. */
    @Test
    fun `a sign-out waits for a stamp under way`() = runBlocking {
        val run = run()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = mutableListOf<String>()
        val write = launch(Dispatchers.IO) {
            run.writeIfCurrent {
                writing.countDown()
                release.await(10, TimeUnit.SECONDS)
                synchronized(order) { order += "stamp" }
            }
        }
        assertTrue(writing.await(10, TimeUnit.SECONDS))
        val signOut = launch(Dispatchers.IO) {
            series.end()
            synchronized(order) { order += "sign-out" }
        }
        // Long enough for a sign-out that did not wait to have finished.
        delay(100)
        release.countDown()
        write.join()
        signOut.join()

        assertEquals(listOf("stamp", "sign-out"), order)
    }

    @Test
    fun `no stamp is written for an ended run`() {
        val run = run()
        signOutAndBackIn()
        var stamped = false
        assertFalse(run.writeIfCurrent { stamped = true })
        assertFalse(stamped)
    }
}
