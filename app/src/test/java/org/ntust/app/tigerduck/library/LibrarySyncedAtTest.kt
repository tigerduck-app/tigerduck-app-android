package org.ntust.app.tigerduck.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The Library page's "Last synced" row, [AppPreferences.librarySyncedAtMs]: kept across
 * restarts while a library account is signed in, and gone with the kept code once none is.
 */
class LibrarySyncedAtTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)

    @Test
    fun `the page's date outlives the process, and goes with the kept code at sign-out`() {
        var signedIn = true
        val cache = LibraryQRCache<String>().apply { store("code", "image", account = 1, issuedAt = 0L) }
        val session = LibrarySession({ signedIn }, cache, prefs)
        prefs.markLibrarySynced(5_000L)

        session.refresh()
        assertEquals(5_000L, AppPreferences(context).librarySyncedAtMs.value)
        assertNotNull(cache.reusable(account = 1, now = 0L))

        signedIn = false
        session.refresh()

        assertNull(cache.reusable(account = 1, now = 0L))
        assertEquals(0L, prefs.librarySyncedAtMs.value)
        assertEquals(0L, AppPreferences(context).librarySyncedAtMs.value)
    }
}
