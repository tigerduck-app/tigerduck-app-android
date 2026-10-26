package org.ntust.app.tigerduck.shared

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The sign-ins made with the stored library credentials: at a return to the
 * app and behind the Library page. Each refusal counts toward the account's
 * lockout, so after one the password waits the school's schedule before it is
 * sent again by itself (tigerduck-app/tigerduck-app#769), and other failures
 * pause the returns for five minutes.
 */
class LibraryServiceStoredSignInTest {

    private class MemoryStore : LibraryCredentialStore {
        private val account = LibraryAccountGate()
        override var libraryUsername: String? = null
        override var libraryPassword: String? = null
        override var libraryToken: String? = null
        override var libraryTokenExpiry: Long = 0L
        override var libraryPasswordRefusals: Int = 0
        override var libraryPasswordRefusedAt: Long = 0L
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
            libraryPasswordRefusals = 0
            libraryPasswordRefusedAt = 0L
        }

        override fun refuseLibraryPassword(generation: Int, password: String, at: Long): Boolean =
            account.whileCurrent(generation) {
                if (libraryPassword != password) return@whileCurrent false
                libraryPasswordRefusals++
                libraryPasswordRefusedAt = at
                true
            } ?: false

        fun signOut() = account.signOut {
            libraryUsername = null
            libraryPassword = null
            libraryToken = null
            libraryTokenExpiry = 0L
            libraryPasswordRefusals = 0
            libraryPasswordRefusedAt = 0L
        }

        /** An account whose token has run out, so the next code signs in again. */
        fun storeExpired(username: String = "B10000001", password: String = "pw") {
            libraryUsername = username
            libraryPassword = password
            libraryToken = "expired"
            libraryTokenExpiry = 1L
        }

        /** Another account handed over, as the phone hands one to the watch, its token run out. */
        fun handOver(username: String, password: String) = account.replaceAccount { storeExpired(username, password) }
    }

    private val store = MemoryStore().apply { storeExpired() }

    /** The library's answers to sign-ins, by status and body; past the end it signs in. */
    private val answers = ArrayDeque<Pair<Int, String>>()
    private var signIns = 0
    private var whileSigningIn: () -> Unit = {}
    private var now = 1_000_000L

    private val library = Interceptor { chain ->
        val path = chain.request().url.encodedPath
        val (status, body) = when {
            path.endsWith(LibraryApi.PATH_LOGIN) -> {
                signIns++
                whileSigningIn()
                answers.removeFirstOrNull()
                    ?: (200 to """{"data":{"username":"B10000001","token":"token-$signIns","expirationTimeStamp":${Long.MAX_VALUE}}}""")
            }
            path.endsWith(LibraryApi.PATH_GENERATE_QR) -> 200 to """{"data":"code"}"""
            else -> error("unexpected request to $path")
        }
        if (status == 0) throw IOException("no network")
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("status")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val service = LibraryService(store, extraInterceptors = listOf(library), clock = { now })

    private val wrongPassword = 200 to """{"data":null,"error":{"code":1,"message":"wrong password"}}"""
    private val serverError = 503 to "<html>unavailable</html>"

    private fun outcomes(): MutableList<LibraryService.StoredSignIn> {
        val seen = Collections.synchronizedList(mutableListOf<LibraryService.StoredSignIn>())
        val ready = CountDownLatch(1)
        Thread {
            runBlocking {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    service.storedSignIns.collect { seen += it }
                }
                ready.countDown()
            }
        }.apply { isDaemon = true }.start()
        ready.await(5, TimeUnit.SECONDS)
        return seen
    }

    @Test
    fun `an expired token is renewed with the stored credentials`() = runBlocking {
        assertEquals("code", service.generateQRCode())
        assertEquals(1, signIns)
        assertEquals("token-1", store.libraryToken)
    }

    @Test
    fun `a refused password is not sent again by itself until its wait is over`() = runBlocking {
        answers += wrongPassword

        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.LoginFailed)
        assertTrue(store.libraryPasswordRefused)
        assertFalse(service.canSignInWithStoredCredentials)
        assertEquals(now + 10_000L, service.storedPasswordRetryAt())

        now += 9_999
        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.CredentialsNotFound)
        assertFalse(service.signInIfCredentialed { true })
        assertEquals(1, signIns)

        now += 1
        assertEquals("token-2", service.ensureToken())
        assertEquals(2, signIns)
    }

    @Test
    fun `each refusal in a row waits the next step of the school's schedule`() = runBlocking {
        listOf(10_000L, 60_000L, 300_000L, 21_600_000L, 21_600_000L).forEach { wait ->
            answers += wrongPassword
            assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.LoginFailed)
            assertEquals(now + wait, service.storedPasswordRetryAt())
            now += wait - 1
            assertFalse(service.storedPasswordPostIsDue())
            now += 1
            assertTrue(service.storedPasswordPostIsDue())
        }
        assertEquals(5, store.libraryPasswordRefusals)
    }

    @Test
    fun `a password the library takes again ends the count`() = runBlocking {
        answers += wrongPassword
        runCatching { service.ensureToken() }
        now += 10_000

        service.ensureToken()

        assertEquals(0, store.libraryPasswordRefusals)
        assertTrue(service.canSignInWithStoredCredentials)
        assertEquals(null, service.storedPasswordRetryAt())
    }

    @Test
    fun `a sign-in the user types never waits`() = runBlocking {
        answers += wrongPassword
        runCatching { service.ensureToken() }

        service.login("B10000001", "pw", store.libraryAccountGeneration, byUser = true)

        assertEquals(2, signIns)
        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `a retry says whether it got the library's answer`() = runBlocking {
        answers += wrongPassword
        runCatching { service.ensureToken() }

        // Not due yet: nothing goes, and it got no answer.
        assertFalse(service.retryStoredPassword())
        assertEquals(1, signIns)

        now += 10_000
        answers += serverError
        assertFalse(service.retryStoredPassword())
        answers += wrongPassword
        assertTrue(service.retryStoredPassword())
        assertEquals(2, store.libraryPasswordRefusals)

        now += 60_000
        assertTrue(service.retryStoredPassword())
        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `any 4xx counts as a refusal`() = runBlocking {
        answers += 401 to ""

        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.LoginFailed)
        assertEquals(1, store.libraryPasswordRefusals)
    }

    @Test
    fun `a server error is no refusal`() = runBlocking {
        answers += serverError

        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.LoginUnavailable)
        assertFalse(store.libraryPasswordRefused)
        assertTrue(service.canSignInWithStoredCredentials)
    }

    @Test
    fun `a sign-in the user types clears the refusal`() = runBlocking {
        answers += wrongPassword
        runCatching { service.ensureToken() }

        service.login("B10000001", "new", store.libraryAccountGeneration, byUser = true)

        assertFalse(store.libraryPasswordRefused)
        assertTrue(service.canSignInWithStoredCredentials)
    }

    @Test
    fun `a refusal for an account that signed out meanwhile is not kept`() = runBlocking {
        answers += wrongPassword
        whileSigningIn = {
            store.signOut()
            store.storeExpired(username = "B10000002", password = "theirs")
        }

        runCatching { service.ensureToken() }

        assertFalse(store.libraryPasswordRefused)
    }

    @Test
    fun `a refusal tells the app, and so does a renewed token`() = runBlocking {
        val seen = outcomes()
        service.ensureToken()
        store.storeExpired()
        answers += wrongPassword
        runCatching { service.ensureToken() }

        waitFor { seen.size == 2 }
        assertEquals(listOf(LibraryService.StoredSignIn.SIGNED_IN, LibraryService.StoredSignIn.REFUSED), seen.toList())
    }

    @Test
    fun `a password the library takes again tells the app so, for the watch to get it back`() = runBlocking {
        val seen = outcomes()
        answers += wrongPassword
        runCatching { service.ensureToken() }
        now += 10_000

        service.ensureToken()

        waitFor { seen.size == 2 }
        assertEquals(
            listOf(LibraryService.StoredSignIn.REFUSED, LibraryService.StoredSignIn.SIGNED_IN_AFTER_REFUSAL),
            seen.toList(),
        )
    }

    @Test
    fun `each refusal counted and each end of the count moves the changes`() = runBlocking {
        val before = service.storedPasswordChanges.value
        answers += wrongPassword
        runCatching { service.ensureToken() }
        assertEquals(before + 1, service.storedPasswordChanges.value)

        now += 10_000
        service.ensureToken()
        assertEquals(before + 2, service.storedPasswordChanges.value)

        // A token renewed with no refusal to end leaves them.
        store.storeExpired()
        service.ensureToken()
        assertEquals(before + 2, service.storedPasswordChanges.value)
    }

    @Test
    fun `a return sends a refused password once its wait is over, and not before`() = runBlocking {
        answers += wrongPassword
        assertFalse(service.signInIfCredentialed { true })

        now += 9_999
        assertFalse(service.signInIfCredentialed { true })
        assertEquals(1, signIns)

        now += 1
        assertTrue(service.signInIfCredentialed { true })
        assertEquals(2, signIns)
        assertTrue(service.hasAccount)
    }

    @Test
    fun `a return signs in with no live token`() = runBlocking {
        assertTrue(service.signInIfCredentialed { true })
        assertEquals("token-1", store.libraryToken)
    }

    @Test
    fun `a return does nothing with a live token, without credentials, or offline`() = runBlocking {
        service.ensureToken()
        assertFalse(service.signInIfCredentialed { true })

        store.signOut()
        assertFalse(service.signInIfCredentialed { true })

        store.storeExpired()
        assertFalse(service.signInIfCredentialed { false })
        assertEquals(1, signIns)
    }

    @Test
    fun `after a failure short of a refusal a return waits five minutes`() = runBlocking {
        answers += serverError
        assertFalse(service.signInIfCredentialed { true })

        now += LibraryService.SIGN_IN_PAUSE_MS - 1
        assertFalse(service.signInIfCredentialed { true })
        assertEquals(1, signIns)

        now += 1
        assertTrue(service.signInIfCredentialed { true })
        assertEquals(2, signIns)
    }

    @Test
    fun `opening the library page does not wait out the pause`() = runBlocking {
        answers += 0 to ""
        assertFalse(service.signInIfCredentialed { true })

        assertEquals("code", service.generateQRCode())
        assertEquals(2, signIns)
    }

    @Test
    fun `a clock set back does not stretch the pause`() {
        assertTrue(LibraryService.signInIsDue(lastFailure = null, now = 0))
        assertFalse(LibraryService.signInIsDue(lastFailure = 1_000, now = 2_000))
        assertTrue(LibraryService.signInIsDue(lastFailure = 10_000, now = 5_000))
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) delay(10)
    }

    /** Two callers at once, as the return to the app and the Library page opening together. */
    private fun twoCallersWhileTheLibraryAnswers(answer: Pair<Int, String>): List<Throwable?> {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers += answer
        whileSigningIn = {
            asked.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        return runBlocking {
            val first = async(Dispatchers.IO) { runCatching { service.ensureToken() }.exceptionOrNull() }
            asked.await(5, TimeUnit.SECONDS)
            val second = async(Dispatchers.IO) { runCatching { service.ensureToken() }.exceptionOrNull() }
            delay(100)
            release.countDown()
            listOf(first.await(), second.await())
        }
    }

    @Test
    fun `callers that ask while a sign-in fails with a server error share its failure`() {
        val failures = twoCallersWhileTheLibraryAnswers(serverError)

        assertEquals(1, signIns)
        assertTrue(failures.all { it is LibraryServiceError.LoginUnavailable })
        assertFalse(store.libraryPasswordRefused)
        // The pause started once, for both.
        assertFalse(runBlocking { service.signInIfCredentialed { true } })
        assertEquals(1, signIns)
    }

    @Test
    fun `callers that ask while the library refuses the password share the refusal and its message`() {
        val failures = twoCallersWhileTheLibraryAnswers(401 to """{"data":null,"error":{"code":401,"message":"wrong password"}}""")

        assertEquals(1, signIns)
        assertTrue(failures.all { it is LibraryServiceError.LoginFailed })
        assertEquals(listOf("wrong password", "wrong password"), failures.map { it?.message })
        assertTrue(store.libraryPasswordRefused)
    }

    @Test
    fun `a caller that stops waiting does not lose the refusal`() = runBlocking {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers += wrongPassword
        whileSigningIn = {
            asked.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val caller = launch(Dispatchers.IO) { runCatching { service.ensureToken() } }
        asked.await(5, TimeUnit.SECONDS)
        caller.cancel()
        release.countDown()

        waitFor { store.libraryPasswordRefused }
        assertTrue(store.libraryPasswordRefused)
        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.CredentialsNotFound)
        assertEquals(1, signIns)
    }

    /**
     * A sign-in asked for one account can, once it has the lock, sign in the account stored by
     * then. That one is current, so it runs on when its caller stops waiting.
     */
    @Test
    fun `a sign-in for the account stored now runs on though it was asked for the one before`() = runBlocking {
        val typing = CountDownLatch(1)
        val typed = CountDownLatch(1)
        val signingIn = CountDownLatch(1)
        val answer = CountDownLatch(1)
        answers += serverError
        whileSigningIn = {
            when (signIns) {
                1 -> {
                    typing.countDown()
                    typed.await(10, TimeUnit.SECONDS)
                }
                2 -> {
                    signingIn.countDown()
                    answer.await(10, TimeUnit.SECONDS)
                }
            }
        }
        // A sign-in typed on the form holds the lock and fails.
        val form = launch(Dispatchers.IO) {
            runCatching { service.login("B10000001", "pw", store.libraryAccountGeneration, byUser = true) }
        }
        assertTrue(typing.await(5, TimeUnit.SECONDS))
        val page = launch(Dispatchers.IO) { runCatching { service.ensureToken() } }
        delay(500)
        store.handOver("B10000002", "theirs")
        typed.countDown()
        assertTrue(signingIn.await(5, TimeUnit.SECONDS))

        page.cancel()
        page.join()
        answer.countDown()
        form.join()

        waitFor { store.libraryToken == "token-2" }
        assertEquals("B10000002", store.libraryUsername)
        assertEquals("token-2", store.libraryToken)
        assertEquals(2, signIns)
    }
}
