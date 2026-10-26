package org.ntust.app.tigerduck.wear.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.shared.LibraryServiceError
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The watch counts the library's refusals of the password the phone sent, as the
 * phone does, so its page waits the same schedule before it sends that password
 * again. A password the phone sends anew, or a sign-in that works, ends the count.
 */
@RunWith(RobolectricTestRunner::class)
class WatchLibraryPasswordRefusalTest {

    // Plain preferences: Robolectric has no Android keystore for the encrypted ones.
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("watch-library-refusal-test", Context.MODE_PRIVATE)
    private val store = WatchLibraryCredentialStore(prefs)

    private var version = 0L

    private fun push(username: String?, password: String = "pw") = store.applyPush(
        hasCredentials = username != null,
        username = username,
        password = username?.let { password },
        token = null,
        tokenExpiry = 0L,
        version = ++version,
        issuedAtMs = System.currentTimeMillis(),
    )

    private fun refuse(password: String = "pw", at: Long = 5_000L) =
        store.refuseLibraryPassword(store.libraryAccountGeneration, password, at)

    @Test
    fun `refusals are counted, with the time of the last, and kept across launches`() {
        push("B10000001")

        assertTrue(refuse(at = 5_000L))
        assertTrue(refuse(at = 9_000L))

        val relaunched = WatchLibraryCredentialStore(prefs)
        assertEquals(2, relaunched.libraryPasswordRefusals)
        assertEquals(9_000L, relaunched.libraryPasswordRefusedAt)
    }

    @Test
    fun `a sign-in on the watch that works ends the count`() {
        push("B10000001")
        refuse()

        store.saveLibrarySignIn(store.libraryAccountGeneration, "B10000001", "pw", "token", Long.MAX_VALUE, byUser = false)

        assertEquals(0, store.libraryPasswordRefusals)
        assertEquals(0L, store.libraryPasswordRefusedAt)
    }

    @Test
    fun `the same password sent again by the phone keeps the count`() {
        push("B10000001")
        refuse()

        push("B10000001")

        assertEquals(1, store.libraryPasswordRefusals)
    }

    @Test
    fun `another password or another account from the phone ends the count`() {
        push("B10000001")
        refuse()
        push("B10000001", password = "new")
        assertEquals(0, store.libraryPasswordRefusals)

        refuse(password = "new")
        push("B10000002", password = "new")
        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `a wipe from the phone ends the count`() {
        push("B10000001")
        refuse()

        push(null)
        push("B10000001")

        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `a refusal of a password the phone replaced meanwhile is not counted`() {
        push("B10000001")
        val generation = store.libraryAccountGeneration
        push("B10000001", password = "new")

        assertFalse(store.refuseLibraryPassword(generation, "pw", 5_000L))
        assertFalse(refuse(password = "pw"))
        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `the watch's page does not send a refused password before its wait is over`() = runBlocking {
        push("B10000001")
        val now = System.currentTimeMillis()
        refuse(at = now)
        // No server: a post would fail with a network error, not this.
        val service = LibraryService(store, clock = { now + 9_999 })

        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.CredentialsNotFound)
    }
}
