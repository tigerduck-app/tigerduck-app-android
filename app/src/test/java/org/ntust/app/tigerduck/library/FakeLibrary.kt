package org.ntust.app.tigerduck.library

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.ntust.app.tigerduck.shared.LibraryAccountGate
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService
import java.io.IOException

/** A library credential store in memory, with the phone's refusal rules. */
internal class MemoryLibraryStore : LibraryCredentialStore {
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

    /** An account whose day-long token has run out, so the next code signs in again. */
    fun storeExpired(username: String = "B10000001", password: String = "pw") {
        libraryUsername = username
        libraryPassword = password
        libraryToken = "expired"
        libraryTokenExpiry = 1L
    }
}

/**
 * The library's sign-in and code endpoints. Each sign-in takes the next of
 * [signInAnswers] (status to body; status 0 is no network), and signs in once
 * they run out; codes always come.
 */
internal class FakeLibraryServer {
    val signInAnswers = ArrayDeque<Pair<Int, String>>()
    var signIns = 0
        private set

    /** Runs while the library is answering a sign-in. */
    var whileSigningIn: () -> Unit = {}

    val interceptor = Interceptor { chain ->
        val path = chain.request().url.encodedPath
        val (status, body) = when {
            path.endsWith(LibraryApi.PATH_LOGIN) -> {
                signIns++
                whileSigningIn()
                signInAnswers.removeFirstOrNull()
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

    fun service(store: LibraryCredentialStore, clock: () -> Long = System::currentTimeMillis) =
        LibraryService(store, extraInterceptors = listOf(interceptor), clock = clock)

    companion object {
        val WRONG_PASSWORD = 200 to """{"data":null,"error":{"code":1,"message":"wrong password"}}"""
        val SERVER_ERROR = 503 to "<html>unavailable</html>"
        val NO_NETWORK = 0 to ""
    }
}
