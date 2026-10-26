package org.ntust.app.tigerduck.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.LibraryService

/** What the watch is handed after the library refuses the stored password, and once it takes it again. */
class LibraryWatchWiringTest {

    private val store = MemoryLibraryStore().apply { storeExpired() }

    @Test
    fun `a refused password is never handed to the watch`() {
        store.refuseLibraryPassword(store.libraryAccountGeneration, "pw", at = 1L)

        assertNull(libraryCredentialsForWatch(store))
    }

    @Test
    fun `a signed-in account is handed over, and none once signed out`() {
        assertEquals("B10000001" to "pw", libraryCredentialsForWatch(store))

        store.signOut()
        assertNull(libraryCredentialsForWatch(store))
    }

    @Test
    fun `a refusal wipes the watch's copy and reads the sign-in again`() = runBlocking {
        var refreshes = 0
        val session = LibrarySession(read = { refreshes++; true })
        val readsAtStart = refreshes
        var published = 0

        onStoredLibrarySignIn(LibraryService.StoredSignIn.REFUSED, session) { published++ }

        assertEquals(1, published)
        assertTrue(refreshes > readsAtStart)
    }

    @Test
    fun `a password the library takes again goes back to the watch`() = runBlocking {
        val session = LibrarySession(read = { true })
        var published = 0

        onStoredLibrarySignIn(LibraryService.StoredSignIn.SIGNED_IN_AFTER_REFUSAL, session) { published++ }

        assertEquals(1, published)
    }

    @Test
    fun `a renewed token leaves the watch's copy alone`() = runBlocking {
        val session = LibrarySession(read = { true })
        var published = 0

        onStoredLibrarySignIn(LibraryService.StoredSignIn.SIGNED_IN, session) { published++ }

        assertEquals(0, published)
    }
}
