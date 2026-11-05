package org.ntust.app.tigerduck.auth

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.backend.ApiBase
import org.ntust.app.tigerduck.network.canonicalBackend
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.currentDeviceModel
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

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
    // The unregister a departure owes, which a login at its backend ends; see [login].
    private val owedUnregister: OwedUnregister? = null,
    // How long a departing refresh waits for a session refresh of its token on its way.
    private val departingWaitMs: Long = REFRESH_TIMEOUT_SECONDS * 1000,
    signInCallTimeoutMs: Long = SIGN_IN_CALL_TIMEOUT_SECONDS * 1_000,
    /**
     * The school session whose sign-in [logout] ends. The default is a session of its own, for
     * tests that are not about it, as other stores here take an absent collaborator.
     */
    val session: AccountSession = AccountSession(SignInSeries(), CoroutineScope(SupervisorJob())),
    enqueueDepartingUnregister: () -> Unit = {},
) {
    private val refreshMutex = Mutex()
    private val backendMove = BackendSessionMove(owedUnregister, deviceUuid, now, enqueueDepartingUnregister)

    // Backend logins, one at a time; see [login].
    private val loginQueue = Mutex()
    private val jsonType = "application/json".toMediaType()

    /**
     * Signs in with the stored account, passing the sign-in it is given to
     * [login], and says what it sent: AuthService's. Run only by the automatic
     * sign-in: after a refused refresh, for [signInAgain], and when something
     * finds no session.
     */
    var signInWithStoredAccount: (suspend (SignIn) -> SignInAttempt)? = null

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

    // Held from asking a sign-in through the token write that depends on it, so
    // a sign-out cannot fall between a reply's check and its save. Taken before
    // the sign-in's own lock (SignIn.ifCurrent), as logout() takes them.
    private val signInLock = Any()

    // Under signInLock: the sign-in that automatic callers which overlap it
    // wait for, and when one that sent credentials last failed.
    private var signInUnderWay: CompletableDeferred<Boolean>? = null
    private var signInFailedAt: Long? = null

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
     * Whether this phone holds a session from [base], at any of its API versions. Signing in moves
     * the device to the account at that backend alone, so an unregister a sign-out owes another
     * backend still stands.
     */
    fun hasSessionAt(base: String): Boolean = synchronized(signInLock) {
        tokens.refreshToken != null && ApiBase.sameBackend(storedIssuer(), base)
    }

    // The access tokens this process stored or handed out, with the backend each came from, for
    // [mayPresent]. Kept past a sign-out, so a request built before it can still reach that
    // backend and no other; a few, as only the last pairs can still be on their way.
    // Under signInLock.
    private val issuers = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KNOWN_ISSUERS
    }

    // Under signInLock. A pair stored before its backend was recorded is the endpoint's, as
    // departingSession takes it. Read at the API the app speaks: a pair `/v3` issued is `/v4`'s too.
    private fun storedIssuer(): String = tokens.issuedBy?.let { ApiBase.of(canonicalBackend(it)) } ?: baseUrl

    // Under signInLock.
    private fun issued(access: String): String = access.also { issuers[it] = storedIssuer() }

    /**
     * Whether [accessToken] may go to [url]: only to the backend that issued it, at either of its
     * API versions ([ApiBase.serves]), and an access token this phone never held nowhere. The
     * backend clients' last check before a request leaves ([SessionHostGuard]), so a request that
     * read its address and its header at different times, around a change of the API endpoint
     * setting, still goes nowhere else.
     */
    fun mayPresent(accessToken: String, url: HttpUrl): Boolean {
        val issuer = synchronized(signInLock) {
            issuers[accessToken] ?: storedIssuer().takeIf { tokens.accessToken == accessToken }
        } ?: return false
        return ApiBase.serves(issuer, url)
    }

    /**
     * The header of a request of [signIn]'s, signing in first with no session at this backend. Without
     * one the request is not sent, as the backend could only refuse it with 401; and once [signIn]
     * has ended none either, rather than the next account's token. The sign-in it may start is
     * [signIn]'s own, and it gets a token only while [signIn] stands.
     */
    suspend fun requireAuthHeader(signIn: SignIn): String {
        if (!signIn.isCurrent) throw NotSignedInException()
        if (!signInAtCurrentBackend(under = signIn)) throw NotSignedInException()
        val access = validTokenIfSignedIn(signIn) ?: refresh(signIn) ?: throw NotSignedInException()
        if (!hasSessionAt(baseUrl)) throw NotSignedInException()
        return "Bearer $access"
    }

    /**
     * Signs in with no session at the current backend, and says whether there is one now.
     * Runs on each return to the app, and before a registration or a sync.
     */
    suspend fun signInIfSignedOut(): Boolean = signInAtCurrentBackend()

    private suspend fun signInAtCurrentBackend(under: SignIn? = null, waitOutPause: Boolean = true): Boolean {
        if (under != null && !under.isCurrent) return false
        if (!hasSessionAt(baseUrl)) signInAutomatically(under, waitOutPause)
        return hasSessionAt(baseUrl)
    }

    private suspend fun prepareBackend(under: SignIn?): Boolean {
        if (under != null && !under.isCurrent) return false
        if (hasSessionAt(baseUrl)) return true
        // A login already out may still store its old backend's pair. Take it after that reply,
        // before another login can go out; a refresh still out stores only over the pair it sent.
        return loginQueue.withLock {
            backendMove.keepingDeparture { keep ->
                synchronized(signInLock) {
                    if (under != null && !under.isCurrent) return@synchronized false
                    if (!hasSessionAt(baseUrl)) {
                        departingSession()?.let { if (!keep(it)) return@synchronized false }
                        tokens.clear()
                    }
                    true
                }
            }
        }
    }

    /**
     * Signs in again after the backend refused a session that looked valid, and says whether there
     * is a session to retry with. Only when the access token stored is one the backend refused
     * ([noteRefused]), or none is: a login ends the device's other sessions of the account
     * (tigerduck-backend#213), so a request refused with a pair a relogin or a refresh has since
     * replaced would otherwise end that relogin's session, and with steady traffic every refused
     * request would end the last one's. Then the caller retries with the stored pair instead.
     *
     * For a refusal of a request of [signIn]'s. Once that has ended, the pair stored is another
     * sign-in's, which the refusal says nothing of, and there is nothing to retry: false.
     */
    suspend fun signInAgain(signIn: SignIn): Boolean {
        if (!hasSessionAt(baseUrl)) return signInAtCurrentBackend(under = signIn)
        val replacing = synchronized(signInLock) {
            if (!signIn.isCurrent) return false
            val refresh = tokens.refreshToken
            // Stored and not refused: the caller retries with it.
            if (refresh != null && tokens.accessToken.let { it != null && it != refusedAccess }) return true
            refresh
        }
        return signInAutomatically(under = signIn, replacing = replacing)
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

    /** The login a pair stored since its sign-in began made moot; see [Replacing]. */
    class SessionReplacedException : AuthException("a pair was stored since; no login sent")

    /**
     * As [signInIfSignedOut], without waiting out the pause after a failed sign-in: for the try of
     * a stored password the school refused ([StoredPasswordRetry]), which its own schedule paces
     * and which may have just got the Moodle token the failed one lacked.
     */
    suspend fun signInIfSignedOutNow(): Boolean = signInAtCurrentBackend(waitOutPause = false)

    /**
     * Runs the interactive sign-in's [signIn], which calls [login] with the
     * sign-in it is given, and says whether it signed in. It never waits
     * for the pause. An automatic sign-in that starts meanwhile waits for it
     * instead of logging in too, and a failure starts the pause as an
     * automatic one does.
     */
    @OptIn(StartsAccountWork::class)
    suspend fun signInInteractively(signIn: suspend (SignIn) -> SignInAttempt): Boolean {
        val attempt = CompletableDeferred<Boolean>()
        val current = synchronized(signInLock) {
            signInUnderWay = attempt
            _lastSignInFailed.value = false
            session.current
        }
        return runSignIn(attempt, current, signInWith = signIn)
    }

    /**
     * Runs [signInWithStoredAccount], or waits for the sign-in under way. For
     * [SIGN_IN_PAUSE_MS] after one with credentials to send failed, it makes
     * none and returns false: the backend allows 5 logins per 15 minutes per
     * student and per IP, and a campus network puts many students behind one
     * IP. [under], for work of that sign-in's, gets none once another is in its
     * place: the next student's login is not that work's to start or to fail.
     */
    @OptIn(StartsAccountWork::class)
    private suspend fun signInAutomatically(
        under: SignIn? = null,
        waitOutPause: Boolean = true,
        replacing: String? = null,
    ): Boolean {
        if (!prepareBackend(under)) return false
        val signInWith = signInWithStoredAccount ?: return false
        val attempt = CompletableDeferred<Boolean>()
        val signIn: SignIn
        // The sign-in already under way, which this caller waits for instead.
        val running = synchronized(signInLock) {
            // Before the credentials, which the sign-in reads.
            signIn = session.current
            if (under != null && under !== signIn) return false
            if (replacing == null && hasSessionAt(baseUrl)) return true
            signInUnderWay ?: run {
                if (waitOutPause && isSignInPaused()) return false
                signInUnderWay = attempt
                _lastSignInFailed.value = false
                null
            }
        }
        if (running != null) {
            val signedIn = running.await()
            return if (signedIn && !hasSessionAt(baseUrl)) signInAutomatically(under, waitOutPause, replacing) else signedIn
        }
        // Finishes for the callers waiting on it when this one is cancelled.
        return withContext(NonCancellable + (replacing?.let(::Replacing) ?: EmptyCoroutineContext)) {
            runSignIn(attempt, signIn, replacing) { current ->
                // A failure of the sign-in itself is the caller's no session,
                // never an exception out of its token lookup.
                try {
                    signInWith(current)
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
        signIn: SignIn,
        replacing: String? = null,
        signInWith: suspend (SignIn) -> SignInAttempt,
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

    private fun settleSignIn(signIn: SignIn, attempt: SignInAttempt): Boolean = synchronized(signInLock) {
        // Signed out since: the attempt was the departing account's, and the
        // sign-out ended any pause.
        if (!signIn.isCurrent) return@synchronized false
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
        tokens.departingSession(storedIssuer(), tokens.refreshToken?.let(::sendsWithoutReply) ?: 0)
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
            // To the backend that issued the session, at the API the app speaks, whatever the setting says.
            val base = ApiBase.of(departing.baseUrl)
            val sends = maxOf(sendsWithoutReply, sendsWithoutReply(token))
            val outcome = takeUnstoredPair(token)
                ?: if (sends >= MAX_REFRESH_TOKEN_SENDS) {
                    RefreshOutcome.Refused
                } else if (!mayDepart()) {
                    RefreshOutcome.Unused
                } else {
                    // Counted by the caller, not on disk: the pair is no longer the stored one.
                    send(token, sends, signIn = null, base)
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
     * Signs in to the backend and stores the pair it returns. Once [signIn] has
     * ended the reply stores nothing and this throws [AuthException].
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
        signIn: SignIn,
        platform: String = "android",
    ): LoginResult = loginQueue.withLock {
        // One at a time, automatic and interactive alike: the backend keeps only the last login's
        // session, which is then the pair stored last. One a sign-out overtook sends nothing.
        if (!signIn.isCurrent) throw AuthException("signed out before login")
        val replacing = currentCoroutineContext()[Replacing]
        // Read once: the debt it may end, the pair it stores and what it sends are this backend's.
        val endpoint = resolveAnnouncementEndpoint(prefs)
        val issuer = endpoint.url.trimEnd('/')
        val privateToken = moodlePrivateToken.takeUnless { endpoint.overrideApplied }
        signingIn(studentId, issuer) {
            // Asked last, past the thread hop and the owed unregister's lock: a refresh that stored
            // a pair meanwhile would see its session ended. One storing just after is answered by
            // its 401s, whose relogin finds this login's pair unrefused and retries (signInAgain).
            if (replacing != null && replacedSince(replacing.refreshToken)) throw SessionReplacedException()
            postLogin(issuer, studentId, moodleToken, privateToken, platform, signIn)
        }
    }

    private fun postLogin(
        issuer: String,
        studentId: String,
        moodleToken: String,
        moodlePrivateToken: String?,
        platform: String,
        signIn: SignIn,
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
        val body = json.toString().toRequestBody(jsonType).oneShot()
        return signIns.at(issuer).login(body).execute().use { response ->
            if (!response.isSuccessful) throw AuthException("login failed: ${response.code}", response.code)
            val result = JSONObject(response.body.string())
            val accessToken = result.getString("access_token")
            val refreshToken = result.getString("refresh_token")
            val expiresIn = result.getInt("expires_in")
            val expiresAt = now() + expiresIn * 1000L
            if (!saveIfSignedIn(signIn, accessToken, refreshToken, expiresAt, issuer, studentId)) {
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
     * Drops the pair and ends the sign-in, which keeps a login or refresh reply
     * still on its way from storing one. The next account's automatic sign-in
     * goes once the session opens.
     *
     * Hands back the session it drops, for the sign-out's unregister: taken
     * under the same lock, as the sign-in ends, so it is the pair a refresh
     * landing just before stored, and nothing lands after. AuthService has
     * ended the sign-in already by then, and a pair refused in that gap goes
     * aside for the unregister, as one that comes back after this does.
     */
    fun logout(): DepartingSession? {
        var departing: DepartingSession? = null
        synchronized(signInLock) {
            departing = departingSession()
            session.signIns.end()
            tokens.clear()
            signInUnderWay = null
            signInFailedAt = null
            _lastSignInFailed.value = false
        }
        // After the lock is released: a cancellation handler never takes a lock held here.
        session.cancelWork()
        return departing
    }

    /**
     * Stores the pair unless [signIn] has ended, wherever the sign-out that ended it stands, or,
     * with [replacing], a sign-in has stored a pair of its own since that refresh token went out;
     * says whether it did. The store and the sign-in's end exclude each other ([SignIn.ifCurrent]).
     */
    private fun saveIfSignedIn(
        signIn: SignIn,
        access: String,
        refresh: String,
        expiresAt: Long,
        issuedBy: String? = null,
        studentId: String? = null,
        replacing: String? = null,
    ): Boolean = synchronized(signInLock) {
        signIn.ifCurrent {
            if (replacing != null && tokens.refreshToken != replacing) return@ifCurrent false
            tokens.save(access, refresh, expiresAt, issuedBy, studentId)
            issued(access)
            true
        } == true
    }

    // Not logout(): the account has not signed out, so a sign-in another
    // caller has in flight may still store its pair. Only the pair [sent] came
    // from: one a sign-in has stored since is that sign-in's.
    private fun clearIfSignedIn(signIn: SignIn, sent: String) {
        synchronized(signInLock) {
            if (signIn.isCurrent && tokens.refreshToken == sent) tokens.clear()
        }
    }

    // The stored access token while it has more than 30 s left and is still
    // [signIn]'s.
    private fun validTokenIfSignedIn(signIn: SignIn): String? = synchronized(signInLock) {
        val access = tokens.accessToken
        if (!signIn.isCurrent || access == null || now() >= tokens.expiresAt - 30_000) null else issued(access)
    }

    private suspend fun refresh(signIn: SignIn): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        validTokenIfSignedIn(signIn)?.let { return it }

        // Asked under the lock a sign-out clears the pair under, so the token is
        // the account's that asked; its count with it. Marked on its way in the
        // same step, so a departing refresh of the token waits for this one
        // rather than send it alongside.
        val marked = synchronized(signInLock) {
            val token = if (signIn.isCurrent) tokens.refreshToken else null
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
        signIn: SignIn,
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
        if (!signIn.isCurrent) return null
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
                if (signInAutomatically(under = signIn, replacing = onItsWay.first)) return accessTokenIfSignedIn(signIn)
                refreshPausedAt = now()
                return null
            }
            RefreshOutcome.NoReply, RefreshOutcome.Refused -> Unit
        }
        if (signInAutomatically(under = signIn, replacing = onItsWay.first)) return accessTokenIfSignedIn(signIn)
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
    private fun accessTokenIfSignedIn(signIn: SignIn): String? =
        synchronized(signInLock) { if (signIn.isCurrent) tokens.accessToken?.let(::issued) else null }

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
    private fun sendWithOneResend(refreshToken: String, sends: Int, signIn: SignIn, base: String): RefreshOutcome {
        val first = send(refreshToken, sends, signIn, base)
        val mayResend = sends + 1 < MAX_REFRESH_TOKEN_SENDS && signIn.isCurrent
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
     * issued it, whatever the endpoint setting says. With no [signIn], a
     * departing refresh: it stores nothing, and its caller counts its sends.
     */
    private fun send(refreshToken: String, sendsBefore: Int, signIn: SignIn?, base: String): RefreshOutcome {
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
                if (signIn != null &&
                    !saveIfSignedIn(signIn, newAccess, newRefresh, expiresAt, issuedBy = base, replacing = refreshToken)
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
    // stored since by a sign-in has a count of its own. A departure took the
    // pair with its count under this lock, so a later send would be one the
    // debt never knows of. With a 5xx nothing here could count it either.
    // False once the pair departed, after sign-out, or if the disk refused the
    // count. A departing refresh, with no [signIn], is counted by its caller,
    // with the debt.
    private fun countSends(refreshToken: String, count: Int, signIn: SignIn?): Boolean =
        synchronized(signInLock) {
            when {
                signIn == null -> true
                !signIn.isCurrent -> false
                tokens.refreshToken != refreshToken -> false
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

    /** What a sign-in did: [signInWithStoredAccount], or the one [signInInteractively] runs. */
    enum class SignInAttempt {
        /** It stored a pair for the sign-in it was given. */
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
