package org.ntust.app.tigerduck.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A library sign-in whose account signs out, or is replaced, while its request
 * is out stores nothing: stored, it would sign the next student in to the
 * library as the one who left (tigerduck-app/tigerduck-app#439, #436).
 */
class LibraryServiceAccountTest {

    private class MemoryStore : LibraryCredentialStore {
        private val account = LibraryAccountGate()
        override var libraryUsername: String? = null
        override var libraryPassword: String? = null
        override var libraryToken: String? = null
        override var libraryTokenExpiry: Long = 0L
        override val isLibraryTokenValid: Boolean
            get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry
        override val libraryAccountGeneration: Int get() = account.current

        override fun saveLibrarySignIn(
            generation: Int,
            username: String,
            password: String,
            token: String?,
            tokenExpiry: Long,
            byUser: Boolean,
            stillCurrent: () -> Boolean,
        ): Boolean = account.saveIfCurrent(generation, byUser, { libraryUsername }, username, stillCurrent) {
            libraryUsername = username
            libraryPassword = password
            libraryToken = token
            libraryTokenExpiry = tokenExpiry
        }

        fun signOut() = account.signOut {
            libraryUsername = null
            libraryPassword = null
            libraryToken = null
            libraryTokenExpiry = 0L
        }

        /** An account whose token is still good, so a code asks for nothing else. */
        fun storeValid(username: String, password: String) {
            libraryUsername = username
            libraryPassword = password
            libraryToken = "valid"
            libraryTokenExpiry = Long.MAX_VALUE
        }

        /** An account whose token has run out, so the next code signs in again. */
        fun storeExpired(username: String, password: String) {
            libraryUsername = username
            libraryPassword = password
            libraryToken = "expired"
            libraryTokenExpiry = 1L
        }

        /** Another account handed over, as the phone hands one to the watch, its token run out. */
        fun handOver(username: String, password: String) = account.replaceAccount { storeExpired(username, password) }
    }

    private val store = MemoryStore()

    /** Runs while the library is answering a sign-in, before the answer reaches the app. */
    private var whileSigningIn: () -> Unit = {}
    private var signIns = 0

    /** Runs while the library is answering a code request, with the call waiting on it. */
    private var whileFetchingCode: (Call) -> Unit = {}

    private val library = Interceptor { chain ->
        val path = chain.request().url.encodedPath
        val body = when {
            path.endsWith(LibraryApi.PATH_LOGIN) -> {
                signIns++
                whileSigningIn()
                """{"data":{"username":"B10000001","token":"token-$signIns","expirationTimeStamp":${Long.MAX_VALUE}}}"""
            }
            path.endsWith(LibraryApi.PATH_GENERATE_QR) -> {
                whileFetchingCode(chain.call())
                """{"data":"code"}"""
            }
            else -> error("unexpected request to $path")
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val service = LibraryService(store, extraInterceptors = listOf(library))

    @Test
    fun `a sign-in stores the account and its token`() = runBlocking {
        assertEquals("token-1", service.login("B10000001", "pw", store.libraryAccountGeneration, byUser = true))
        assertEquals("B10000001", store.libraryUsername)
        assertEquals("pw", store.libraryPassword)
        assertEquals("token-1", store.libraryToken)
    }

    @Test
    fun `a sign-in answered after a sign-out stores nothing`() = runBlocking {
        whileSigningIn = { store.signOut() }

        val thrown = runCatching { service.login("B10000001", "pw", store.libraryAccountGeneration, byUser = true) }.exceptionOrNull()

        assertTrue("expected CredentialsNotFound, got $thrown", thrown is LibraryServiceError.CredentialsNotFound)
        assertNull(store.libraryUsername)
        assertNull(store.libraryPassword)
        assertNull(store.libraryToken)
    }

    @Test
    fun `a code asked for as a sign-out lands brings back no account`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        whileSigningIn = { store.signOut() }

        val thrown = runCatching { service.generateQRCode() }.exceptionOrNull()

        assertTrue("expected CredentialsNotFound, got $thrown", thrown is LibraryServiceError.CredentialsNotFound)
        assertNull(store.libraryUsername)
        assertNull(store.libraryPassword)
        assertNull(store.libraryToken)
    }

    @Test
    fun `a sign-in begun before a sign-out stores nothing, though its request goes out after it`() = runBlocking {
        // The school sign-in reads the generation before its SSO step, then signs in to the
        // library once that step is done.
        val generation = store.libraryAccountGeneration
        store.signOut()

        val thrown = runCatching { service.login("B10000001", "pw", generation, byUser = false) }.exceptionOrNull()

        assertTrue("expected CredentialsNotFound, got $thrown", thrown is LibraryServiceError.CredentialsNotFound)
        assertNull(store.libraryUsername)
        assertNull(store.libraryToken)
    }

    @Test
    fun `another account stored meanwhile is kept`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        whileSigningIn = {
            assertTrue(store.saveLibrarySignIn(store.libraryAccountGeneration, "B10000002", "other", "theirs", Long.MAX_VALUE, byUser = true))
        }

        runCatching { service.ensureToken() }

        assertEquals("B10000002", store.libraryUsername)
        assertEquals("theirs", store.libraryToken)
    }

    /**
     * A sign-in the user asked for, answered after the app's own sign-in stored another account
     * meanwhile, is what the user wants, and only a sign-out stops it.
     */
    @Test
    fun `a sign-in the user asked for replaces another account stored meanwhile`() = runBlocking {
        whileSigningIn = {
            assertTrue(store.saveLibrarySignIn(store.libraryAccountGeneration, "B10000002", "other", "theirs", Long.MAX_VALUE, byUser = false))
        }

        assertEquals("token-1", service.login("b10000001", "pw", store.libraryAccountGeneration, byUser = true))
        assertEquals("b10000001", store.libraryUsername)
        assertEquals("token-1", store.libraryToken)
    }

    /** The school sign-in upper-cases the ID and the library form keeps what was typed. */
    @Test
    fun `the same student in another case is not another account`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        val generation = store.libraryAccountGeneration

        assertTrue(store.saveLibrarySignIn(generation, "b10000001", "pw", "fresh", Long.MAX_VALUE, byUser = true))
        // A sign-in the app made on its own, begun before that save, still stores its answer.
        assertTrue(store.saveLibrarySignIn(generation, "B10000001", "pw", "own", Long.MAX_VALUE, byUser = false))
        assertEquals("own", store.libraryToken)
    }

    @Test
    fun `a sign-in again for the account still stored keeps its new token`() = runBlocking {
        store.storeExpired("B10000001", "pw")

        assertEquals("token-1", service.ensureToken())
        assertEquals("B10000001", store.libraryUsername)
        assertEquals("token-1", store.libraryToken)
    }

    /**
     * A code request whose caller is cancelled, as the watch's page is when the phone hands over
     * another account, stops waiting for the library at once rather than holding the page until
     * it answers or times out.
     */
    @Test
    fun `cancelling a code request cancels the call it waits on`() = runBlocking {
        store.storeValid("B10000001", "pw")
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        var call: Call? = null
        whileFetchingCode = {
            call = it
            asked.countDown()
            answer.await(5, TimeUnit.SECONDS)
        }
        val request = launch(Dispatchers.Default) { runCatching { service.generateQRCode() } }
        assertTrue(asked.await(5, TimeUnit.SECONDS))

        request.cancel()

        assertTrue(call!!.isCanceled())
        answer.countDown()
        request.join()
    }

    /**
     * The phone hands the watch another account while the watch's page signs in with the one
     * before: the page stops waiting, and the next account's sign-in is not held behind a request
     * nothing will use, which went on until the library answered or timed out.
     */
    @Test
    fun `a sign-in for an account handed over, that no one waits for, gives way to the next account`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        val release = CountDownLatch(1)
        val asked = CountDownLatch(1)
        whileSigningIn = {
            if (signIns == 1) {
                asked.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
        }
        try {
            val page = launch(Dispatchers.Default) { runCatching { service.ensureToken() } }
            assertTrue(asked.await(5, TimeUnit.SECONDS))

            store.handOver("B10000002", "theirs")
            page.cancel()

            assertEquals("token-2", withTimeout(5_000) { service.ensureToken() })
            assertEquals("B10000002", store.libraryUsername)
        } finally {
            release.countDown()
        }
    }

    /** As above, with the page gone before the account was handed over. */
    @Test
    fun `a sign-in no one waits for gives way once its account is handed over`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        val release = CountDownLatch(1)
        val asked = CountDownLatch(1)
        whileSigningIn = {
            if (signIns == 1) {
                asked.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
        }
        try {
            val page = launch(Dispatchers.Default) { runCatching { service.ensureToken() } }
            assertTrue(asked.await(5, TimeUnit.SECONDS))
            page.cancel()
            page.join()

            store.handOver("B10000002", "theirs")

            assertEquals("token-2", withTimeout(5_000) { service.ensureToken() })
        } finally {
            release.countDown()
        }
    }

    /** A caller still waiting on the sign-in keeps it going, even for an account handed over. */
    @Test
    fun `a sign-in someone still waits for is not cut short`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        val release = CountDownLatch(1)
        val asked = CountDownLatch(1)
        whileSigningIn = {
            if (signIns == 1) {
                asked.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
        }
        val first = async(Dispatchers.Default) { runCatching { service.ensureToken() }.exceptionOrNull() }
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        store.handOver("B10000002", "theirs")
        val next = async(Dispatchers.Default) { service.ensureToken() }

        release.countDown()

        // Its answer is the departing account's, so it stores nothing.
        assertTrue(first.await() is LibraryServiceError.CredentialsNotFound)
        assertEquals("token-2", next.await())
        assertEquals(2, signIns)
    }

    /**
     * A sign-in typed after a library sign-out does not wait behind a sign-in with the stored
     * credentials of the account that left, which no one waits for any more.
     */
    @Test
    fun `a sign-in typed after a sign-out does not wait for a stored sign-in no one waits for`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        val release = CountDownLatch(1)
        val asked = CountDownLatch(1)
        whileSigningIn = {
            if (signIns == 1) {
                asked.countDown()
                release.await(15, TimeUnit.SECONDS)
            }
        }
        try {
            val page = launch(Dispatchers.Default) { runCatching { service.ensureToken() } }
            assertTrue(asked.await(5, TimeUnit.SECONDS))
            page.cancel()
            page.join()

            store.signOut()

            assertEquals(
                "token-2",
                withTimeout(5_000) { service.login("B10000002", "theirs", store.libraryAccountGeneration, byUser = true) },
            )
            assertEquals("B10000002", store.libraryUsername)
        } finally {
            release.countDown()
        }
    }

    /** A user name stored before the library form trimmed it can carry spaces around it. */
    @Test
    fun `a stored name with spaces around it is not another account`() = runBlocking {
        store.storeExpired(" b11234567 ", "pw")
        val generation = store.libraryAccountGeneration

        assertTrue(store.saveLibrarySignIn(generation, "B11234567", "pw", "fresh", Long.MAX_VALUE, byUser = true))

        assertEquals(generation, store.libraryAccountGeneration)
    }
}
