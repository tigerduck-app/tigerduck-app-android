package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.SignOutPlans
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.util.logSummary

@Singleton
class AuthService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: NtustSessionManager,
    private val ssoLoginService: SsoLoginService,
    private val credentials: CredentialManager,
    private val pushRegistration: PushRegistrationService,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val authTokenManager: AuthTokenManager,
    private val moodleTokenService: MoodleTokenService,
    private val storedPasswordLatch: StoredPasswordLatch,
    private val importedClassTables: ImportedClassTableStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val demoAccount: org.ntust.app.tigerduck.demo.DemoAccount,
    private val sessions: AccountSessions,
    /** The sign-out a typed sign-in as another student runs first; lazy, as the plans depend on this. */
    private val signOutPlans: dagger.Lazy<SignOutPlans>,
) {
    /**
     * A demo session presents as signed in, with or without an account: `authState` is what every
     * screen asks, and a clean install could not otherwise be photographed. Safe only because demo
     * mode also refuses every request ([org.ntust.app.tigerduck.debug.DemoModeInterceptor]), and
     * nothing lowers the flag but the sign-out ([SignOutPlans]), which the user asks for. Sampled
     * once, at process start, for what is decided once per process: how [authState] starts. The
     * demo sign-in and the sign-out move that themselves.
     */
    private val demoMode = sessions.school.isDemo

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
    private val signedInState = SignedInState(demoMode || credentials.ntustStudentId != null)
    val authState: StateFlow<Boolean> = signedInState.signedIn.also(sessions.school::signedInFrom)

    /**
     * Shows the account as signed in or out and says so, a sign-out with its [reason], unless
     * [signIn], a sign-in's own, has ended.
     */
    private fun setSignedIn(signedIn: Boolean, signIn: SignIn? = null, reason: SignOutReason = SignOutReason.SCHOOL) =
        signedInState.set(signedIn, stands = { signIn == null || signIn.isCurrent }) { changed ->
            if (changed) sessions.schoolChanged(signedIn, reason)
        }

    /**
     * The sign-out's Show step ([SignOutPlans]): the last sign-in's error goes, and the account
     * shows as signed out, told with [reason]. Never under the account lock.
     */
    internal fun showSignedOut(reason: SignOutReason) {
        _loginError.value = null
        setSignedIn(false, reason = reason)
    }

    private val loginMutex = Mutex()

    /**
     * Held by a sign-out as it ends the sign-in and while it drops the stored account
     * ([SignOutPlans]), and by a sign-in while it asks whether its own has ended and stores what it
     * got. Taken before the sign-out's own lock and the sign-in series'.
     */
    internal val accountLock = Any()

    // The automatic backend sign-in, after a refused refresh and whenever
    // something finds no session.
    private val storedAccountSignIn = StoredAccountSignIn(
        tokens = authTokenManager,
        studentId = { credentials.ntustStudentId },
        password = { credentials.ntustPassword },
        storedMoodleToken = { credentials.moodleToken },
        // A token in place of the one the backend refused. Stores none once the
        // account has signed out, and sends no password the school refused.
        harvestMoodleToken = { refused -> moodleTokenService.renewToken(refused) },
        afterSignIn = {
            appScope.launch {
                runCatching { pushRegistration.onSignedIn() }
                    .onFailure { e -> android.util.Log.w("AuthService", "registration after auto sign-in failed: ${e.logSummary()}") }
            }
            // Deliver an edit that is still unconfirmed, such as one made while
            // the v3 JWT had lapsed; one made while signed out gives way to the
            // account's settings. With nothing pending this sends nothing, so
            // this device's values never overwrite what another device wrote.
            // It must not mark anything either, because this sign-in can run
            // from inside a pull's own request — see
            // NotificationSettingsSync.pushIfUnconfirmed.
            notificationSettingsSync.pushIfUnconfirmed()
        },
    )

    // The stored account's sign-in again, run under loginMutex by ensureAuthenticated(). The
    // typed sign-in is typedSignIn's.
    private val schoolSignIn = SchoolSignIn(
        studentId = { credentials.ntustStudentId },
        password = { credentials.ntustPassword },
        sessionIsWarm = { sessionManager.cookiesValid },
        signInToSchool = { studentId, password ->
            ssoLoginService.ensureServiceLogin(SSO_SERVICE_URL, studentId, password)
        },
        signInAgainToSchool = { studentId, password, accepted, stillRefused ->
            ssoLoginService.signInAgain(SSO_SERVICE_URL, studentId, password, accepted, stillRefused)
        },
    )

    init {
        authTokenManager.signInWithStoredAccount = storedAccountSignIn::signIn
    }

    // The sign-in with credentials the user typed. The school checks them from an empty session
    // rather than trusting whatever session the jar holds: it may still be another account's --
    // see [SsoLoginService.signIn].
    @OptIn(StartsAccountWork::class)
    private val typedSignIn = TypedAccountSignIn(
        schoolSignIn = { studentId, password ->
            ssoLoginService.signIn(SSO_SERVICE_URL, studentId, password)
        },
        signInInteractively = { signIn -> authTokenManager.signInInteractively(signIn) },
        awaitOpen = authTokenManager.session::awaitOpen,
        currentSignIn = { authTokenManager.session.current },
        accountLock = accountLock,
        storedStudentId = { credentials.ntustStudentId },
        writeAccount = { studentId, password ->
            credentials.ntustStudentId = studentId
            credentials.ntustPassword = password
        },
        latch = storedPasswordLatch,
        leaveStoredAccount = { signOutPlans.get().signOut(SignOutReason.SCHOOL) },
        // The store-review account signs in without a server — see DemoAccount.
        isDemoAccount = demoAccount::matches,
        enterDemo = demoAccount::enter,
        accountChanging = { BrowserCleanup.of(context).markPending() },
        markSignedIn = { signIn -> setSignedIn(true, signIn) },
    )

    /**
     * Signs in to the backend again after it refused a request of [signIn]'s, as the
     * automatic sign-in: it shares an attempt that is under way, and makes none in the
     * pause after one that failed. Says whether it signed in; false once [signIn] has ended.
     */
    suspend fun attemptRelogin(signIn: SignIn): Boolean = authTokenManager.signInAgain(signIn)

    /** Whether the school can be asked for what the cache lacks: signed in, and not the demo, which has no server. */
    val canAskSchool: Boolean get() = authState.value && !sessions.school.isDemo

    val storedStudentId: String? get() = credentials.ntustStudentId
    internal val storedPassword: String? get() = credentials.ntustPassword
    val storedMoodleToken: String? get() = credentials.moodleToken

    suspend fun login(studentId: String, password: String): Boolean = loginMutex.withLock {
        _isLoggingIn.value = true
        _loginError.value = null

        try {
            val normalizedId = studentId.trim().uppercase()

            // Registered before authState flips: an automatic sign-in it sets off, such as Home's
            // pull, waits for this one instead of logging in too. The sign-in is read before the
            // credentials are stored, so a sign-out from here on keeps the v3 login below from
            // storing a pair. The credentials are stored, and the stored-password latch lifted,
            // the moment the school takes them; see TypedAccountSignIn.
            val outcome = typedSignIn.signIn(
                normalizedId,
                password,
                // Before the class table can show: another account must not inherit what the
                // last one imported, whether imported while signed out or left by a wipe that
                // never ran.
                prepareSignedIn = { signIn ->
                    sessions.schoolTookSignIn()
                    importedClassTables.accountDidSignIn(normalizedId)
                    // After the store knows who signed in, which empties another account's leftovers.
                    if (signIn.isDemo) demoAccount.importClassmates(signIn)
                },
            ) { signIn, sameStudent ->
                // Best-effort: v3 JWT login failures must not block the SSO session. The automatic
                // sign-in tries again later; this one never waits for its pause.
                // Harvest a FRESH Moodle token before the v3 login. The backend verifies it against
                // moodle2, and the stored token is stale or empty right after SSO (especially on a
                // fresh install), which makes the backend reject the login with 401 invalid_token.
                // iOS does the same obtain-then-login. Best-effort: fall back to this student's
                // stored token, and never let a harvest failure block the SSO session. obtainToken
                // stores nothing once the account has signed out, and stillCurrent then keeps the
                // fallback from sending anything: the sign-out ends the sign-in before it drops
                // the credentials.
                val stillCurrent = { signIn.isCurrent }
                val moodleToken = interactiveMoodleToken(
                    harvest = { moodleTokenService.obtainToken(normalizedId, password) },
                    stored = { credentials.moodleToken },
                    sameStudent = sameStudent,
                    stillCurrent = stillCurrent,
                )
                if (moodleToken.isNullOrEmpty()) {
                    // A backend login without one could only be refused, and
                    // would count against the login limit. The automatic
                    // sign-in tries again.
                    android.util.Log.w("AuthService", "v3 login skipped: no Moodle token")
                    return@signIn SignInAttempt.FAILED
                }
                try {
                    authTokenManager.login(
                        studentId = normalizedId,
                        moodleToken = moodleToken,
                        moodlePrivateToken = null,
                        signIn = signIn,
                    )
                    SignInAttempt.SIGNED_IN
                } catch (e: CancellationException) {
                    // Cut off as its reply landed, which cancelling cannot stop: the pair is stored,
                    // nothing below runs, and an edit made with no copy to measure it against is lost.
                    if (authTokenManager.isLoggedIn) notificationSettingsSync.pushIfUnconfirmed()
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("AuthService", "v3 login failed (best-effort): ${e.logSummary()}")
                    SignInAttempt.FAILED
                }
            }

            if (outcome == TypedAccountSignIn.Outcome.SIGNED_IN) {
                // The same catch-up as the automatic sign-in, for a fresh SSO
                // login, before the registration's network call.
                followTypedSignIn(
                    startSettingsCopy = notificationSettingsSync::pushIfUnconfirmed,
                    register = pushRegistration::onSignedIn,
                )
            }

            _isLoggingIn.value = false
            outcome != TypedAccountSignIn.Outcome.NOT_SIGNED_IN
        } catch (e: CancellationException) {
            _isLoggingIn.value = false
            throw e
        } catch (e: Exception) {
            _loginError.value = context.signInErrorMessage(e)
            _isLoggingIn.value = false
            false
        }
    }

    /**
     * The try [StoredPasswordRetry] makes once a refusal of the stored password has waited its
     * turn: the stored password posted to the school, past a warm session, which says nothing
     * about it ([SchoolSignIn.storedAgain]), a Moodle token where none is stored,
     * then the backend's ([retryStoredPasswordChain]). True once it ran to the end, and with
     * nothing to try.
     */
    internal suspend fun retryStoredPassword(): Boolean {
        if (sessions.school.isDemo) return true
        // Made before anything is posted: a sign-in by hand or a sign-out meanwhile moves the
        // generation it reads, and leaves it nothing to settle.
        val settlement = RetrySettlement(
            storedPasswordLatch,
            studentId = credentials.ntustStudentId ?: return true,
            password = credentials.ntustPassword ?: return true,
        )
        return retryStoredPasswordChain(
            signInToSchool = { signInAgainToSchool(settlement::schoolAccepted) },
            moodleTokenMissing = { credentials.moodleToken.isNullOrEmpty() },
            renewMoodleToken = { moodleTokenService.renewToken(null) },
            settle = settlement::settle,
            // Past the pause after a failed sign-in only with the Moodle token it lacked, which
            // this try's harvest, paced by the refusals' own schedule, has just got: any other
            // try waits it out, as the backend takes 5 logins per 15 minutes.
            signInToBackend = { renewedToken ->
                if (renewedToken) authTokenManager.signInIfSignedOutNow() else authTokenManager.signInIfSignedOut()
            },
        )
    }

    /**
     * [ensureAuthenticated] for the retry of a refused stored password: it posts, warm session or
     * not, and tells [accepted] when the school takes the post.
     */
    private suspend fun signInAgainToSchool(accepted: () -> Unit): Boolean {
        // Read before the lock: a sign-in by hand this try waits behind clears the refusal, and
        // moves the generation, by the time the lock is held.
        val refused = storedPasswordLatch.generation
        return loginMutex.withLock {
            if (sessions.school.isDemo) return@withLock false
            try {
                schoolSignIn.storedAgain(accepted = accepted, stillRefused = {
                    storedPasswordLatch.generation == refused && storedPasswordLatch.refusals.value > 0
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun ensureAuthenticated(): Boolean = loginMutex.withLock {
        // No session to build: every caller then reads its cache, which is
        // where the demo data is.
        if (sessions.school.isDemo) return@withLock false
        try {
            schoolSignIn.stored()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    private companion object {
        // The service the school sign-in signs in to.
        const val SSO_SERVICE_URL = "https://courseselection.ntust.edu.tw/"
    }
}
