package org.ntust.app.tigerduck.auth

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.canonicalBackend
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.currentDeviceModel
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** A request that needs a session got no access token, so it was not sent. */
class NotSignedInException : Exception("not authenticated")

/**
 * Manages v3 JWT access and refresh tokens, and signs in again on its own
 * when they are refused or gone.
 * Tokens are stored in EncryptedSharedPreferences via [V3TokenStore].
 */
class AuthTokenManager(
    private val tokens: V3TokenStore,
    private val httpClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val deviceUuid: String,
    private val now: () -> Long = System::currentTimeMillis,
    // The unregister a sign-out owes, which a login waits for and then ends; see [login].
    private val owedUnregister: OwedUnregister? = null,
    // How long a departing refresh waits for a session refresh of its token on its way.
    private val departingWaitMs: Long = REFRESH_TIMEOUT_SECONDS * 1000,
    signInCallTimeoutMs: Long = SIGN_IN_CALL_TIMEOUT_SECONDS * 1_000,
) {
    private val refreshMutex = Mutex()

    // Backend logins, one at a time; see [login].
    private val loginQueue = Mutex()
    private val jsonType = "application/json".toMediaType()

    /**
     * Signs in with the stored account, passing the generation it is given to
     * [login], and says what it sent: AuthService's. Run only by the automatic
     * sign-in: after a refused refresh, for [signInAgain], and when something
     * finds no session.
     */
    var signInWithStoredAccount: (suspend (signInGeneration: Int) -> SignInAttempt)? = null

    // OkHttp sends a failed request again on its own, up to three sends of one
    // refresh token in all, and the backend takes a third send as reuse; for a
    // reply that asks for a send again, see [oneShot]. The timeout keeps the
    // two sends [sendWithOneResend] makes inside the backend's 60 s grace.
    private val refreshClient = httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .callTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // For the sign-in, which an automatic one makes under NonCancellable while
    // callers wait on it; see SIGN_IN_CALL_TIMEOUT_SECONDS.
    private val signInClient = httpClient.newBuilder()
        .callTimeout(signInCallTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    // The sign-in, through signInClient, at the issuer the login reads once.
    private val signIns = BackendServices(AuthApi::class.java, signInClient)

    // When a refresh last got a 429 or a 5xx, or could not be sent; read and
    // written under refreshMutex.
    private var refreshPausedAt: Long? = null

    // Held from reading generation through the token write that depends on
    // it, so a sign-out cannot fall between a reply's check and its save.
    private val signInLock = Any()
    private var generation = 0

    // Under signInLock: the sign-in that automatic callers which overlap it
    // wait for, and when one that sent credentials last failed.
    private var signInUnderWay: CompletableDeferred<Boolean>? = null
    private var signInFailedAt: Long? = null

    /**
     * Moved by [logout]. A login or refresh reply for an earlier value belongs
     * to an account that has signed out, so it stores nothing. Work that reads
     * the credentials and then suspends reads this first and passes it to
     * [login].
     */
    val signInGeneration: Int get() = synchronized(signInLock) { generation }

    private val _lastSignInFailed = MutableStateFlow(false)

    /**
     * Whether the last sign-in that had credentials to send failed: from then
     * until another starts, one succeeds or the account signs out. The
     * TigerSync status asks the user to sign in again while it holds and
     * there is no session.
     */
    val lastSignInFailed: StateFlow<Boolean> = _lastSignInFailed.asStateFlow()

    // Resolve per-call so the debug API-endpoint override applies immediately,
    // matching PushApiClient — pinning at construction made login/refresh
    // traffic ignore an override that push traffic honored, minting tokens on
    // one host and presenting them to another.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')

    val isLoggedIn: Boolean get() = tokens.refreshToken != null

    /**
     * Whether this phone holds a session from [base]. Signing in moves the device to the account
     * at that backend alone, so an unregister a sign-out owes another backend still stands.
     */
    fun hasSessionAt(base: String): Boolean = synchronized(signInLock) {
        tokens.refreshToken != null && storedIssuer() == canonicalBackend(base)
    }

    // The access tokens this process stored or handed out, with the backend each came from, for
    // [mayPresent]. Kept past a sign-out, so a request built before it can still reach that
    // backend and no other; a few, as only the last pairs can still be on their way.
    // Under signInLock.
    private val issuers = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KNOWN_ISSUERS
    }

    // Under signInLock. A pair stored before its backend was recorded is the endpoint's, as
    // departingSession takes it.
    private fun storedIssuer(): String = tokens.issuedBy?.let(::canonicalBackend) ?: baseUrl

    // Under signInLock.
    private fun issued(access: String): String = access.also { issuers[it] = storedIssuer() }

    /**
     * Whether [accessToken] may go to [url]: only to the backend that issued it, and an access
     * token this phone never held nowhere. The backend clients' last check before a request
     * leaves ([SessionHostGuard]), so a request that read its address and its header at different
     * times, around a change of the API endpoint setting, still goes nowhere else.
     */
    fun mayPresent(accessToken: String, url: HttpUrl): Boolean {
        val issuer = synchronized(signInLock) {
            issuers[accessToken] ?: storedIssuer().takeIf { tokens.accessToken == accessToken }
        }?.toHttpUrlOrNull() ?: return false
        if (url.scheme != issuer.scheme || url.host != issuer.host || url.port != issuer.port) return false
        val path = issuer.encodedPath.trimEnd('/')
        return url.encodedPath == path || url.encodedPath.startsWith("$path/")
    }

    suspend fun authHeader(): String? {
        val access = validAccessToken() ?: return null
        return "Bearer $access"
    }

    /**
     * [authHeader] for a request that needs a session. Without one the request
     * is not sent, as the backend could only refuse it with 401.
     */
    suspend fun requireAuthHeader(): String = authHeader() ?: throw NotSignedInException()

    /**
     * [requireAuthHeader] pinned to sign-in [signIn] ([signInGeneration]): once
     * that account has signed out, none, rather than the next account's
     * token. For work that read its data as one account and sends it later.
     */
    suspend fun requireAuthHeader(signIn: Int): String {
        if (signInGeneration != signIn || !signInIfSignedOut()) throw NotSignedInException()
        val access = validTokenIfSignedIn(signIn) ?: refresh(signIn) ?: throw NotSignedInException()
        return "Bearer $access"
    }

    /**
     * Signs in when there is no session, and says whether there is one now.
     * Runs on each return to the app, before a registration or a sync, and
     * from [authHeader].
     */
    suspend fun signInIfSignedOut(): Boolean {
        if (!isLoggedIn) signInAutomatically()
        return isLoggedIn
    }

    /**
     * Signs in again after the backend refused a session that looked valid, and says whether there
     * is a session to retry with. Only when the access token stored is one the backend refused
     * ([noteRefused]), or none is: a login ends the device's other sessions of the account
     * (tigerduck-backend#213), so a request refused with a pair a relogin or a refresh has since
     * replaced would otherwise end that relogin's session, and with steady traffic every refused
     * request would end the last one's. Then the caller retries with the stored pair instead.
     */
    suspend fun signInAgain(): Boolean {
        val replacing = synchronized(signInLock) {
            val refresh = tokens.refreshToken
            // Stored and not refused: the caller retries with it.
            if (refresh != null && tokens.accessToken.let { it != null && it != refusedAccess }) return true
            refresh
        }
        return signInAutomatically(replacing = replacing)
    }

    /**
     * Notes that the backend answered [accessToken] 401, for [signInAgain]: only while it is the
     * one stored, as a refusal of a pair replaced since says nothing of the stored one. From the
     * backend clients' guard ([SessionHostGuard]), which every request with the session's header
     * passes.
     */
    fun noteRefused(accessToken: String) {
        synchronized(signInLock) { if (tokens.accessToken == accessToken) refusedAccess = accessToken }
    }

    // Under signInLock. The stored access token the backend refused, if it still is the stored one.
    private var refusedAccess: String? = null

    /**
     * The pair, by its refresh token, that an automatic sign-in after a refusal is to replace: a
     * login that finds another stored at its turn, which a refresh or a sign-in has stored since,
     * sends nothing ([login]), and the sign-in counts as done ([runSignIn]). In the coroutine
     * context, which carries it through AuthService's sign-in to [login].
     */
    private class Replacing(val refreshToken: String) : AbstractCoroutineContextElement(Replacing) {
        companion object Key : CoroutineContext.Key<Replacing>
    }

    /** The login a pair stored since its sign-in began made moot; see [Replacing]. */
    class SessionReplacedException : AuthException("a pair was stored since; no login sent")

    /**
     * As [signInIfSignedOut], without waiting out the pause after a failed sign-in: for the try of
     * a stored password the school refused ([StoredPasswordRetry]), which its own schedule paces
     * and which may have just got the Moodle token the failed one lacked.
     */
    suspend fun signInIfSignedOutNow(): Boolean {
        if (!isLoggedIn) signInAutomatically(waitOutPause = false)
        return isLoggedIn
    }

    /**
     * Runs the interactive sign-in's [signIn], which calls [login] with the
     * generation it is given, and says whether it signed in. It never waits
     * for the pause. An automatic sign-in that starts meanwhile waits for it
     * instead of logging in too, and a failure starts the pause as an
     * automatic one does.
     */
    suspend fun signInInteractively(signIn: suspend (signInGeneration: Int) -> SignInAttempt): Boolean {
        val attempt = CompletableDeferred<Boolean>()
        val current = synchronized(signInLock) {
            signInUnderWay = attempt
            _lastSignInFailed.value = false
            generation
        }
        return runSignIn(attempt, current, signInWith = signIn)
    }

    /**
     * Runs [signInWithStoredAccount], or waits for the sign-in under way. For
     * [SIGN_IN_PAUSE_MS] after one with credentials to send failed, it makes
     * none and returns false: the backend allows 5 logins per 15 minutes per
     * student and per IP, and a campus network puts many students behind one
     * IP.
     */
    private suspend fun signInAutomatically(waitOutPause: Boolean = true, replacing: String? = null): Boolean {
        val signInWith = signInWithStoredAccount ?: return false
        val attempt = CompletableDeferred<Boolean>()
        val signIn: Int
        // The sign-in already under way, which this caller waits for instead.
        val running = synchronized(signInLock) {
            // Before the credentials, which the sign-in reads.
            signIn = generation
            signInUnderWay ?: run {
                if (waitOutPause && isSignInPaused()) return false
                signInUnderWay = attempt
                _lastSignInFailed.value = false
                null
            }
        }
        if (running != null) return running.await()
        // Finishes for the callers waiting on it when this one is cancelled.
        return withContext(NonCancellable + (replacing?.let(::Replacing) ?: EmptyCoroutineContext)) {
            runSignIn(attempt, signIn, replacing) { generation ->
                // A failure of the sign-in itself is the caller's no session,
                // never an exception out of its token lookup.
                try {
                    signInWith(generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sign-in failed: ${e.logSummary()}")
                    SignInAttempt.FAILED
                }
            }
        }
    }

    /**
     * Runs [signInWith] as the sign-in under way, which [attempt] tells its waiting callers about.
     * One meant to replace the pair [replacing] whose login a pair stored since made moot counts
     * as signed in, with that pair, and starts no pause.
     */
    private suspend fun runSignIn(
        attempt: CompletableDeferred<Boolean>,
        signIn: Int,
        replacing: String? = null,
        signInWith: suspend (Int) -> SignInAttempt,
    ): Boolean {
        var signedIn = false
        try {
            val answer = signInWith(signIn)
            signedIn = settleSignIn(signIn, if (replacing != null && replacedSince(replacing)) SignInAttempt.SIGNED_IN else answer)
        } finally {
            synchronized(signInLock) { if (signInUnderWay === attempt) signInUnderWay = null }
            attempt.complete(signedIn)
        }
        return signedIn
    }

    private fun settleSignIn(signIn: Int, attempt: SignInAttempt): Boolean = synchronized(signInLock) {
        // Signed out since: the attempt was the departing account's, and the
        // sign-out ended any pause.
        if (generation != signIn) return@synchronized false
        when (attempt) {
            SignInAttempt.SIGNED_IN -> signInFailedAt = null
            SignInAttempt.FAILED -> signInFailedAt = now()
            SignInAttempt.NOTHING_TO_SEND -> Unit
        }
        _lastSignInFailed.value = attempt == SignInAttempt.FAILED
        attempt == SignInAttempt.SIGNED_IN
    }

    // Measured from the failure, as the refresh pause is, so a clock set back
    // does not stretch it.
    private fun isSignInPaused(): Boolean {
        val failedAt = signInFailedAt ?: return false
        return now() - failedAt in 0 until SIGN_IN_PAUSE_MS
    }

    /**
     * The session as it stands, for a sign-out to take before [logout] wipes
     * it: the device unregister goes out as the departing account after. Null
     * without one.
     */
    fun departingSession(): DepartingSession? = synchronized(signInLock) {
        val access = tokens.accessToken ?: return null
        val refresh = tokens.refreshToken ?: return null
        // The backend recorded at sign-in; the one set now for a pair stored before that was.
        // The count of the token's sends goes with it as the disk has it: every send that may
        // have reached the backend, from before a restart or answered with a 5xx too, and one
        // still on its way, counted there before it went out. That one stays counted even when
        // it comes back unused: once the pair is gone the count cannot be put back on disk, and
        // the departing refresh must never be the token's third send (MAX_REFRESH_TOKEN_SENDS).
        // This process's own count of replies lost is taken as well; the disk's is never below it.
        DepartingSession(
            access, refresh, tokens.expiresAt, storedIssuer(), tokens.studentId,
            maxOf(sendsWithoutReply(refresh), tokens.refreshTokenSends),
        )
    }

    /**
     * Sends one refresh of a [DepartingSession] for the sign-out's device
     * unregister. Stores nothing: the pair is the departing account's, and the
     * session here is whoever signs in next.
     *
     * [sendsWithoutReply] is how many sends of the token may have reached the
     * backend without a pair coming back, which the caller keeps with the debt
     * across launches, counted before each send goes out; this process's own
     * count of replies lost is taken too. At two, nothing is sent and the
     * refresh is [DepartingRefresh.Refused]: within its grace the backend
     * answers a rotated token once more, and takes a later send of it for
     * reuse. A send the backend did not use is [DepartingRefresh.Unused], for
     * the caller to leave uncounted; a 5xx is [DepartingRefresh.Unavailable],
     * which stays counted.
     *
     * [mayDepart] is asked right before the token would go out, after any wait
     * for a refresh on its way: false, nothing is sent, and the refresh is
     * [DepartingRefresh.Unused]. A pair already back is still handed over.
     */
    suspend fun refreshDeparting(
        departing: DepartingSession,
        sendsWithoutReply: Int,
        mayDepart: () -> Boolean = { true },
    ): DepartingRefresh {
        val token = departing.refreshToken
        // The session's own refresh of this token may still be on its way, sent
        // before the sign-out: its pair is waited for and taken rather than the
        // token sent once more. That refresh belongs to the departing account,
        // so it signs nobody in and never waits on the caller.
        val onItsWay = synchronized(signInLock) { refreshOnItsWay?.takeIf { it.first == token }?.second }
        // Bounded, as whoever waits on the unregister waits on this: past it the
        // refresh is left for a later try rather than the token sent alongside.
        if (onItsWay != null && withTimeoutOrNull(departingWaitMs) { onItsWay.await() } == null) {
            return DepartingRefresh.Unavailable
        }
        return withContext(Dispatchers.IO) {
            // To the backend that issued the session, whatever the endpoint setting says now.
            val base = departing.baseUrl
            val sends = maxOf(sendsWithoutReply, sendsWithoutReply(token))
            val outcome = takeUnstoredPair(token)
                ?: if (sends >= MAX_REFRESH_TOKEN_SENDS) {
                    RefreshOutcome.Refused
                } else if (!mayDepart()) {
                    RefreshOutcome.Unused
                } else {
                    // Counted by the caller, not on disk: the pair is no longer the stored one.
                    send(token, sends, DEPARTING, base)
                }
            when (outcome) {
                is RefreshOutcome.Refreshed -> DepartingRefresh.Refreshed(
                    DepartingSession(outcome.accessToken, outcome.refreshToken, outcome.expiresAt, base, departing.studentId),
                )
                RefreshOutcome.Refused -> DepartingRefresh.Refused
                RefreshOutcome.NoReply -> DepartingRefresh.NoReply
                // A 5xx: the same token goes out on a later try, this send counted.
                RefreshOutcome.Unavailable -> DepartingRefresh.Unavailable
                // Turned away or never sent: the same token goes out on a later try, uncounted. A
                // departing send is never counted on disk, so the disk cannot refuse it.
                RefreshOutcome.Unused, RefreshOutcome.Uncounted -> DepartingRefresh.Unused
            }
        }
    }

    // Under signInLock. The pair a refresh got back after a sign-out, which it
    // could not store, by the token it replaced; for refreshDeparting to take.
    private var unstoredPair: Pair<String, RefreshOutcome.Refreshed>? = null

    // Under signInLock. How many sends of a refresh token in a row got no reply.
    private var unanswered: Pair<String, Int>? = null

    // Under signInLock. The session refresh on its way, by the token it sends.
    private var refreshOnItsWay: Pair<String, CompletableDeferred<Unit>>? = null

    private fun takeUnstoredPair(token: String): RefreshOutcome.Refreshed? = synchronized(signInLock) {
        unstoredPair?.takeIf { it.first == token }?.second?.also { unstoredPair = null }
    }

    private fun sendsWithoutReply(token: String): Int = synchronized(signInLock) {
        unanswered?.takeIf { it.first == token }?.second ?: 0
    }

    /**
     * Signs in to the backend and stores the pair it returns. When [logout]
     * has moved [signInGeneration] past the value passed here, the reply
     * stores nothing and this throws [AuthException].
     *
     * The backend proves the account with [moodleToken] alone and ignores a
     * password (https://github.com/tigerduck-app/tigerduck-backend/pull/64),
     * so none is sent. Without a token nothing is sent either: such a login
     * could only be refused, and would count against the student's limit.
     */
    suspend fun login(
        studentId: String,
        moodleToken: String,
        moodlePrivateToken: String?,
        platform: String = "android",
        signInGeneration: Int = this.signInGeneration,
    ): LoginResult = loginQueue.withLock {
        // One at a time, the automatic and the interactive alike: the backend keeps only the
        // session of the login it took last, which is then the pair stored last. One that a
        // sign-out overtook while it waited sends nothing.
        if (signInGeneration != this.signInGeneration) throw AuthException("signed out before login")
        val replacing = currentCoroutineContext()[Replacing]
        // Read once: the debt the login may end and the pair it stores are this backend's.
        val issuer = baseUrl
        signingIn(studentId, issuer) {
            // Asked last, past the thread hop and the owed unregister's lock: a refresh that stored
            // a pair while those waited would otherwise see its session ended. One storing in the
            // moment after is answered by its requests' 401s, whose relogin then finds this
            // login's pair unrefused and retries with it (signInAgain).
            if (replacing != null && replacedSince(replacing.refreshToken)) throw SessionReplacedException()
            postLogin(issuer, studentId, moodleToken, moodlePrivateToken, platform, signInGeneration)
        }
    }

    private fun postLogin(
        issuer: String,
        studentId: String,
        moodleToken: String,
        moodlePrivateToken: String?,
        platform: String,
        signInGeneration: Int,
    ): LoginResult {
        if (moodleToken.isBlank()) throw AuthException("no Moodle token")
        val json = JSONObject().apply {
            put("student_id", studentId)
            put("moodle_token", moodleToken)
            put("moodle_private_token", moodlePrivateToken)
            put("device_info", JSONObject().apply {
                put("client_device_id", deviceUuid)
                put("platform", platform)
                put("app_version", BuildConfig.VERSION_NAME)
                put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
                put("device_model", currentDeviceModel())
            })
        }
        val body = json.toString().toRequestBody(jsonType)
        return signIns.at(issuer).login(body).execute().use { response ->
            if (!response.isSuccessful) throw AuthException("login failed: ${response.code}", response.code)
            val result = JSONObject(response.body.string())
            val accessToken = result.getString("access_token")
            val refreshToken = result.getString("refresh_token")
            val expiresIn = result.getInt("expires_in")
            val expiresAt = now() + expiresIn * 1000L
            if (!saveIfSignedIn(signInGeneration, accessToken, refreshToken, expiresAt, issuer, studentId)) {
                throw AuthException("signed out during login")
            }
            LoginResult(
                accessToken = accessToken,
                deviceId = result.getString("device_id"),
            )
        }
    }

    /**
     * A backend login, on the I/O dispatcher and under the lock of the
     * unregister a sign-out owes, which a login that lands ends: see
     * [OwedUnregister.signingIn].
     */
    private suspend fun <T> signingIn(studentId: String, base: String, login: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) {
            val owed = owedUnregister ?: return@withContext login()
            owed.signingIn(studentId, base) { login() }
        }

    /**
     * Drops the pair, and keeps a login or refresh reply still on its way from
     * storing one. The next account's automatic sign-in then goes at once.
     *
     * Hands back the session it drops, for the sign-out's unregister: taken
     * under the same lock, as the generation moves, so it is the pair a refresh
     * landing just before stored, and nothing lands after.
     */
    fun logout(): DepartingSession? {
        var departing: DepartingSession? = null
        synchronized(signInLock) {
            departing = departingSession()
            generation++
            tokens.clear()
            signInUnderWay = null
            signInFailedAt = null
            _lastSignInFailed.value = false
        }
        return departing
    }

    /**
     * Runs [block] unless [logout] has run since [signIn] was read, under the
     * lock the generation moves under, as [saveIfSignedIn] stores: what
     * [block] writes lands before whatever the sign-out clears after moving
     * the generation (`AuthService.logout`), or not at all. For a short write
     * only; every sign-in and refresh waits on the lock. Says whether it ran.
     */
    fun runIfSignedIn(signIn: Int, block: () -> Unit): Boolean = synchronized(signInLock) {
        if (generation != signIn) return false
        block()
        true
    }

    /**
     * Stores the pair unless [logout] has run since [signIn] was read, or, with [replacing], a
     * sign-in has stored a pair of its own since that refresh token went out; says whether it did.
     */
    private fun saveIfSignedIn(
        signIn: Int,
        access: String,
        refresh: String,
        expiresAt: Long,
        issuedBy: String? = null,
        studentId: String? = null,
        replacing: String? = null,
    ): Boolean {
        synchronized(signInLock) {
            if (generation != signIn) return false
            if (replacing != null && tokens.refreshToken != replacing) return false
            tokens.save(access, refresh, expiresAt, issuedBy, studentId)
            issued(access)
            return true
        }
    }

    // Not logout(): the account has not signed out, so a sign-in another
    // caller has in flight may still store its pair. Only the pair [sent] came
    // from: one a sign-in has stored since is that sign-in's.
    private fun clearIfSignedIn(signIn: Int, sent: String) {
        synchronized(signInLock) {
            if (generation == signIn && tokens.refreshToken == sent) tokens.clear()
        }
    }

    private suspend fun validAccessToken(): String? {
        // Read first: after a sign-out while this waits for a sign-in or a
        // refresh, the session stored is the next account's, not this request's.
        val signIn = signInGeneration
        if (!signInIfSignedOut()) return null
        return validTokenIfSignedIn(signIn) ?: refresh(signIn)
    }

    // The stored access token while it has more than 30 s left and is still
    // generation [signIn]'s.
    private fun validTokenIfSignedIn(signIn: Int): String? = synchronized(signInLock) {
        val access = tokens.accessToken
        if (generation != signIn || access == null || now() >= tokens.expiresAt - 30_000) null else issued(access)
    }

    private suspend fun refresh(signIn: Int): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        validTokenIfSignedIn(signIn)?.let { return it }

        // With the generation under the lock a sign-out takes, so the token is
        // the account's that asked; its count with it. Marked on its way in the
        // same step, so a departing refresh of the token waits for this one
        // rather than send it alongside.
        val marked = synchronized(signInLock) {
            val token = if (generation == signIn) tokens.refreshToken else null
            // With the backend the pair came from, which the refresh goes back to
            // whatever the endpoint setting names now.
            token?.let {
                Triple((it to CompletableDeferred<Unit>()).also { mark -> refreshOnItsWay = mark }, tokens.refreshTokenSends, storedIssuer())
            }
        } ?: return null
        val (onItsWay, sends, issuer) = marked
        try {
            // No header rather than the access token in its last seconds, which
            // would draw a 401, and Home answers a 401 with a relogin.
            if (isRefreshPaused()) return null
            // Finishes even when the caller is cancelled: stopped between a send
            // and what follows it, a token the backend may have rotated would stay
            // on disk for the next refresh.
            withContext(NonCancellable) { refreshAndSettle(onItsWay, sends, signIn, issuer) }
        } finally {
            arrived(onItsWay)
        }
    }

    /** The refresh [onItsWay] marked has its answer, or never went out. */
    private fun arrived(onItsWay: Pair<String, CompletableDeferred<Unit>>) {
        synchronized(signInLock) { if (refreshOnItsWay === onItsWay) refreshOnItsWay = null }
        onItsWay.second.complete(Unit)
    }

    /**
     * [sends]: the sends of the token [onItsWay] marks, counted before this refresh; see
     * [V3TokenStore.refreshTokenSends].
     */
    private suspend fun refreshAndSettle(
        onItsWay: Pair<String, CompletableDeferred<Unit>>,
        sends: Int,
        signIn: Int,
        issuer: String,
    ): String? {
        val outcome = try {
            if (sends >= MAX_REFRESH_TOKEN_SENDS) {
                // Another send could be its third use: sign in again instead.
                Log.w(TAG, "refresh token may have been used twice; signing in again")
                RefreshOutcome.Refused
            } else {
                withContext(Dispatchers.IO) { sendWithOneResend(onItsWay.first, sends, signIn, issuer) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            RefreshOutcome.Refused
        } finally {
            // Before any sign-in below: a departing refresh waiting on this
            // one wants its answer, not the relogin after it.
            arrived(onItsWay)
        }
        // Signed out since the refresh began: what came back is the departing
        // account's, and a relogin or a clear would act on whoever signed in since.
        if (signIn != signInGeneration) return null
        // A sign-in stored a pair of its own while this refresh was out: what came back is the
        // pair it replaced, whose session that login ended, and a relogin or a clear would end
        // the sign-in's. The caller goes with the stored pair.
        if (replacedSince(onItsWay.first, outcome)) return accessTokenIfSignedIn(signIn)
        when (outcome) {
            is RefreshOutcome.Refreshed -> {
                refreshPausedAt = null
                return outcome.accessToken
            }
            RefreshOutcome.Unavailable, RefreshOutcome.Unused -> {
                refreshPausedAt = now()
                return null
            }
            RefreshOutcome.Uncounted -> {
                // The token cannot go out uncounted, and a sign-in sends none: on a full disk the
                // refresh would otherwise fail every time and leave no session once the access
                // token ran out. Kept for a later refresh when the sign-in fails too.
                if (signInAutomatically(replacing = onItsWay.first)) return accessTokenIfSignedIn(signIn)
                refreshPausedAt = now()
                return null
            }
            RefreshOutcome.NoReply, RefreshOutcome.Refused -> Unit
        }
        if (signInAutomatically(replacing = onItsWay.first)) return accessTokenIfSignedIn(signIn)
        withContext(Dispatchers.IO) { clearIfSignedIn(signIn, onItsWay.first) }
        return null
    }

    // Whether the stored pair is neither the one [sent] came from nor the one its refresh stored.
    private fun replacedSince(sent: String, outcome: RefreshOutcome): Boolean = synchronized(signInLock) {
        val stored = tokens.refreshToken ?: return@synchronized false
        stored != sent && stored != (outcome as? RefreshOutcome.Refreshed)?.refreshToken
    }

    // Whether a pair other than the one [sent] came from is stored now.
    private fun replacedSince(sent: String): Boolean = synchronized(signInLock) {
        tokens.refreshToken.let { it != null && it != sent }
    }

    // After a sign-out during the relogin, the stored token belongs to whoever
    // signed in since and is not this refresh's to hand out.
    private fun accessTokenIfSignedIn(signIn: Int): String? =
        synchronized(signInLock) { if (generation == signIn) tokens.accessToken?.let(::issued) else null }

    // Measured from the failure rather than to a deadline, so a clock set back
    // does not stretch the pause.
    private fun isRefreshPaused(): Boolean {
        val pausedAt = refreshPausedAt ?: return false
        return now() - pausedAt in 0 until REFRESH_PAUSE_MS
    }

    /**
     * Sends [refreshToken], and once more at once if no reply came back, no
     * send before this refresh was counted ([sends]), and the account has not
     * signed out since.
     *
     * A send whose reply is lost may still have rotated the token. While the
     * pair it gave is unused the backend answers the old token once more with
     * a fresh pair, and takes any later send of it as reuse, so a resend that
     * fails too is a failed refresh, never a token kept for later. After a
     * counted send the resend could be that later send. A resend the backend
     * did not use ([RefreshOutcome.Unused]), or one the disk would not count
     * and so never sent ([RefreshOutcome.Uncounted]), leaves the token that
     * send, kept for a later refresh.
     */
    private fun sendWithOneResend(refreshToken: String, sends: Int, signIn: Int, base: String): RefreshOutcome {
        val first = send(refreshToken, sends, signIn, base)
        val mayResend = sends + 1 < MAX_REFRESH_TOKEN_SENDS && signIn == signInGeneration
        if (first != RefreshOutcome.NoReply || !mayResend) return first
        return when (val second = send(refreshToken, sends + 1, signIn, base)) {
            is RefreshOutcome.Refreshed, RefreshOutcome.Unused, RefreshOutcome.Uncounted -> second
            else -> RefreshOutcome.Refused
        }
    }

    /**
     * Sends [refreshToken], counted on disk before it goes out, so a process
     * killed while it is out leaves it counted; [sendsBefore] is the count
     * before it. A count the disk does not take keeps it from going out. A
     * 429 and a send that never left the device put the count back, as the
     * backend did not use the token. It goes to [base], the backend that
     * issued it, whatever the endpoint setting says.
     */
    private fun send(refreshToken: String, sendsBefore: Int, signIn: Int, base: String): RefreshOutcome {
        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
        }
        val body = json.toString().toRequestBody(jsonType).oneShot()
        val request = Request.Builder()
            .url("$base/auth/refresh")
            .post(body)
            .build()
        if (!countSends(refreshToken, sendsBefore + 1, signIn)) {
            // Counted in memory only, a restart would forget it; put back there too.
            countSends(refreshToken, sendsBefore, signIn)
            return RefreshOutcome.Uncounted
        }
        val progress = SendProgress()
        return try {
            refreshClient.newCall(request).apply { addEventListener(progress) }.execute().use { response ->
                // Answered: whatever the token's sends before got, this one has a reply.
                synchronized(signInLock) { if (unanswered?.first == refreshToken) unanswered = null }
                if (response.code == 429 || response.code >= 500) {
                    Log.w(TAG, "refresh got HTTP ${response.code}; keeping the tokens")
                    // The backend's limiter answers before the refresh runs. A
                    // 5xx may come from a gateway after the backend rotated.
                    if (response.code != 429) return@use RefreshOutcome.Unavailable
                    countSends(refreshToken, sendsBefore, signIn)
                    return@use RefreshOutcome.Unused
                }
                if (!response.isSuccessful) return@use RefreshOutcome.Refused
                val result = JSONObject(response.body.string())
                val newAccess = result.getString("access_token")
                val newRefresh = result.getString("refresh_token")
                val expiresAt = now() + result.getInt("expires_in") * 1000L
                val refreshed = RefreshOutcome.Refreshed(newAccess, newRefresh, expiresAt)
                // Stores nothing after a sign-out, which refreshAndSettle then sees.
                // The pair is kept aside instead, for the sign-out's unregister.
                if (!saveIfSignedIn(signIn, newAccess, newRefresh, expiresAt, issuedBy = base, replacing = refreshToken) &&
                    signIn != DEPARTING
                ) {
                    synchronized(signInLock) { unstoredPair = refreshToken to refreshed }
                }
                refreshed
            }
        } catch (e: IOException) {
            if (e.wasNeverSent() || progress.failedBeforeWriting) {
                Log.w(TAG, "refresh not sent: ${e.logSummary()}; keeping the tokens")
                countSends(refreshToken, sendsBefore, signIn)
                RefreshOutcome.Unused
            } else {
                Log.w(TAG, "refresh got no reply: ${e.javaClass.simpleName}")
                synchronized(signInLock) {
                    val count = unanswered?.takeIf { it.first == refreshToken }?.second ?: 0
                    unanswered = refreshToken to count + 1
                }
                RefreshOutcome.NoReply
            }
        }
    }

    // Only while [refreshToken] is still the account's stored token: a pair
    // stored since by a sign-in has a count of its own. False when the disk did
    // not take the count, and once the account has signed out: the sign-out
    // took the departing session under this lock with the count as it stood,
    // so a send counted after it would be one the debt never knows of, and
    // answered with a 5xx nothing here would count it either. A departing
    // refresh ([DEPARTING]) is counted by its caller, with the debt.
    private fun countSends(refreshToken: String, count: Int, signIn: Int): Boolean =
        synchronized(signInLock) {
            when {
                signIn == DEPARTING -> true
                generation != signIn -> false
                tokens.refreshToken != refreshToken -> true
                else -> tokens.saveRefreshTokenSends(count)
            }
        }

    // OkHttp 5 raises these before it writes the request: the DNS lookup's
    // UnknownHostException as is, and a refused or unreachable connect as a
    // ConnectException or NoRouteToHostException. A timeout or a reset may
    // come after the token went out, so either stays a NoReply unless the
    // call's SendProgress saw it fail before writing the request.
    private fun IOException.wasNeverSent(): Boolean =
        this is UnknownHostException || this is ConnectException || this is NoRouteToHostException

    /**
     * What a refresh call got to before it failed. One that began to connect
     * and failed before writing the request never sent the token: a connect
     * timeout, to a host that drops the packets, raises the same
     * SocketTimeoutException as a reply that never came.
     */
    private class SendProgress : EventListener() {
        @Volatile private var connecting = false
        @Volatile private var writing = false

        val failedBeforeWriting: Boolean get() = connecting && !writing

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connecting = true
        }

        override fun requestHeadersStart(call: Call) {
            writing = true
        }
    }

    // OkHttp sends a request again after some replies, whatever
    // retryOnConnectionFailure says: a 503 with Retry-After: 0, and a 307 or a
    // 308 with its body. It sends none whose body is one-shot.
    private fun RequestBody.oneShot(): RequestBody = object : RequestBody() {
        override fun contentType() = this@oneShot.contentType()
        override fun contentLength() = this@oneShot.contentLength()
        override fun writeTo(sink: BufferedSink) = this@oneShot.writeTo(sink)
        override fun isOneShot() = true
    }

    private sealed interface RefreshOutcome {
        // Not a data class, whose toString() would print the token.
        class Refreshed(val accessToken: String, val refreshToken: String, val expiresAt: Long) : RefreshOutcome

        /** No reply came back, so the backend may or may not have rotated the token. */
        data object NoReply : RefreshOutcome

        /**
         * A 5xx: the token is kept for a later refresh, and counted, as a
         * gateway's may come after the backend rotated.
         */
        data object Unavailable : RefreshOutcome

        /**
         * A 429, or a send that never left the device: the backend did not
         * use the token, which is kept for a later refresh without the send
         * counted.
         */
        data object Unused : RefreshOutcome

        /**
         * A send the disk would not count, which therefore never went out:
         * the token is kept, and the app signs in instead.
         */
        data object Uncounted : RefreshOutcome

        /** Anything else, a 401 among them: the app signs in again, as after every failed refresh before. */
        data object Refused : RefreshOutcome
    }

    /** What a sign-in did: [signInWithStoredAccount], or the one [signInInteractively] runs. */
    enum class SignInAttempt {
        /** It stored a pair for the generation it was given. */
        SIGNED_IN,

        /** It had credentials to send, to the backend or to harvest a Moodle token, and stored no pair. */
        FAILED,

        /** It had nothing to send: no account, the demo account, or neither a password nor a Moodle token. */
        NOTHING_TO_SEND,
    }

    data class LoginResult(val accessToken: String, val deviceId: String)
    /** [status] is the backend's HTTP status, when it answered. */
    open class AuthException(message: String, val status: Int? = null) : Exception(message)

    private companion object {
        const val TAG = "AuthTokenManager"
        const val REFRESH_TIMEOUT_SECONDS = 20L

        // No generation is ever this, so a departing refresh stores nothing.
        const val DEPARTING = -1

        // The backend answers a refresh token twice at most: once, and again
        // while the pair it gave is unused. It takes a third use for theft and
        // revokes every session of the device.
        const val MAX_REFRESH_TOKEN_SENDS = 2

        // The backend limits refreshes per IP over a minute.
        const val REFRESH_PAUSE_MS = 60_000L

        // Access tokens kept with their backend for mayPresent: the pair now, the ones a refresh
        // or a sign-out replaced while requests built with them may still be on their way.
        const val KNOWN_ISSUERS = 8
    }
}

/**
 * How long an automatic sign-in with the stored password waits after one that failed: the
 * backend's here, and the Moodle token renewal's SSO sign-in (MoodleTokenRenewal).
 */
internal const val SIGN_IN_PAUSE_MS = 5 * 60_000L

/**
 * The deadline for each whole call of an automatic sign-in: the backend's sign-in here, and each
 * step of the Moodle token harvest, its SSO sign-in among them (MoodleTokenService); the sign-in
 * by hand makes the same calls. The clients time each read alone, so a server that trickles its
 * answer could hold the sign-in, which runs under NonCancellable, and whoever waits on it, without
 * end. A step takes a few seconds, redirects included; 30 s is above both clients' read timeouts
 * (15 s and 20 s), so a step with one slow read they let through still ends in time.
 */
internal const val SIGN_IN_CALL_TIMEOUT_SECONDS = 30L
