package org.ntust.app.tigerduck.wear.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the phone sends keeps the watch's own library sign-in, begun before it,
 * from storing the account it was for (tigerduck-app/tigerduck-app-android#293).
 */
@RunWith(RobolectricTestRunner::class)
class WatchLibraryCredentialStoreTest {

    // Plain preferences: Robolectric has no Android keystore for the encrypted ones.
    private val store = WatchLibraryCredentialStore(
        RuntimeEnvironment.getApplication().getSharedPreferences("watch-library-test", Context.MODE_PRIVATE),
    )

    private var version = 0L

    private fun push(username: String?) = store.applyPush(
        hasCredentials = username != null,
        username = username,
        password = username?.let { "pw" },
        token = null,
        tokenExpiry = 0L,
        version = ++version,
        issuedAtMs = System.currentTimeMillis(),
    )

    // The watch signing in with what the phone sent, as LibraryService does once the library answers.
    private fun signIn(generation: Int, username: String = "B10000001") =
        store.saveLibrarySignIn(generation, username, "pw", "token", Long.MAX_VALUE, byUser = false)

    @Test
    fun `the watch's sign-in stores its token for the account the phone sent`() {
        push("B10000001")
        assertTrue(signIn(store.libraryAccountGeneration))
        assertEquals("B10000001", store.libraryUsername)
        assertEquals("token", store.libraryToken)
    }

    @Test
    fun `a wipe from the phone keeps a sign-in begun before it from storing the account`() {
        push("B10000001")
        val generation = store.libraryAccountGeneration

        assertEquals(WatchLibraryCredentialStore.ApplyResult.WIPED, push(null))

        assertFalse(signIn(generation))
        assertNull(store.libraryUsername)
        assertNull(store.libraryPassword)
        assertNull(store.libraryToken)
    }

    @Test
    fun `another account from the phone keeps a sign-in for the one before from storing it`() {
        push("B10000001")
        val generation = store.libraryAccountGeneration

        push("B10000002")

        assertFalse(signIn(generation))
        assertEquals("B10000002", store.libraryUsername)
        assertNull(store.libraryToken)
    }

    @Test
    fun `the same account in another case from the phone is not another account`() {
        push("B10000001")
        val generation = store.libraryAccountGeneration

        push("b10000001")

        assertTrue(signIn(generation))
        assertEquals("token", store.libraryToken)
    }

    @Test
    fun `the same account with spaces around it from the phone is not another account`() {
        push(" b10000001 ")
        val generation = store.libraryAccountGeneration

        push("B10000001")

        assertTrue(signIn(generation))
        assertEquals("token", store.libraryToken)
    }

    @Test
    fun `a purge keeps a sign-in begun before it from storing the account`() {
        push("B10000001")
        val generation = store.libraryAccountGeneration

        store.clear()

        assertFalse(signIn(generation))
        assertNull(store.libraryUsername)
    }
}
