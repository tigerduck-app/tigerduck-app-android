package org.ntust.app.tigerduck.shared

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.pinnedClientBuilder
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

/**
 * NTUST library passport + virtual-code client. Shared between phone (`:app`)
 * and watch (`:wear`) so the wire schema lives in exactly one place. The
 * backing credential store is provided per-platform: phone uses
 * EncryptedSharedPreferences, watch uses a DataStore mirrored from the phone
 * via the Wearable Data Layer.
 */
class LibraryService(
    private val credentials: LibraryCredentialStore,
    /** True only in debug builds — gates the OkHttp body logging. */
    private val isDebugBuild: Boolean = false,
    /**
     * Interceptors the host app wants on this client. It builds its own
     * rather than taking one from DI so the watch can construct it with no
     * Hilt graph, which also puts it outside anything the phone installs
     * globally — the phone passes its demo-mode kill switch through here.
     */
    extraInterceptors: List<Interceptor> = emptyList(),
    /** Wall-clock ms, for the waits after a failed sign-in with the stored credentials. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** The app's pinned trust, which the library's pins are checked by; null only in tests. */
    tls: PinnedTls? = null,
) {
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TigerDuck-HTTP", LibraryApi.redactSensitive(message))
    }.apply {
        level = if (isDebugBuild) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }

    private val client = pinnedClientBuilder(tls)
        .apply { extraInterceptors.forEach(::addInterceptor) }
        .addInterceptor(loggingInterceptor)
        .build()
    private val gson = Gson()
    private val tokenMutex = Mutex()

    /** What became of a sign-in made with the stored credentials, for the app to act on. */
    enum class StoredSignIn {
        /** A new token is stored. */
        SIGNED_IN,

        /**
         * A new token is stored with a password the library had refused, which
         * ends the count: the phone hands the password to the watch again.
         */
        SIGNED_IN_AFTER_REFUSAL,

        /**
         * The library refused the stored password. It goes again by itself only
         * once [StoredPasswordSchedule.delayMs] has passed ([storedPasswordRetryAt]).
         */
        REFUSED,
    }

    private val _storedSignIns = MutableSharedFlow<StoredSignIn>(extraBufferCapacity = 8)

    /** Each sign-in [ensureToken] makes with the stored credentials that stores a token or is refused. */
    val storedSignIns: SharedFlow<StoredSignIn> = _storedSignIns.asSharedFlow()

    private val _storedPasswordChanges = MutableStateFlow(0)

    /**
     * Moves with each refusal of the stored password counted and each sign-in
     * that ends the count, typed or not: what the phone's retry keys its wait on.
     */
    val storedPasswordChanges: StateFlow<Int> = _storedPasswordChanges.asStateFlow()

    /** The library's refusals in a row of the stored password; 0 with none. */
    val storedPasswordRefusals: Int get() = credentials.libraryPasswordRefusals

    /**
     * When a sign-in the app makes on its own may send the stored password
     * again, which is now or earlier once the wait is over; null with no
     * refusal, or no account stored. As on the iPhone, after the first three
     * refusals in a row it waits 10 seconds, 1 minute and 5 minutes, then 6
     * hours each time ([StoredPasswordSchedule]).
     */
    fun storedPasswordRetryAt(): Long? {
        if (credentials.libraryUsername == null || credentials.libraryPassword == null) return null
        return StoredPasswordSchedule.retryAt(
            credentials.libraryPasswordRefusals,
            credentials.libraryPasswordRefusedAt,
            clock(),
        )
    }

    /** Whether a sign-in the app makes on its own may send the stored password now. */
    fun storedPasswordPostIsDue(): Boolean {
        val now = clock()
        val retryAt = StoredPasswordSchedule.retryAt(
            credentials.libraryPasswordRefusals,
            credentials.libraryPasswordRefusedAt,
            now,
        ) ?: return true
        return retryAt <= now
    }

    /**
     * When a sign-in with the stored credentials last failed short of a
     * refusal, with no answer or a server error. [signInIfCredentialed] waits
     * [SIGN_IN_PAUSE_MS] after it; opening the Library page does not.
     */
    @Volatile
    private var storedSignInFailedAt: Long? = null

    /** Whether [ensureToken] can sign in again by itself once the token runs out. */
    val canSignInWithStoredCredentials: Boolean
        get() = credentials.libraryUsername != null &&
            credentials.libraryPassword != null &&
            !credentials.libraryPasswordRefused

    /**
     * Whether a library account is signed in, as the Library page, Settings and
     * the flip count it: its token is live, or its stored credentials sign in
     * again behind the code. A refused stored password signs it out until the
     * library takes it again, by hand or once its wait is over, and so does no
     * account.
     */
    val hasAccount: Boolean
        get() = credentials.isLibraryTokenValid || canSignInWithStoredCredentials

    /**
     * Signs in with the stored credentials when there is no live token, so the
     * Library page shows its code at once: at launch and on each return to the
     * app. Not while a refusal's wait holds ([storedPasswordRetryAt]), not
     * within [SIGN_IN_PAUSE_MS] of another failure, and not offline. Returns
     * whether it signed in.
     */
    suspend fun signInIfCredentialed(isOnline: () -> Boolean): Boolean {
        if (credentials.isLibraryTokenValid) return false
        if (credentials.libraryUsername == null || credentials.libraryPassword == null) return false
        if (!storedPasswordPostIsDue()) return false
        if (!signInIsDue(storedSignInFailedAt, clock()) || !isOnline()) return false
        return try {
            ensureToken()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Signs in and stores the account and its token. Once the library account
     * has signed out since [accountGeneration] was read, the answer is the
     * departing account's: it stores nothing, and this throws
     * [LibraryServiceError.CredentialsNotFound]. A sign-in the app makes on its
     * own ([byUser] false) also yields to another account stored meanwhile; one
     * the user asked for replaces it. The caller reads the generation from the
     * store when its sign-in starts: right before this call, or earlier, as the
     * school sign-in does before its SSO step. [stillCurrent] is asked as the
     * answer is stored: false stores nothing either, for a caller whose own
     * account, such as the school one it signs in with, changed meanwhile.
     */
    suspend fun login(
        username: String,
        password: String,
        accountGeneration: Int,
        byUser: Boolean,
        stillCurrent: () -> Boolean = { true },
    ): String {
        // A sign-in with the stored credentials of an account that has gone
        // since, which no one waits for, would hold this one behind it.
        synchronized(pendingSignIns) { dropAbandonedSignIns() }
        return tokenMutex.withLock {
            val refused = credentials.libraryPasswordRefused
            loginInternal(username, password, accountGeneration, byUser, stillCurrent)
                // The account stored now is one the library took, so the count is gone.
                .also { if (refused) _storedPasswordChanges.update { it + 1 } }
        }
    }

    /**
     * One try of a stored password the library refused, once its wait is
     * over, for the phone's retry. Says whether it got the library's answer, a
     * refusal or a token, or found nothing left to try; a post lost to the
     * network or met with a server error is no answer, nor is a wait not yet
     * over.
     */
    suspend fun retryStoredPassword(): Boolean {
        val refusals = credentials.libraryPasswordRefusals
        if (refusals == 0 || credentials.libraryUsername == null || credentials.libraryPassword == null) return true
        return try {
            ensureToken()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A refusal it got moved the count; so did a sign-out meanwhile.
            credentials.libraryPasswordRefusals != refusals
        }
    }

    private suspend fun loginInternal(
        username: String,
        password: String,
        accountGeneration: Int,
        byUser: Boolean,
        stillCurrent: () -> Boolean = { true },
    ): String =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(LibraryLoginRequest(username, password))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${LibraryApi.BASE_URL}${LibraryApi.PATH_LOGIN}")
                .post(body)
                .build()

            // A sign-in that stops at a redirect got no answer: it is no refusal of the password.
            (post(request) ?: throw LibraryServiceError.LoginUnavailable(GENERIC_ERROR)).use { response ->
                val answer = loginAnswer(response.code, response.body.string())
                val stored = credentials.saveLibrarySignIn(
                    accountGeneration,
                    username,
                    password,
                    answer.token,
                    answer.expirationTimeStamp,
                    byUser,
                    stillCurrent,
                )
                if (!stored) throw LibraryServiceError.CredentialsNotFound()
                answer.token
            }
        }

    /**
     * Reads the library's answer to a sign-in. A 4xx is a refusal like a wrong
     * password: which status a wrong password gets is unchecked, and a refusal
     * holds the automatic sign-ins back for its wait. A server error or an
     * answer that cannot be read is not.
     */
    private fun loginAnswer(status: Int, body: String): LibraryLoginData {
        val parsed = runCatching { gson.fromJson(body, LibraryLoginResponse::class.java) }.getOrNull()
        val message = serverErrorMessage(status, parsed?.error)
        if (status in 400..499) throw LibraryServiceError.LoginFailed(message)
        if (status !in 200..299 || parsed == null) throw LibraryServiceError.LoginUnavailable(message)
        if (parsed.error?.code?.let { it != 0 } == true) throw LibraryServiceError.LoginFailed(message)
        return parsed.data ?: throw LibraryServiceError.LoginFailed(message)
    }

    /**
     * Returns a valid token, signing in again with the stored credentials once
     * it runs out. Callers that ask while that sign-in is out for the account
     * wait for it and share its outcome, rather than each sending the password.
     */
    suspend fun ensureToken(): String {
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return token
        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound()
        if (credentials.libraryPassword == null) throw LibraryServiceError.CredentialsNotFound()
        // Each refusal counts toward the account's lockout, so a refused
        // password is not sent again by itself before its wait is over.
        if (!storedPasswordPostIsDue()) throw LibraryServiceError.CredentialsNotFound()

        val attempt = synchronized(pendingSignIns) {
            dropAbandonedSignIns()
            // Read under the lock, so no attempt is cancelled for an older
            // generation after this read it and before this joins.
            val key = SignInKey(username, credentials.libraryAccountGeneration)
            // One cancelled as abandoned but not yet done is not joined: its
            // caller would get a cancellation it did not ask for. Nor is one
            // whose answer is in: its outcome is recorded by then, and it
            // leaves this map from a completion handler that runs after its
            // waiters can see it done, so a caller landing in between would
            // share an answer given before it asked. The Library page, opened
            // right after a return's sign-in failed, would get that failure
            // instead of sending again as it is meant to.
            (pendingSignIns[key]?.takeUnless { it.abandoned || it.job.isCompleted } ?: startSignIn(key))
                .also { it.waiting++ }
        }
        try {
            return attempt.job.await()
        } finally {
            synchronized(pendingSignIns) {
                attempt.waiting--
                dropAbandonedSignIns()
            }
        }
    }

    /** The account a sign-in with the stored credentials is for, as it stood when asked. */
    private data class SignInKey(val username: String, val generation: Int)

    private class SignInAttempt(val key: SignInKey) {
        lateinit var job: Deferred<String>

        /** The callers waiting for it; guarded by [pendingSignIns]. */
        var waiting = 0

        /**
         * The generation it signs in at, once it has the lock: the account
         * stored by then, which can be one stored after it was asked for.
         */
        @Volatile
        var signingInAt: Int? = null

        /** The generation its answer is for. */
        val generation: Int get() = signingInAt ?: key.generation

        /** Cancelled because no one waits for it and its account has gone; guarded by [pendingSignIns]. */
        var abandoned = false
    }

    /**
     * Each account's sign-in with the stored credentials that is under way.
     * It runs in [signInScope], so a caller that stops waiting leaves it to
     * finish, and its outcome is kept.
     */
    private val pendingSignIns = HashMap<SignInKey, SignInAttempt>()
    private val signInScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Under the [pendingSignIns] lock. */
    private fun startSignIn(key: SignInKey): SignInAttempt {
        val attempt = SignInAttempt(key)
        attempt.job = signInScope.async(start = CoroutineStart.LAZY) {
            storedSignIn { attempt.signingInAt = it }
        }
        pendingSignIns[key] = attempt
        attempt.job.invokeOnCompletion {
            synchronized(pendingSignIns) {
                if (pendingSignIns[key] === attempt) pendingSignIns.remove(key)
            }
        }
        attempt.job.start()
        return attempt
    }

    /**
     * Cancels each sign-in no caller waits for whose account has signed out or
     * been replaced since: its answer would store nothing, and it would hold
     * the next account's sign-in behind it until the library answered or timed
     * out, as on the watch when the phone hands over another account. One
     * still waited for runs on, and so does one signing in the account stored
     * now. Under the [pendingSignIns] lock.
     */
    private fun dropAbandonedSignIns() {
        val generation = credentials.libraryAccountGeneration
        pendingSignIns.values
            .filter { it.waiting == 0 && it.generation != generation }
            .forEach {
                it.abandoned = true
                it.job.cancel()
            }
    }

    private suspend fun storedSignIn(signingInAt: (Int) -> Unit): String = tokenMutex.withLock {
        // Before the account it signs in with, so a sign-out after this keeps
        // the answer from being stored.
        val accountGeneration = credentials.libraryAccountGeneration
        signingInAt(accountGeneration)
        // A sign-in that landed while this waited for the lock, typed or not.
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return@withLock token
        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound()
        val password = credentials.libraryPassword
            ?: throw LibraryServiceError.CredentialsNotFound()
        // Looked at again holding the lock, so a refusal another caller got
        // just before keeps this one from posting.
        if (!storedPasswordPostIsDue()) throw LibraryServiceError.CredentialsNotFound()
        val refused = credentials.libraryPasswordRefused

        val outcome = try {
            Result.success(loginInternal(username, password, accountGeneration, byUser = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        // Kept whatever happens to the callers: a refusal that went unrecorded
        // would let the password be sent again.
        withContext(NonCancellable) {
            val failure = outcome.exceptionOrNull()
            when {
                failure == null -> {
                    storedSignInFailedAt = null
                    if (refused) {
                        _storedPasswordChanges.update { it + 1 }
                        _storedSignIns.tryEmit(StoredSignIn.SIGNED_IN_AFTER_REFUSAL)
                    } else {
                        _storedSignIns.tryEmit(StoredSignIn.SIGNED_IN)
                    }
                }
                // A failure says something only about the credentials still stored.
                failure is LibraryServiceError.LoginFailed -> {
                    if (credentials.refuseLibraryPassword(accountGeneration, password, clock())) {
                        _storedPasswordChanges.update { it + 1 }
                        _storedSignIns.tryEmit(StoredSignIn.REFUSED)
                    }
                }
                credentials.libraryAccountGeneration == accountGeneration &&
                    credentials.libraryPassword == password -> storedSignInFailedAt = clock()
            }
        }
        outcome.getOrThrow()
    }

    suspend fun generateQRCode(): String = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val body = gson.toJson(LibraryQRRequest(token))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${LibraryApi.BASE_URL}${LibraryApi.PATH_GENERATE_QR}")
            .post(body)
            .build()

        // The token, sent again after a dropped connection, counts toward no lockout.
        val answer = post(request, retryOnConnectionFailure = true)
            ?: throw LibraryServiceError.QRGenerationFailed(GENERIC_ERROR)
        answer.use { response ->
            val responseBody = response.body.string()
            val qrResponse = runCatching {
                gson.fromJson(responseBody, LibraryQRResponse::class.java)
            }.getOrNull()

            if (qrResponse?.data == null ||
                qrResponse.error?.code?.let { it != 0 } == true
            ) {
                throw LibraryServiceError.QRGenerationFailed(
                    serverErrorMessage(response.code, qrResponse?.error)
                )
            }
            qrResponse.data
        }
    }

    /**
     * Posts [request], which carries the password or the token, by [executeCredentialPost]'s
     * rules: no redirect sends it on, to the library itself either, and one that would returns
     * null. As on the iPhone and the Apple Watch.
     */
    private suspend fun post(request: Request, retryOnConnectionFailure: Boolean = false): Response? =
        client.executeCredentialPost(request, retryOnConnectionFailure = retryOnConnectionFailure) { await(it) }

    /**
     * Waits for [call]'s response, cancelling the call when the caller is cancelled. A blocking
     * execute() went on until the library answered or timed out, and held a caller that had
     * already moved on, such as the watch's page after the phone handed over another account.
     */
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { waiting ->
        waiting.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = waiting.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) =
                waiting.resume(response) { _, unused, _ -> unused.close() }
        })
    }

    /**
     * Build a human-readable error message that never collapses to blank. The
     * library API sometimes responds with `error.message = ""` (e.g. on a wrong
     * password), and Elvis `?:` alone passes that empty string through — which
     * in turn renders as a red box with no text in the UI. Treat blank as
     * missing, and tack on the HTTP status when we have nothing better.
     */
    private fun serverErrorMessage(httpStatus: Int, error: LibraryApiError?): String {
        val msg = error?.message?.takeUnless { it.isBlank() }
        if (msg != null) return msg
        return if (httpStatus in 200..299) GENERIC_ERROR else "$GENERIC_ERROR (HTTP $httpStatus)"
    }

    companion object {
        // Localized error strings live in the UI layer of each module; the
        // shared service falls back to a stable non-empty marker so callers
        // can still display *something* if the server omits a message.
        private const val GENERIC_ERROR = "Library request failed"

        /** How long a return to the app waits after a sign-in that failed short of a refusal. */
        const val SIGN_IN_PAUSE_MS = 5 * 60 * 1000L

        /**
         * Whether a return to the app may sign in again. Measured from the
         * failure, so a clock set back does not stretch the pause.
         */
        fun signInIsDue(lastFailure: Long?, now: Long): Boolean {
            if (lastFailure == null) return true
            val age = now - lastFailure
            return age >= SIGN_IN_PAUSE_MS || age < 0
        }
    }
}
