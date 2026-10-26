package org.ntust.app.tigerduck.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library's part of each launch and return to the app. */
class LibraryReturnSignInTest {

    private val store = MemoryLibraryStore().apply { storeExpired() }
    private val server = FakeLibraryServer()
    private val service = server.service(store)
    private var reads = 0
    private val session = LibrarySession(read = { reads++; service.hasAccount })
    private var demo = false
    private var online = true
    private val onReturn = LibraryReturnSignIn(session, service, { demo }, { online })

    @Test
    fun `a return reads the sign-in again and signs in with the stored credentials`() = runBlocking {
        val readsBefore = reads

        assertTrue(onReturn.onAppResumed())

        assertTrue(reads > readsBefore)
        assertEquals(1, server.signIns)
        assertTrue(store.isLibraryTokenValid)
    }

    @Test
    fun `in demo mode a return reaches no server`() = runBlocking {
        demo = true

        assertFalse(onReturn.onAppResumed())
        assertEquals(0, server.signIns)
    }

    @Test
    fun `offline a return does not sign in`() = runBlocking {
        online = false

        assertFalse(onReturn.onAppResumed())
        assertEquals(0, server.signIns)
    }
}
