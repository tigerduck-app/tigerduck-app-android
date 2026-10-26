package org.ntust.app.tigerduck.auth

import android.content.Context
import android.webkit.CookieManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.PortalLinksCache
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushRegistrationService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import org.ntust.app.tigerduck.auth.AuthTokenManager
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
    private val courseService: CourseService,
    private val moodleService: MoodleService,
    private val dataCache: DataCache,
    private val bulletinCache: BulletinCache,
    private val portalLinksCache: PortalLinksCache,
    private val bulletinReadStateStore: BulletinReadStateStore,
    private val importedClassTables: ImportedClassTableStore,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val demoAccount: org.ntust.app.tigerduck.demo.DemoAccount,
    private val prefs: org.ntust.app.tigerduck.data.preferences.AppPreferences,
    private val classroomUsage: org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService,
    private val heldShareLink: org.ntust.app.tigerduck.HeldShareLink,
) {
    /**
     * A demo session presents as signed in, with or without an account.
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
    private val demoMode = demoAccount.isActive

    /** The last logout's WebView cookie wipe, awaited by [awaitWebViewCookieWipe]. */
    @Volatile
    private var webViewCookieWipe: Deferred<Unit>? = null

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
    val authState: StateFlow<Boolean> = signedInState.signedIn

    /** [authState] with its changes counted, for what must follow every one of them; see [AccountState]. */
    val accountState: StateFlow<AccountState> = signedInState.counted

    private fun setSignedIn(signedIn: Boolean) = signedInState.set(signedIn)

    private val loginMutex = Mutex()

    private val _signIns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Each sign-in by hand the school took, whoever it is: another student
     * signing in over this one leaves [authState] true throughout, so what
     * follows an account, such as the in-app browser's data, follows this too.
     */
    val signIns: SharedFlow<Unit> = _signIns.asSharedFlow()

    // Held by a sign-out while it drops the stored account and moves the sign-in generation, and
    // by a sign-in while it checks that generation and stores what it got: one that started
    // before a sign-out then keeps nothing.
    private val accountLock = Any()

    // The automatic backend sign-in, after a refused refresh and whenever
    // something finds no session.
    private val storedAccountSignIn = StoredAccountSignIn(
        tokens = authTokenManager,
        isDemo = { demoAccount.isActive },
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
            // Deliver a live_activity edit that is still unconfirmed: one made
            // while signed out, or while the v3 JWT had lapsed. With nothing
            // pending this sends nothing, so this device's values never
            // overwrite what another device wrote. It must not mark anything
            // either, because this sign-in can run from inside a pull's own
            // request — see NotificationSettingsSync.pushIfUnconfirmed.
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
    private val typedSignIn = TypedAccountSignIn(
        schoolSignIn = { studentId, password ->
            ssoLoginService.signIn(SSO_SERVICE_URL, studentId, password)
        },
        signInInteractively = { signIn -> authTokenManager.signInInteractively(signIn) },
        signInGeneration = { authTokenManager.signInGeneration },
        accountLock = accountLock,
        storedStudentId = { credentials.ntustStudentId },
        writeAccount = { studentId, password ->
            credentials.ntustStudentId = studentId
            credentials.ntustPassword = password
        },
        latch = storedPasswordLatch,
        // The store-review account signs in without a server — see DemoAccount.
        isDemoAccount = demoAccount::matches,
        enterDemo = demoAccount::enter,
        accountChanging = { BrowserCleanup.of(context).markPending() },
        markSignedIn = { setSignedIn(true) },
    )

    /**
     * Signs in to the backend again after it refused the session, as the
     * automatic sign-in: it shares an attempt that is under way, and makes
     * none in the pause after one that failed. Says whether it signed in.
     */
    suspend fun attemptRelogin(): Boolean = authTokenManager.signInAgain()

    val storedStudentId: String? get() = credentials.ntustStudentId

    /**
     * Whether the account [studentId] named, read before some suspending
     * work, is still the one signed in: false after a sign-out, or a sign-in
     * as someone else, in the meantime. What that work read from the cache
     * then belongs to the account that left.
     */
    fun isStillSignedInAs(studentId: String?): Boolean =
        authState.value && storedStudentId == studentId
    internal val storedPassword: String? get() = credentials.ntustPassword
    val storedMoodleToken: String? get() = credentials.moodleToken

    suspend fun login(studentId: String, password: String): Boolean = loginMutex.withLock {
        _isLoggingIn.value = true
        _loginError.value = null

        try {
            val normalizedId = studentId.trim().uppercase()

            // Registered before authState flips: an automatic sign-in it sets off, such as Home's
            // pull, waits for this one instead of logging in too. The generation is read before
            // the credentials are stored, so a sign-out from here on keeps the v3 login below from
            // storing a pair. The credentials are stored, and the stored-password latch lifted,
            // the moment the school takes them, and the library signs in before authState flips;
            // see TypedAccountSignIn.
            val outcome = typedSignIn.signIn(
                normalizedId,
                password,
                // Before the class table can show: another account must not inherit what the
                // last one imported, whether imported while signed out or left by a wipe that
                // never ran.
                prepareSignedIn = {
                    _signIns.tryEmit(Unit)
                    importedClassTables.accountDidSignIn(normalizedId)
                    // After the store knows who signed in, which empties another account's leftovers.
                    if (demoAccount.isActive) demoAccount.importClassmates()
                },
            ) { signIn, sameStudent ->
                // Best-effort: v3 JWT login failures must not block the SSO
                // session. The automatic sign-in tries again later; this one
                // never waits for its pause.
                // Harvest a FRESH Moodle token before the v3 login. The backend
                // verifies it against moodle2, and the stored token is stale or
                // empty right after SSO (especially on a fresh install) — which
                // makes the backend reject the login with 401 invalid_token.
                // iOS does the same obtain-then-login. Best-effort: fall back to
                // this student's stored token, and never let a harvest failure
                // block the SSO session. obtainToken stores nothing once the
                // account has signed out, and stillCurrent then keeps the
                // fallback from sending anything. It reads the stored ID too:
                // the sign-out drops the credentials before it moves the generation.
                val stillCurrent = {
                    authTokenManager.signInGeneration == signIn && credentials.ntustStudentId == normalizedId
                }
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
                        signInGeneration = signIn,
                    )
                    SignInAttempt.SIGNED_IN
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("AuthService", "v3 login failed (best-effort): ${e.logSummary()}")
                    SignInAttempt.FAILED
                }
            }

            if (outcome == TypedAccountSignIn.Outcome.SIGNED_IN) {
                runCatching { pushRegistration.onSignedIn() }
                    .onFailure { e -> if (e is CancellationException) throw e }
                // Same catch-up as the automatic sign-in, for a fresh SSO login.
                notificationSettingsSync.pushIfUnconfirmed()
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
        if (demoAccount.isActive) return true
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
            if (demoAccount.isActive) return@withLock false
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
        if (demoAccount.isActive) return@withLock false
        try {
            schoolSignIn.stored()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The library sign-in after the school's, unless the library token is still good. [generation]
     * was read before the sign-in began: a sign-out since leaves none of what it stores.
     */
    fun logout() {
        // The school account alone: the library account and the school mail stay signed in, each
        // signed out of by itself (decided for Android on 2026-10-22).
        // One step for a sign-in under way, which checks the generation under the same lock
        // before it stores anything: it either stores before this clears, or stores nothing.
        // The session it wipes, taken as it moves the generation: the
        // pushRegistration.unregister() below sends its DELETE afterwards, until
        // the server has it, and without the departing session it would find
        // none to go out with, and leak the device row.
        // On disk before the account goes: the in-app browser's clearing is owed even if the
        // process dies before it runs.
        BrowserCleanup.of(context).markPending()
        val departing = synchronized(accountLock) {
            credentials.signOutSchoolAccount()
            authTokenManager.logout()
        }
        // A queued live_activity push, and an edit still waiting to be
        // confirmed, both belong to the account that is leaving. Neither may
        // reach whoever signs in next.
        notificationSettingsSync.cancelPendingPushes()
        // Before the session goes: a 選課清單 fetch still running would
        // otherwise log the departing account back in, and the next one to
        // sign in would find its session warm and read its roster.
        courseService.cancelSharedFetches()
        moodleService.cancelSharedFetches()
        // Likewise the empty-classroom finder: its kept days came from this session, and a read
        // still queued for it must not sign the departing account in again. The next account
        // opens on its own building.
        org.ntust.app.tigerduck.emptyclassroom.forgetEmptyClassroom(classroomUsage, prefs)
        sessionManager.signOut()
        // The WebView cookie store is disk-backed and separate from the OkHttp jar cleared above.
        // The portal WebView is its only authenticated user, so a leftover session cookie there
        // would open the next account's portal link signed in as this one.
        webViewCookieWipe = wipeWebViewCookies()
        bulletinReadStateStore.clear()
        // The cache they dated is wiped below; left behind, they would date
        // the next account's data, and hold its first background sync off.
        prefs.clearSyncStamps()
        // The departing student's holiday choices, and those still waiting to go up: left
        // behind, the next student would inherit the quiet days and send the waiting ones as
        // theirs. Any PUT still queued is pinned to the sign-in that just ended.
        prefs.forgetHolidayOverrides()
        // The term picked in the class table was the departing student's. Cleared, the next
        // student's picker follows the newest term.
        prefs.classTableSelectedSemester = null
        // A share link held for a sign-in is not one for whoever signs in next.
        heldShareLink.drop()
        // A re-enable check compares this device's data, which is wiped below.
        // Left pending, it would hold the next session's assignment marks and
        // then offer Use Local, sending that emptied data over the server's.
        prefs.pendingConflictCategories = emptySet()
        // A refusal belongs to the password leaving with the account; and an
        // automatic sign-in of that account still waiting for its answer
        // latches nothing for the next one.
        storedPasswordLatch.clear()
        _loginError.value = null
        setSignedIn(false)
        pushRegistration.unregister(departing)
        // Last, so everything above still sees the demo account and keeps
        // its requests on the device.
        demoAccount.exit()
        // Wipe persisted user data on the application scope so a coroutine
        // launched from a transient ViewModel scope can't be cancelled mid-
        // delete when the user backs out of Settings or the activity dies.
        // Without this guarantee, the JSON cache survives logout and bleeds
        // into the next account on the same device.
        appScope.launch {
            // Only this sign-out empties them: a relogin in the background or a renewed session
            // keeps the class tables the user imported.
            runCatching { importedClassTables.clearForSignOut() }
                .onFailure { android.util.Log.w("AuthService", "clearForSignOut failed on logout: ${it.logSummary()}") }
            runCatching { dataCache.clearAllUserData() }
                .onFailure { android.util.Log.w("AuthService", "clearAllUserData failed on logout: ${it.logSummary()}") }
            // Bulletins are school-wide rather than private, but the read-state
            // store is already cleared above — clear the content snapshot too
            // so the next session starts coherent instead of half-stale.
            runCatching { bulletinCache.clear() }
                .onFailure { android.util.Log.w("AuthService", "bulletinCache.clear failed on logout: ${it.logSummary()}") }
            // The information-system portal shows the account's own student
            // record / grades / financial-aid links — user-scoped, must not
            // bleed into the next account on this device.
            runCatching { portalLinksCache.clear() }
                .onFailure { android.util.Log.w("AuthService", "portalLinksCache.clear failed on logout: ${it.logSummary()}") }
            // Now that the timetable is gone: a widget left alone shows the departing student's
            // until its next scheduled update, up to half an hour away.
            runCatching { widgetUpdater.updateAll() }
                .onFailure { android.util.Log.w("AuthService", "widget update failed on logout: ${it.logSummary()}") }
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    /**
     * Suspends until the last logout's WebView cookie wipe has finished. `removeAllCookies` is
     * asynchronous and `flush()` doesn't wait for it, so cookies installed for the next account
     * before it completes could be erased by it — the WebView would then open signed out.
     * Call before bridging a new session into the WebView.
     */
    suspend fun awaitWebViewCookieWipe() {
        val wipe = webViewCookieWipe ?: return
        // The callback only fails to arrive if the WebView provider is broken; a portal tap must
        // not hang forever on that.
        if (withTimeoutOrNull(WEBVIEW_COOKIE_WIPE_TIMEOUT_MS) { wipe.await() } == null) {
            android.util.Log.w("AuthService", "WebView cookie wipe still pending; syncing anyway")
        }
    }

    /**
     * Starts removing every WebView cookie and returns a [Deferred] that completes once it's done,
     * or right away if it can't start. Must run on a Looper thread, which the callback is posted
     * to — both logout entry points are UI click handlers. Throws when the system WebView is
     * missing or mid-update; logout must still go through.
     */
    private fun wipeWebViewCookies(): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        runCatching {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies {
                // Runs later on the main looper, outside the runCatching around it: a throwing
                // flush() would otherwise crash the app and leave `done` incomplete.
                runCatching { manager.flush() }
                    .onFailure { android.util.Log.w("AuthService", "WebView cookie flush failed on logout", it) }
                done.complete(Unit)
            }
        }.onFailure {
            android.util.Log.w("AuthService", "WebView cookie wipe failed on logout", it)
            done.complete(Unit)
        }
        return done
    }

    private companion object {
        // The service the school sign-in signs in to.
        const val SSO_SERVICE_URL = "https://courseselection.ntust.edu.tw/"
        const val WEBVIEW_COOKIE_WIPE_TIMEOUT_MS = 5_000L
    }
}
