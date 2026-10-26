package org.ntust.app.tigerduck.shared

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What answers the library's sign-in and code requests, which carry the password and the token:
 * no 307 or 308 sends either on, to the library itself either, and a sign-in that stops at one is
 * no refusal of the password. As on the iPhone and the Apple Watch, for the phone and the watch
 * alike.
 *
 * The library stands in as an application interceptor, which answers ahead of OkHttp's own
 * redirect handling, so it follows a redirect itself the way OkHttp does whenever the client it
 * answers for follows redirects: a 307 or a 308 with the same method and body.
 */
class LibraryServiceRedirectTest {

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
    }

    private val store = MemoryStore().apply {
        libraryUsername = "B00015999"
        libraryPassword = "pw"
        libraryToken = "expired"
        libraryTokenExpiry = 1L
    }

    /** Every request the library got, as "METHOD url". */
    private val requests = CopyOnWriteArrayList<String>()

    /** Whether each request would be sent again after a dropped connection, by its path. */
    private val retries = CopyOnWriteArrayList<Pair<String, Boolean>>()

    /** What the library answers a sign-in with. */
    private var signInAnswer: (Request) -> Response = { json(it, SIGNED_IN) }

    /** What the library answers a code request with. */
    private var codeAnswer: (Request) -> Response = { json(it, """{"data":"code"}""") }

    private val library = Interceptor { chain ->
        retries += chain.request().url.encodedPath to chain.retryOnConnectionFailure
        var request = chain.request()
        var response = answer(request)
        while (chain.followRedirects && response.isRedirect) {
            val next = request.url.resolve(response.header("Location")!!)!!
            response.close()
            request = if (response.code == 307 || response.code == 308) {
                request.newBuilder().url(next).build()
            } else {
                request.newBuilder().url(next).get().build()
            }
            response = answer(request)
        }
        response
    }

    private fun answer(request: Request): Response {
        requests += "${request.method} ${request.url}"
        val url = request.url.toString()
        return when (url) {
            SIGN_IN_URL -> signInAnswer(request)
            CODE_URL -> codeAnswer(request)
            else -> json(request, """{"data":null}""")
        }
    }

    private val service = LibraryService(store, extraInterceptors = listOf(library))

    private val posts get() = requests.filter { it.startsWith("POST") }

    @Test
    fun `a 307 after a sign-in sends the password nowhere else`() = runBlocking {
        signInAnswer = { redirect(it, 307, ELSEWHERE) }

        val failure = runCatching {
            service.login("B00015999", "pw", store.libraryAccountGeneration, byUser = true)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is LibraryServiceError.LoginUnavailable)
        assertEquals(listOf("POST $SIGN_IN_URL"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    @Test
    fun `a 308 after a sign-in is not followed to the library itself either`() = runBlocking {
        signInAnswer = { redirect(it, 308, "$SIGN_IN_URL?node=2") }

        val failure = runCatching {
            service.login("B00015999", "pw", store.libraryAccountGeneration, byUser = true)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is LibraryServiceError.LoginUnavailable)
        assertEquals(listOf("POST $SIGN_IN_URL"), posts)
    }

    @Test
    fun `a stored sign-in that stops at a 307 is no refused password`() = runBlocking {
        signInAnswer = { redirect(it, 307, ELSEWHERE) }

        val failure = runCatching { service.generateQRCode() }.exceptionOrNull()

        assertTrue("got $failure", failure is LibraryServiceError.LoginUnavailable)
        assertFalse(store.libraryPasswordRefused)
        assertTrue(service.canSignInWithStoredCredentials)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    /**
     * As on the iPhone, where a redirect refused ends in a network error: no answer to the
     * password, so the retry of a refused one goes again a step further down the schedule rather
     * than counting another refusal (tigerduck-app/tigerduck-app#769).
     */
    @Test
    fun `a retry of a refused password that stops at a 307 is no answer, and no refusal`() = runBlocking {
        store.libraryPasswordRefusals = 1
        store.libraryPasswordRefusedAt = 1L
        signInAnswer = { redirect(it, 307, ELSEWHERE) }

        assertFalse(service.retryStoredPassword())

        assertEquals(1, store.libraryPasswordRefusals)
        assertEquals(1L, store.libraryPasswordRefusedAt)
        assertEquals(listOf("POST $SIGN_IN_URL"), posts)
    }

    @Test
    fun `a 308 after a code request sends the token nowhere else`() = runBlocking {
        store.libraryToken = "valid"
        store.libraryTokenExpiry = Long.MAX_VALUE
        codeAnswer = { redirect(it, 308, ELSEWHERE) }

        val failure = runCatching { service.generateQRCode() }.exceptionOrNull()

        assertTrue("got $failure", failure is LibraryServiceError.QRGenerationFailed)
        assertEquals(listOf("POST $CODE_URL"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    @Test
    fun `a sign-in and a code request the library answers go through`() = runBlocking {
        assertEquals("code", service.generateQRCode())

        assertEquals(listOf("POST $SIGN_IN_URL", "POST $CODE_URL"), posts)
        assertEquals("token-1", store.libraryToken)
    }

    @Test
    fun `a sign-in is never sent again after a dropped connection, a code request may be`() = runBlocking {
        service.generateQRCode()

        // The password, sent again, would count twice toward the account's lockout; the token
        // counts toward none.
        assertEquals(
            listOf("/v1${LibraryApi.PATH_LOGIN}" to false, "/v1${LibraryApi.PATH_GENERATE_QR}" to true),
            retries,
        )
    }

    private fun redirect(request: Request, code: Int, location: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Redirect")
        .header("Location", location)
        .body("".toResponseBody(null))
        .build()

    private fun json(request: Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private companion object {
        const val SIGN_IN_URL = "${LibraryApi.BASE_URL}${LibraryApi.PATH_LOGIN}"
        const val CODE_URL = "${LibraryApi.BASE_URL}${LibraryApi.PATH_GENERATE_QR}"
        const val ELSEWHERE = "https://elsewhere.example/collect"
        val SIGNED_IN = """{"data":{"username":"B00015999","token":"token-1","expirationTimeStamp":${Long.MAX_VALUE}}}"""
    }
}
