package org.ntust.app.tigerduck.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.SignOutReason
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LibrarySessionTest {

    @Test
    fun `a sign-out takes the account off, reads the stores again, and then tells the watch`() {
        val steps = mutableListOf<String>()
        var signedIn = true
        val session = LibrarySession(
            read = {
                steps += "read"
                signedIn
            },
            onSignedOut = { steps += "signed out" },
            remove = {
                steps += "remove"
                signedIn = false
            },
            tellWatch = { steps += "watch" },
        )
        steps.clear()

        session.signOut(SignOutReason.LIBRARY)

        assertEquals(listOf("remove", "read", "signed out", "watch"), steps)
        assertFalse(session.signedIn.value)
    }

    @Test
    fun `the removal is told why the account goes`() {
        val told = mutableListOf<SignOutReason>()
        val session = LibrarySession(read = { false }, remove = { told += it })

        session.signOut(SignOutReason.ENDPOINT_CHANGE)
        session.signOut(SignOutReason.LIBRARY)

        assertEquals(listOf(SignOutReason.ENDPOINT_CHANGE, SignOutReason.LIBRARY), told)
    }

    @Test
    fun `an account coming to show as signed in is told once, and a read that changes nothing tells nothing`() {
        var signedIn = false
        var told = 0
        val session = LibrarySession({ signedIn }, tellSignedIn = { told++ })

        session.refresh()
        assertEquals(0, told)

        signedIn = true
        session.refresh()
        session.refresh()
        assertEquals(1, told)

        signedIn = false
        session.refresh()
        assertEquals(1, told)
    }

    @Test
    fun `the library is signed out by one path, not a copy per screen`() {
        var dir: File = File("").absoluteFile
        while (!File(dir, "src/main/java").isDirectory && !File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile ?: throw AssertionError("Could not locate the :app module")
        }
        val sources = listOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")).first { it.isDirectory }
        val callers = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { "clearLibraryCredentials(" in it.readText() }.map { it.name }.toSet()

        assertEquals(setOf("CredentialManager.kt", "LibrarySession.kt"), callers)
    }

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
