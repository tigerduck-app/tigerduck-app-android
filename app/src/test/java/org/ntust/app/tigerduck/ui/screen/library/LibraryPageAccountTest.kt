package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.library.FakeLibraryServer
import org.ntust.app.tigerduck.library.MemoryLibraryStore

/**
 * The Library page counts an account as signed in while its token is live or
 * its stored credentials can still sign in, as the iPhone does
 * (tigerduck-app/tigerduck-app#528): a failed code fetch short of a refusal,
 * such as a timeout at the gate, is tried again, not read as a sign-out.
 */
class LibraryPageAccountTest {

    private val store = MemoryLibraryStore()
    private val server = FakeLibraryServer()
    private var demoCode: String? = null
    private var demoSignedIn = false
    private val page = LibraryPageAccount(store, server.service(store), { demoCode }, { demoSignedIn })

    @Test
    fun `an expired token with stored credentials shows the pass, and its fetch signs in again`() = runBlocking {
        store.storeExpired()
        assertTrue(page.hasAccount)

        assertEquals("code", page.fetchCode())
        assertEquals(1, server.signIns)
    }

    @Test
    fun `a fetch that fails without a refusal keeps the account signed in`() = runBlocking {
        store.storeExpired()
        server.signInAnswers += FakeLibraryServer.NO_NETWORK

        runCatching { page.fetchCode() }

        assertTrue(page.hasAccount)
    }

    @Test
    fun `a server error keeps the account signed in too`() = runBlocking {
        store.storeExpired()
        server.signInAnswers += FakeLibraryServer.SERVER_ERROR

        runCatching { page.fetchCode() }

        assertTrue(page.hasAccount)
    }

    @Test
    fun `a refused stored password signs the account out`() = runBlocking {
        store.storeExpired()
        server.signInAnswers += FakeLibraryServer.WRONG_PASSWORD

        runCatching { page.fetchCode() }

        assertFalse(page.hasAccount)
    }

    @Test
    fun `no account shows the sign-in form, the demo library the pass`() {
        assertFalse(page.hasAccount)
        demoSignedIn = true
        assertTrue(page.hasAccount)
    }

    @Test
    fun `the demo library's code comes from the demo, a real account's from the library`() = runBlocking {
        demoCode = "demo"
        assertEquals("demo", page.fetchCode())
        assertEquals(0, server.signIns)
    }

    @Test
    fun `a code belongs to its account, and the demo library's to none of them`() {
        store.storeExpired()
        val real = page.codeAccount
        demoSignedIn = true
        val demo = page.codeAccount
        assertNotEquals(real, demo)

        demoSignedIn = false
        store.signOut()
        assertNotEquals(real, page.codeAccount)
        assertNotEquals(LibraryPageAccount.codeAccount(3, demoLibrary = true), LibraryPageAccount.codeAccount(4, demoLibrary = false))
    }
}
