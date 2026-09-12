package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.shared.LibraryService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import org.ntust.app.tigerduck.auth.AuthTokenManager

@Singleton
class AuthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val libraryService: LibraryService,
    private val credentials: CredentialManager,
    private val pushRegistration: PushRegistrationService,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val authTokenManager: AuthTokenManager,
    private val moodleTokenService: MoodleTokenService,
    private val dataCache: DataCache,
    private val bulletinCache: BulletinCache,
    private val bulletinReadStateStore: BulletinReadStateStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
    debugFixtures: org.ntust.app.tigerduck.debug.DebugFixtureStore,
) {
    /**
     * A screenshot session presents as signed in without an account.
     *
     * `authState` is what every screen asks, so a device that skipped the
     * wizard replaced its whole content with "not signed in" and no fixture
     * could put anything on it -- the fake timetable was on disk, and nothing
     * would draw it. Reporting true here is what lets a clean install be
     * photographed at all.
     *
     * Safe only because demo mode also refuses every request
     * ([org.ntust.app.tigerduck.debug.DemoModeInterceptor]): nothing tries to
     * use the credentials that are not there, and the screens take the
     * no-network path they already had. Nothing lowers the flag behind our
     * back either -- `logout()` is reached from the settings entry and the
     * full reset, both of which are the user saying so.
     *
     * Sampled once, at process start, like the rest of demo mode.
     */
    private val demoMode =
        org.ntust.app.tigerduck.BuildConfig.DEBUG && debugFixtures.demoMode

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    /**
     * Observable "is a user signed in" state, backed by the stored NTUST
     * credentials — so it is **durable**: it survives process death, reboots
     * and upgrades, and only flips on an actual login or logout.
     *
     * This is the signal almost everything wants, including every feature
     * that only reads the local cache. Session *liveness* — whether an SSO
     * cookie is currently warm enough to make a network call — is a separate,
     * much shorter-lived question; ask [NtustSessionManager.cookiesValid] for
     * it, or better, call [ensureAuthenticated], which re-logs in from stored
     * credentials rather than merely reporting that it cannot.
     *
     * Conflating the two is a live hazard: the cookie jar is in-memory, so a
     * liveness check reads false after every process restart. Gating the Live
     * Update on one made it vanish after each reboot or background kill until
     * the user signed in by hand.
     */
    private val _authState = MutableStateFlow(demoMode || credentials.ntustStudentId != null)
    val authState: StateFlow<Boolean> = _authState

    private val loginMutex = Mutex()

    init {
        authTokenManager.onRefreshFailed = {
            attemptRelogin()
        }
    }

    suspend fun attemptRelogin(): Boolean {
        val studentId = credentials.ntustStudentId ?: return false
        val password = credentials.ntustPassword
        // Harvest a FRESH Moodle token before the v3 login — the backend
        // verifies it against moodle2, and the stored one may be stale, which
        // gets the login rejected with 401 invalid_token (see login()).
        // Best-effort: fall back to the stored token if the harvest fails.
        val moodleToken = if (password != null) {
            try {
                moodleTokenService.obtainToken(studentId, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AuthService", "auto-relogin: Moodle token harvest failed; using stored", e)
                credentials.moodleToken
            }
        } else {
            credentials.moodleToken
        }
        if (moodleToken.isNullOrEmpty()) return false
        return try {
            authTokenManager.login(
                studentId = studentId,
                password = password ?: "",
                moodleToken = moodleToken,
                moodlePrivateToken = null,
            )
            android.util.Log.i("AuthService", "auto-relogin: v3 JWT refreshed")
            runCatching { pushRegistration.onSignedIn() }
            // Deliver a live_activity edit that is still unconfirmed: one made
            // while signed out, or while the v3 JWT had lapsed. With nothing
            // pending this sends nothing, so this device's values never
            // overwrite what another device wrote. It must not mark anything
            // either, because this relogin can run from inside a pull's own
            // request — see NotificationSettingsSync.pushIfUnconfirmed.
            notificationSettingsSync.pushIfUnconfirmed()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("AuthService", "auto-relogin failed", e)
            false
        }
    }

    val storedStudentId: String? get() = credentials.ntustStudentId
    internal val storedPassword: String? get() = credentials.ntustPassword
    val storedMoodleToken: String? get() = credentials.moodleToken

    /**
     * Upgrade migration: users upgrading from v2 have stored NTUST credentials
     * but no v3 JWT. Detect this and silently perform the v3 login so push
     * registration works without requiring a manual log-out/log-in cycle.
     * Safe to call on every launch — no-ops if already signed in or no creds.
     */
    suspend fun migrateToV3IfNeeded() {
        if (authTokenManager.isLoggedIn) return
        val studentId = credentials.ntustStudentId ?: return
        val password = credentials.ntustPassword ?: return
        android.util.Log.i("AuthService", "v3 migration: has creds but no JWT, attempting silent login")
        val moodleToken = try {
            moodleTokenService.obtainToken(studentId, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AuthService", "v3 migration: Moodle token harvest failed", e)
            credentials.moodleToken ?: return
        }
        runCatching {
            authTokenManager.login(
                studentId = studentId,
                password = password,
                moodleToken = moodleToken,
                moodlePrivateToken = null,
            )
        }.onSuccess {
            android.util.Log.i("AuthService", "v3 migration: JWT obtained")
            runCatching { pushRegistration.onSignedIn() }
                .onFailure { e -> if (e is CancellationException) throw e }
            // Same catch-up as attemptRelogin(), for the v2->v3 migration.
            notificationSettingsSync.pushIfUnconfirmed()
        }.onFailure { e ->
            if (e is CancellationException) throw e
            android.util.Log.w("AuthService", "v3 migration: login failed", e)
        }
    }

    suspend fun login(studentId: String, password: String): Boolean = loginMutex.withLock {
        _isLoggingIn.value = true
        _loginError.value = null

        try {
            val normalizedId = studentId.trim().uppercase()
            val success = performSsoLoginUnlocked(normalizedId, password)

            if (success) {
                credentials.ntustStudentId = normalizedId
                credentials.ntustPassword = password
                _authState.value = true
                // Best-effort: v3 JWT login failures must not block the SSO
                // session — the push system falls back to the shared secret if
                // no Bearer token is available.
                // Harvest a FRESH Moodle token before the v3 login. The backend
                // verifies it against moodle2, and the stored token is stale or
                // empty right after SSO (especially on a fresh install) — which
                // makes the backend reject the login with 401 invalid_token.
                // iOS does the same obtain-then-login. Best-effort: fall back to
                // any stored token, and never let a harvest failure block the
                // SSO session.
                val moodleToken = try {
                    moodleTokenService.obtainToken(normalizedId, password)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("AuthService", "Moodle token harvest failed; using stored", e)
                    credentials.moodleToken
                }
                runCatching {
                    authTokenManager.login(
                        studentId = normalizedId,
                        password = password,
                        moodleToken = moodleToken,
                        moodlePrivateToken = null,
                            )
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.w("AuthService", "v3 login failed (best-effort)", e)
                }
                runCatching { pushRegistration.onSignedIn() }
                    .onFailure { e -> if (e is CancellationException) throw e }
                // Same catch-up as attemptRelogin(), for a fresh SSO login.
                notificationSettingsSync.pushIfUnconfirmed()
            }

            _isLoggingIn.value = false
            success
        } catch (e: CancellationException) {
            _isLoggingIn.value = false
            throw e
        } catch (e: Exception) {
            _loginError.value = if (e is SsoLoginError.NetworkError) {
                context.getString(R.string.error_network_unavailable)
            } else {
                e.message ?: context.getString(R.string.error_sign_in_failed)
            }
            _isLoggingIn.value = false
            false
        }
    }

    suspend fun ensureAuthenticated(): Boolean = loginMutex.withLock {
        val studentId = credentials.ntustStudentId ?: return@withLock false
        val password = credentials.ntustPassword ?: return@withLock false

        if (sessionManager.cookiesValid) return@withLock true

        try {
            performSsoLoginUnlocked(studentId.trim().uppercase(), password)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Shared SSO + library login work. Caller MUST already hold [loginMutex] —
     * Kotlin `Mutex` is non-reentrant, so the public entry points each acquire
     * the lock once and delegate here, avoiding the deadlock that would happen
     * if one path called the other.
     */
    private suspend fun performSsoLoginUnlocked(
        normalizedId: String,
        password: String,
    ): Boolean {
        val serviceUrl = "https://courseselection.ntust.edu.tw/"
        val success = ssoLoginService.ensureServiceLogin(serviceUrl, normalizedId, password)
        if (success && !credentials.isLibraryTokenValid) {
            // Best-effort: library credentials may differ from NTUST SSO, so a
            // failure here must not fail the SSO login — but log it, otherwise
            // "library QR never works" is undiagnosable in the field.
            try {
                libraryService.login(normalizedId, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AuthService", "Library login failed (best-effort)", e)
            }
        }
        return success
    }

    fun logout() {
        // Snapshot the bearer BEFORE wiping tokens: clearNtustCredentials() and
        // authTokenManager.logout() both null the v3 access token, and
        // pushRegistration.unregister() runs its DELETE fire-and-forget on its
        // own scope — so without a captured header the device-unregister call
        // would go out unauthenticated, 401, and leak the device row.
        val authHeader = authTokenManager.currentAuthHeader()
        credentials.clearNtustCredentials()
        credentials.clearLibraryCredentials()
        authTokenManager.logout()
        // A queued live_activity push, and an edit still waiting to be
        // confirmed, both belong to the account that is leaving. Neither may
        // reach whoever signs in next.
        notificationSettingsSync.cancelPendingPushes()
        sessionManager.invalidateSession()
        bulletinReadStateStore.clear()
        _loginError.value = null
        _authState.value = false
        pushRegistration.unregister(authHeader)
        // Wipe persisted user data on the application scope so a coroutine
        // launched from a transient ViewModel scope can't be cancelled mid-
        // delete when the user backs out of Settings or the activity dies.
        // Without this guarantee, the JSON cache survives logout and bleeds
        // into the next account on the same device.
        appScope.launch {
            runCatching { dataCache.clearAllUserData() }
                .onFailure { android.util.Log.w("AuthService", "clearAllUserData failed on logout", it) }
            // Bulletins are school-wide rather than private, but the read-state
            // store is already cleared above — clear the content snapshot too
            // so the next session starts coherent instead of half-stale.
            runCatching { bulletinCache.clear() }
                .onFailure { android.util.Log.w("AuthService", "bulletinCache.clear failed on logout", it) }
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }
}
