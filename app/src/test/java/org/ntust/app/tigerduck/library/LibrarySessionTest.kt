package org.ntust.app.tigerduck.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LibrarySessionTest {

    @Test
    fun `refresh picks up a sign-in and a sign-out made behind its back`() {
        var signedIn = false
        val session = LibrarySession(read = { signedIn })
        assertFalse(session.signedIn.value)

        signedIn = true
        assertTrue(session.refresh())
        assertTrue(session.signedIn.value)

        signedIn = false
        assertFalse(session.refresh())
        assertFalse(session.signedIn.value)
    }

    @Test
    fun `signed out, the kept QR code goes`() {
        var signedIn = true
        var cleared = 0
        val session = LibrarySession({ signedIn }, onSignedOut = { cleared++ })

        session.refresh()
        assertEquals(0, cleared)

        signedIn = false
        session.refresh()
        assertEquals(1, cleared)
    }

    @Test
    fun `an earlier read cannot write its older answer over a later one`() {
        var signedIn = true
        val firstReading = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val session = LibrarySession(read = {
            val answer = signedIn
            if (++reads == 2) {
                firstReading.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answer
        })

        val earlier = thread { session.refresh() }
        firstReading.await(5, TimeUnit.SECONDS)
        signedIn = false
        val later = thread { session.refresh() }
        later.join(200)
        release.countDown()
        earlier.join()
        later.join()

        assertFalse(session.signedIn.value)
    }
}
