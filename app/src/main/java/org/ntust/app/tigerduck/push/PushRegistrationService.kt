package org.ntust.app.tigerduck.push

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.os.ConfigurationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot consumed by the bulletin notification settings screen so users
 * (and us in support) can see whether the push pipeline is healthy.
 *
 * Carries no timestamps: the "last registration" / "last sync" times this
 * used to hold were display-only fields for the TigerSync status card's
 * relative-time rows, which spec §6 removes. Registration success/failure
 * is still tracked live via [isRegistered] / [lastError].
 */
data class PushDiagnostic(
    val hasFcmToken: Boolean,
    val isRegistered: Boolean,
    val lastError: String?,
)

/**
 * Coalesces the two events that trigger a server-side device registration —
 * an FCM token arriving and the user signing in — and POSTs once both are
 * available. Mirrors the iOS PushRegistrationService actor. A registration
 * that does not land is retried automatically; see [retryRegistrationIfDue].
 */
@Singleton
class PushRegistrationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: PushIdentity,
    private val api: PushApiClient,
    private val authTokenManager: AuthTokenManager,
    private val appPreferences: AppPreferences,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var fcmToken: String? = null
    private var debounceJob: Job? = null

    // Latched while unregister()'s API call is in flight so a token rotation or
    // FcmBootstrap restart between mutex release and HTTP completion can't
    // resurrect the row we're deleting under anon-$deviceId.
    private var isUnregistering = false

    // Bookkeeping for retryRegistrationIfDue(), guarded by [mutex]. Owed from
    // process start: the startup registration has not landed yet and, if
    // fetching its token fails, nothing else asks again. Timed on the
    // monotonic clock so a wall-clock change cannot shorten the backoff.
    private var registrationOwed = true
    private var consecutiveRegistrationFailures = 0
    private var lastRegistrationAttemptAt = SystemClock.elapsedRealtime()

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _diagnostic = MutableStateFlow(loadInitialDiagnostic())
    val diagnostic: StateFlow<PushDiagnostic> = _diagnostic.asStateFlow()

    init {
        // The in-app language picker (SettingsViewModel.setAppLanguage) only
        // flips the local preference — nothing else re-registers the device,
        // so without this the server would keep composing push copy (the
        // Moodle-reauth notification most of all — it targets a device the
        // user hasn't opened lately, exactly the device most likely to be
        // stale) in the language the device happened to register with.
        // Mirrors the wear-bridge collector on this same signal at
        // TigerDuckApp.kt's onCreate. PushRegistrationService is always
        // constructed at app start (AuthService depends on it and
        // TigerDuckApp field-injects AuthService eagerly), on both flavors,
        // so this collector is live before the language picker can fire.
        scope.launch {
            appPreferences.appLanguageChanged.collect { syncLocalePreference() }
        }
    }

    suspend fun update(fcmToken: String) {
        val changed = mutex.withLock {
            if (isUnregistering) return@withLock false
            if (fcmToken == this.fcmToken) return@withLock false
            this.fcmToken = fcmToken
            true
        }
        if (changed) {
            updateDiagnostic { it.copy(hasFcmToken = true) }
            scheduleRegister()
        }
    }

    suspend fun onSignedIn() {
        scheduleRegister()
    }

    /**
     * Consent has landed — release the registration that [performRegister]
     * was holding back.
     *
     * The FCM token usually arrives during onboarding, so by the time the
     * user finishes there is a token cached and nothing else that would
     * re-trigger a register until the next sign-in or token rotation.
     * Without this the device would stay unannounced for the rest of the
     * install on a user who never signs in.
     */
    suspend fun onOnboardingCompleted() {
        scheduleRegister()
    }

    /**
     * Re-runs a device registration that has not landed in this process,
     * when [isRegistrationRetryDue] says one is due: the automatic stand-in
     * for the Sync Now button spec §6 removed. `FcmBootstrap` (play) calls it
     * on app foreground, from `BackgroundSyncWorker`, and when connectivity
     * returns; the fdroid `FcmBootstrap` never does.
     *
     * [currentToken] fetches the FCM token and is called only once a retry is
     * known to be due, so before consent nothing at all happens. The token is
     * fetched each time rather than taken from memory because a fetch that
     * failed at startup leaves none there. The registration itself goes
     * through [scheduleRegister] and so through every gate [performRegister]
     * applies to any registration: a token, consent, no unregister in flight.
     */
    suspend fun retryRegistrationIfDue(currentToken: suspend () -> String?) {
        if (!registrationRetryDue()) return
        val token = currentToken()?.takeIf { it.isNotBlank() } ?: run {
            // The fetch itself came back empty — a device with no network at
            // boot, which is the case this retry exists for. Recorded as a
            // failed attempt, or `millisSinceLastAttempt` keeps growing past
            // every backoff and each connectivity `onAvailable` fires a fresh
            // token fetch the instant it arrives, on every handover.
            recordRegistrationAttempt(landed = false)
            return
        }
        // Again: a registration may have started or landed during the fetch.
        if (!registrationRetryDue()) return
        val changed = mutex.withLock {
            if (isUnregistering) return
            (token != fcmToken).also { fcmToken = token }
        }
        if (changed) updateDiagnostic { it.copy(hasFcmToken = true) }
        scheduleRegister()
    }

    private suspend fun registrationRetryDue(): Boolean = mutex.withLock {
        isRegistrationRetryDue(
            hasCompletedOnboarding = appPreferences.hasCompletedOnboarding,
            registrationOwed = registrationOwed && !isUnregistering,
            registrationPending = debounceJob?.isActive == true,
            consecutiveFailures = consecutiveRegistrationFailures,
            millisSinceLastAttempt = SystemClock.elapsedRealtime() - lastRegistrationAttemptAt,
        )
    }

    private suspend fun scheduleRegister() {
        mutex.withLock {
            val request = RegistrationAttemptState(registrationOwed, consecutiveRegistrationFailures)
                .onRegistrationRequested(isUnregistering)
            registrationOwed = request.state.registrationOwed
            if (!request.startNow) return@withLock
            debounceJob?.cancel()
            debounceJob = scope.launch {
                // Coalesce the token + sign-in arrivals so we only POST once.
                delay(250)
                performRegister()
            }
        }
    }

    fun unregister(authHeaderOverride: String? = null) {
        scope.launch {
            // Clear fcmToken and latch isUnregistering in the same critical
            // section that cancels the debounce so a token rotation or
            // scheduleRegister fired during the API round-trip can't resurrect
            // the row we're deleting under anon-$deviceId.
            mutex.withLock {
                debounceJob?.cancel()
                fcmToken = null
                isUnregistering = true
                // A sign-out owes no registration: the row is being deleted on
                // purpose, and the next sign-in or token asks for a new one.
                registrationOwed = false
                consecutiveRegistrationFailures = 0
            }
            val deviceId = identity.uuid()
            try {
                runCatching { api.unregister(deviceId, authHeaderOverride) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "unregister failed", e)
                    }
            } finally {
                // Always reset state so a cancelled coroutine (e.g. test scope
                // cancellation) can't leave isUnregistering latched true and
                // block all future scheduleRegister() calls.
                withContext(NonCancellable) {
                    mutex.withLock { isUnregistering = false }
                }
            }
            updateDiagnostic {
                PushDiagnostic(
                    hasFcmToken = false,
                    isRegistered = false,
                    lastError = null,
                )
            }
        }
    }

    /**
     * BCP-47 tag for the language the app is actually displaying, mirroring
     * `TigerDuckApp.createNotificationChannels()`'s resolution of
     * [AppPreferences.appLanguage]. Falls back to the system locale only
     * when the preference means "follow system" — see
     * [AppLanguageManager.resolveExplicitLocale].
     */
    private fun currentLocaleTag(): String? =
        AppLanguageManager.resolveExplicitLocale(appPreferences.appLanguage)?.toLanguageTag()
            ?: ConfigurationCompat.getLocales(context.resources.configuration)[0]?.toLanguageTag()

    /**
     * PATCH the device's locale alone in response to an in-app language
     * change, so server-composed push copy stops arriving in the old
     * language before the next unrelated registration event.
     *
     * Signed in only: the PATCH needs a session, and a signed-out device
     * has no `user_devices` row for `locale` to live on — a signed-out
     * device's next real registration (`announceDevice`) will reach the
     * server anyway once it signs in, and `performRegister` always carries
     * the current tag.
     *
     * Deliberately a locale-only PATCH rather than a full [syncNow]: a full
     * re-register also re-sends the FCM token and re-announces the
     * anonymous device row on every language toggle, and reuses a
     * debounce/mutex built around the token/sign-in coalescing use case for
     * an unrelated trigger. The backend applies `locale` only when
     * non-null, so this narrow PATCH can't be clobbered by — or clobber —
     * any of the other preference PATCHes below.
     *
     * Silent and non-fatal by design: a failure here is never shown to the
     * user. Both lookups above sit inside the same runCatching as the PATCH
     * itself, so a failure here can't kill the appLanguageChanged collector
     * in init — it stays alive and retries on the next language change.
     */
    private suspend fun syncLocalePreference() {
        if (!authTokenManager.isLoggedIn) return
        runCatching {
            val locale = currentLocaleTag() ?: return@runCatching
            val deviceId = identity.uuid()
            api.updateDevicePreferences(deviceId, locale = locale)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "locale preference PATCH failed", e)
        }
    }

    private suspend fun performRegister(): Boolean {
        // Snapshot token under the mutex so a concurrent token rotation or
        // updateServerPushOptOut can't flip state between read and POST.
        val token = mutex.withLock {
            // An unregister in flight is not an attempt at anything: the row
            // is being deleted on purpose, nothing is owed while it is, and
            // moving the retry's clock here would space out the registration
            // the next sign-in asks for.
            if (isUnregistering) return false
            fcmToken
        }
        if (token == null) {
            // No token in this process — the fetch has never succeeded. This
            // pass failed as surely as a rejected POST does, so it has to
            // climb the same backoff; without it the retry spins on every
            // trigger for as long as the device stays offline.
            recordRegistrationAttempt(landed = false)
            return false
        }
        // Nothing identifying leaves the device until the user has been
        // through onboarding and seen the privacy page. `fcmBootstrap.start()`
        // runs from Application.onCreate and an FCM token needs no permission,
        // so without this the very first launch would announce the device id
        // and token before the user had agreed to anything. Mirrors iOS, where
        // AppState only calls `pushCoordinator.enable()` once
        // `hasCompletedOnboarding` is true. `onOnboardingCompleted()` re-fires
        // this the moment consent lands, so the token is not lost.
        //
        // Recorded as an attempt that is not a failure: a device that has not
        // consented is not failing at anything, and no amount of retrying
        // opens this gate — only the user finishing onboarding does, and that
        // registers immediately through `onOnboardingCompleted()`. Climbing
        // the backoff here would leave the first retry after consent spaced
        // by half an hour for no reason. Moving the clock alone is still
        // right: this process did just run the registration path.
        if (!appPreferences.hasCompletedOnboarding) {
            recordRegistrationAttempt(landed = false, countsAsFailure = false)
            return false
        }
        val clientDeviceId = identity.uuid()
        // Announce the hardware first, every time, signed in or not. The two
        // registrations answer different questions — "which device is this"
        // and "whose account is on it" — and only the device one puts a row in
        // the table custom-push targeting reads. Gating it on sign-in would
        // leave a device that signs in immediately just as unreachable as one
        // that never signs in at all.
        val announceError = announceDevice(clientDeviceId, token)

        // Without an account there is no second registration to make. The
        // backend links the two rows on sign-in and unlinks them on sign-out,
        // so nothing is delivered twice either way.
        if (!authTokenManager.isLoggedIn) {
            updateDiagnostic {
                it.copy(
                    hasFcmToken = true,
                    // Not isRegistered: no account row exists, and the settings
                    // screen reads that flag to mean cloud sync is live. Custom
                    // push reaches this device; user-scoped push does not.
                    lastError = announceError?.let { e -> e.message ?: e::class.java.simpleName },
                )
            }
            return (announceError == null).also { recordRegistrationAttempt(landed = it) }
        }
        return runCatching {
            api.register(
                DeviceRegisterRequest(
                    clientDeviceId = clientDeviceId,
                    deviceClass = identity.deviceClass(),
                    appVersion = BuildConfig.VERSION_NAME,
                    osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
                    deviceModel = currentDeviceModel(),
                    pushToken = PushTokenIn(tokenValue = token),
                    cloudSyncEnabled = appPreferences.cloudSyncEnabled,
                    locale = currentLocaleTag(),
                )
            )
        }.fold(
            onSuccess = {
                updateDiagnostic {
                    it.copy(
                        hasFcmToken = true,
                        isRegistered = true,
                        lastError = null,
                    )
                }
                true
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "register failed", e)
                updateDiagnostic { it.copy(lastError = e.message ?: e::class.java.simpleName) }
                false
            },
        ).also { recordRegistrationAttempt(landed = it) }
    }

    /**
     * Records one pass through the registration path for the retry's backoff:
     * always when it happened, and what it did to the debt and the failure
     * streak.
     *
     * [countsAsFailure] `false` moves the clock without climbing the backoff
     * ladder — for a gate that is closed for a reason no retry can change
     * (consent), as opposed to one that failed and should be spaced out (no
     * token, a rejected POST).
     */
    private suspend fun recordRegistrationAttempt(landed: Boolean, countsAsFailure: Boolean = true) {
        mutex.withLock {
            lastRegistrationAttemptAt = SystemClock.elapsedRealtime()
            val next = RegistrationAttemptState(registrationOwed, consecutiveRegistrationFailures)
                .afterRegistrationAttempt(landed, countsAsFailure)
            registrationOwed = next.registrationOwed
            consecutiveRegistrationFailures = next.consecutiveFailures
        }
    }

    /**
     * Re-registration retry, currently called by the debug API-endpoint
     * override screen's Save/Reset actions so a changed backend URL gets a
     * fresh registration immediately. `performRegister` already updates
     * `isRegistered` / `lastError` on both the success and the API-failure
     * path; this only has to cover the no-op paths performRegister itself
     * stays silent about, so a caller showing progress doesn't silently lie
     * about success once it stops.
     */
    suspend fun syncNow(): Boolean {
        val ok = performRegister()
        if (!ok) {
            // No "not signed in" case: signed out is a supported outcome now
            // that the announce registers the device on its own, and a failed
            // announce has already written the real error to the diagnostic —
            // stamping a reason over it would replace the cause with a
            // symptom.
            val reason = mutex.withLock {
                when {
                    isUnregistering -> "Unregister in progress"
                    fcmToken == null -> "Waiting for FCM token"
                    else -> null
                }
            }
            // Only stamp a reason when performRegister bailed *before* the API
            // call — register-API failures already set lastError themselves.
            if (reason != null) {
                updateDiagnostic { it.copy(lastError = reason) }
            }
        }
        return ok
    }

    /**
     * Atomic read-modify-write for the diagnostic snapshot. Concurrent
     * performRegister.onSuccess + unregister callers would otherwise lose
     * updates if both read .value, transformed, and wrote back without a lock.
     */
    private suspend fun updateDiagnostic(block: (PushDiagnostic) -> PushDiagnostic) {
        mutex.withLock {
            val next = block(_diagnostic.value)
            _diagnostic.value = next
            val editor = prefs.edit()
                .putBoolean(KEY_HAS_TOKEN, next.hasFcmToken)
                .putBoolean(KEY_REGISTERED, next.isRegistered)
            if (next.lastError == null) editor.remove(KEY_LAST_ERR)
            else editor.putString(KEY_LAST_ERR, next.lastError)
            editor.apply()
        }
    }

    private fun loadInitialDiagnostic(): PushDiagnostic = PushDiagnostic(
        hasFcmToken = prefs.getBoolean(KEY_HAS_TOKEN, false),
        isRegistered = prefs.getBoolean(KEY_REGISTERED, false),
        lastError = prefs.getString(KEY_LAST_ERR, null),
    )

    /** Current value of the user-facing server-push opt-out. Default `false`
     *  (i.e. opted in) on every flavor except fdroid, which is always read as
     *  opted out regardless of what is stored — see
     *  [effectiveServerPushOptedOut]. Reads SharedPreferences synchronously —
     *  safe for initial UI hydration in the settings screen. */
    fun isServerPushOptedOut(): Boolean =
        effectiveServerPushOptedOut(prefs.getBoolean(KEY_SERVER_PUSH_OPT_OUT, false))

    /** PATCH the backend, and persist the opt-out locally only once the
     *  backend has accepted it — a rejected change must not survive
     *  anywhere, not just in the in-memory switch
     *  (`SettingsViewModel.setServerPushOn` already reverts that half; this
     *  is the other half). Returns `true` on full success (backend + local),
     *  `false` if the call failed, in which case the pref is left exactly
     *  where it was. `lastError` is surfaced via the diagnostic for the
     *  status card either way. See [applyOptOutIfAccepted] for the
     *  extracted, unit-tested invariant. No-ops on fdroid: the toggle that
     *  leads here is greyed out and off (`CloudSyncSettingsScreen`), but
     *  this is the actual boundary to the network call, so it is guarded
     *  here too rather than trusted to stay unreachable from the UI alone —
     *  the signed-in branch below has no FCM token dependency to fall back
     *  on the way [performRegister] does. */
    suspend fun updateServerPushOptOut(optOut: Boolean): Boolean {
        if (BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) return false
        val deviceId = identity.uuid()
        // Hold the mutex across the PATCH AND the pref write so a concurrent
        // performRegister (which snapshots under the same mutex) can't read
        // an in-flight value, and so rapid toggle taps serialize their
        // PATCH calls on the wire instead of racing to last-write-wins.
        // updateDiagnostic also acquires the mutex, so it has to run outside
        // this critical section to avoid self-deadlock.
        val error: String? = mutex.withLock {
            // Two different rows hold this flag, and which one decides
            // depends on whether there is an account. Signed in, operator
            // targeting reads `user_devices` and the PATCH owns it. Signed
            // out, targeting reads `device_registrations` and the PATCH has
            // no session to authenticate with — it would 401 and the setting
            // would never leave the device — so the announce carries it
            // instead. With no token yet there is no row to correct: the
            // attempt below is a no-op success, and the pref is stored so
            // the first announce will carry it.
            val token = if (isUnregistering) null else fcmToken
            val failure = applyOptOutIfAccepted(prefs, KEY_SERVER_PUSH_OPT_OUT, optOut) {
                if (authTokenManager.isLoggedIn) {
                    api.updateDevicePreferences(deviceId, serverPushEnabled = !optOut)
                } else if (token != null) {
                    api.registerAnonymous(
                        AnonymousDeviceRequest(
                            deviceId = deviceId,
                            deviceClass = identity.deviceClass(),
                            pushToken = token,
                            serverPushEnabled = !optOut,
                        )
                    )
                }
            }
            failure?.let {
                Log.w(TAG, "server push preference update failed", it)
                it.message ?: it::class.java.simpleName
            }
        }
        // Clear stale errors on success, set them on failure — either way the
        // status card now reflects backend reachability for this PATCH.
        updateDiagnostic { it.copy(lastError = error) }
        return error == null
    }

    /**
     * Register the physical device, with no account attached.
     *
     * Returns the failure rather than throwing, and never writes the
     * diagnostic: the caller decides what a failure here means. Signed in it
     * is a background detail — cloud sync still works — while signed out it
     * is the only registration there was, so it is the thing to report.
     */
    private suspend fun announceDevice(deviceId: String, token: String): Throwable? =
        runCatching {
            api.registerAnonymous(
                AnonymousDeviceRequest(
                    deviceId = deviceId,
                    deviceClass = identity.deviceClass(),
                    pushToken = token,
                    // Carried on every announce, not just when it changes.
                    // This row is what operator targeting filters on, and
                    // while signed out the preferences PATCH has no session
                    // to authenticate with — so the announce is the only
                    // path the opt-out has to the server.
                    serverPushEnabled = !isServerPushOptedOut(),
                )
            )
        }.fold(
            onSuccess = { null },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "device announce failed", e)
                e
            },
        )

    /**
     * Unlike [updateServerPushOptOut], this has no fdroid check of its own —
     * the toggle that reaches it is unreachable there (`CloudSyncSettingsScreen`),
     * and the network boundary is guarded once, for this and
     * [updateSyncPreferences] together, inside
     * [PushApiClient.updateDevicePreferences] — see `preferencesPatchBlockedOnFdroid`.
     */
    suspend fun updateCloudSyncEnabled(enabled: Boolean) {
        val deviceId = identity.uuid()
        api.updateDevicePreferences(deviceId, cloudSyncEnabled = enabled)
    }

    /** See [updateCloudSyncEnabled]'s note on where the fdroid guard lives. */
    suspend fun updateSyncPreferences(
        syncCourses: Boolean,
        syncCourseColors: Boolean,
        syncCourseNames: Boolean,
        syncAssignments: Boolean,
        syncAssignmentReminders: Boolean,
        syncLiveActivity: Boolean,
    ) {
        val deviceId = identity.uuid()
        runCatching {
            api.updateDevicePreferences(
                deviceId,
                syncCourses = syncCourses,
                syncCourseColors = syncCourseColors,
                syncCourseNames = syncCourseNames,
                syncAssignments = syncAssignments,
                syncAssignmentReminders = syncAssignmentReminders,
                syncLiveActivity = syncLiveActivity,
            )
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "sync preferences PATCH failed", e)
        }
    }

    private companion object {
        const val TAG = "Push.Register"
        const val PREFS_NAME = "push_diagnostics"
        const val KEY_HAS_TOKEN = "has_token"
        const val KEY_REGISTERED = "registered"
        const val KEY_LAST_ERR = "last_error"
        const val KEY_SERVER_PUSH_OPT_OUT = "server_push_user_opt_out"
    }
}

/**
 * The effective server-push opt-out: fdroid is always read as opted out — it
 * never completes a device registration to opt in or out of in the first
 * place (no FCM token) — regardless of what [storedOptOut] says.
 *
 * [flavor] defaults to [BuildConfig.FLAVOR] for the one production call
 * site, [PushRegistrationService.isServerPushOptedOut]. Tests pass it
 * explicitly instead, for the same reason
 * [org.ntust.app.tigerduck.data.preferences.effectiveCloudSyncEnabled] does:
 * this module's tests can't construct [PushRegistrationService] (real OkHttp
 * / Keystore dependencies), and a literal expectation on `BuildConfig.FLAVOR`
 * would only hold under one Gradle variant's unit-test task.
 */
internal fun effectiveServerPushOptedOut(storedOptOut: Boolean, flavor: String = BuildConfig.FLAVOR): Boolean =
    storedOptOut || flavor.equals("fdroid", ignoreCase = true)

/**
 * Whether [PushRegistrationService.retryRegistrationIfDue] should re-run a
 * device registration now. Spec §6 removed the Sync Now button, and a
 * registration that fails while the process stays warm (FCM, the hourly
 * worker and daily use all keep it warm) would otherwise wait for a sign-in,
 * a token rotation or the process dying, with no bound on how long that is.
 *
 * Due only when every one of these holds:
 * - [flavor] is not fdroid, which has no FCM token and never registers;
 * - [hasCompletedOnboarding]: nothing identifying leaves the device before
 *   the user has been through onboarding and seen the privacy page, the
 *   same gate `performRegister` applies to every registration;
 * - [registrationOwed]: a registration was asked for in this process and
 *   none has landed since, so the retry is a no-op once one has;
 * - not [registrationPending]: one already debouncing or on the wire is not
 *   retried on top of itself;
 * - [millisSinceLastAttempt] has reached [registrationRetryBackoffMillis]
 *   for [consecutiveFailures], so a trigger that fires often (every resume)
 *   cannot turn into a loop.
 *
 * [flavor] defaults to [BuildConfig.FLAVOR]; tests pass it explicitly, for
 * the reason [effectiveServerPushOptedOut] gives.
 */
internal fun isRegistrationRetryDue(
    hasCompletedOnboarding: Boolean,
    registrationOwed: Boolean,
    registrationPending: Boolean,
    consecutiveFailures: Int,
    millisSinceLastAttempt: Long,
    flavor: String = BuildConfig.FLAVOR,
): Boolean {
    if (flavor.equals("fdroid", ignoreCase = true)) return false
    if (!hasCompletedOnboarding || !registrationOwed || registrationPending) return false
    return millisSinceLastAttempt >= registrationRetryBackoffMillis(consecutiveFailures)
}

/**
 * The two pieces of bookkeeping [isRegistrationRetryDue] reads, as
 * [PushRegistrationService] holds them: whether a registration asked for in
 * this process still has not landed, and how many attempts have failed in a
 * row.
 */
internal data class RegistrationAttemptState(
    val registrationOwed: Boolean,
    val consecutiveFailures: Int,
)

/**
 * [RegistrationAttemptState] after one pass through the registration path.
 *
 * - [landed]: a registration reached the server. The debt is paid and the
 *   streak resets — this is what makes the retry a no-op afterwards.
 * - a failure that [countsAsFailure] (no FCM token, or a rejected POST): the
 *   debt stands and the streak grows, so [registrationRetryBackoffMillis]
 *   spaces the next attempt further out.
 * - a failure that does not (the consent gate): nothing changes but the
 *   caller's clock. No retry can open that gate — only the user finishing
 *   onboarding, which registers immediately — so climbing the ladder there
 *   would leave the first attempt after consent half an hour away.
 *
 * Extracted as a pure function, like [applyOptOutIfAccepted] and
 * [isRegistrationRetryDue] itself, so this state→flag mapping is testable
 * without [PushRegistrationService], which this module cannot construct
 * (real OkHttp / Android-Keystore dependencies, and neither Robolectric nor
 * a mocking library here). Only the mapping: the wiring that feeds it — the
 * `.also { recordRegistrationAttempt(...) }` on each registration path —
 * still has no test.
 */
internal fun RegistrationAttemptState.afterRegistrationAttempt(
    landed: Boolean,
    countsAsFailure: Boolean = true,
): RegistrationAttemptState = when {
    landed -> copy(registrationOwed = false, consecutiveFailures = 0)
    countsAsFailure -> copy(consecutiveFailures = consecutiveFailures + 1)
    else -> this
}

/**
 * What a registration request does: the bookkeeping it leaves behind, and
 * whether it may start a POST right now.
 */
internal data class RegistrationRequest(
    val state: RegistrationAttemptState,
    val startNow: Boolean,
)

/**
 * What [PushRegistrationService.scheduleRegister] does with a registration
 * request — a sign-in, an FCM token arriving, or consent landing — given
 * whether an unregister's DELETE is still in flight.
 *
 * [RegistrationRequest.startNow] is false for the duration of that DELETE:
 * a POST then would re-announce the `user_devices` row the DELETE is
 * removing under `anon-$deviceId`. The debt is recorded either way, which is
 * the whole point — a user who signs out and straight back in inside that
 * window has asked for a registration that cannot be served yet, and unless
 * the request is remembered the window swallows it, leaving neither a
 * registration nor anything for [isRegistrationRetryDue] to pick up.
 */
internal fun RegistrationAttemptState.onRegistrationRequested(isUnregistering: Boolean): RegistrationRequest =
    RegistrationRequest(state = copy(registrationOwed = true), startNow = !isUnregistering)

/**
 * How long the automatic registration retry waits after the last attempt:
 * 30 s after the first failure, 5 min after the second, then every 30 min
 * for as long as it keeps failing. [consecutiveFailures] of 0 is the wait
 * after the process starts, counted from then, which gives the startup
 * registration time to land before anything retries it.
 */
internal fun registrationRetryBackoffMillis(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 1 -> 30_000L
    consecutiveFailures == 2 -> 5 * 60_000L
    else -> 30 * 60_000L
}

/**
 * Commits [optOut] to [prefs] under [key] only once [attempt] — the PATCH /
 * anonymous-register call that is supposed to make the backend agree —
 * completes without throwing. A [CancellationException] is rethrown rather
 * than treated as a failure, matching structured-concurrency expectations
 * (e.g. the owning `SettingsViewModel` being cleared mid-toggle).
 *
 * Extracted as a top-level function, instead of inlined in
 * [PushRegistrationService.updateServerPushOptOut], so this one invariant —
 * a rejected change must not leave the wrong value in storage — is
 * unit-testable by itself. [PushApiClient] and [AuthTokenManager] can't be
 * constructed in a plain JVM test (real OkHttp / Android-Keystore calls, and
 * this module has neither a mocking library nor Robolectric), so there is no
 * way to make the real [PushRegistrationService.updateServerPushOptOut] fail
 * on demand — but this function can be handed a fake [SharedPreferences] and
 * a lambda that throws.
 *
 * `updateServerPushOptOut` used to write [prefs] unconditionally *before*
 * attempting the call, on the theory that a failure would eventually
 * reconcile via the next `performRegister` / `announceDevice`. That
 * silently contradicted `SettingsViewModel.setServerPushOn`'s
 * revert-and-Toast on failure, which tells the user the change did not take
 * effect while the persisted value kept the rejected one — surviving a
 * screen revisit or process death and still feeding `announceDevice`'s
 * reconciliation. The rule: record a preference only once the thing it
 * claims has actually happened.
 *
 * `setServerPushOn`, through this function, is the switch that follows that
 * rule end to end, and the pattern to copy. `SettingsViewModel.pushCloudSyncEnabled`
 * only looks like it: its enable branch writes `cloudSyncEnabled` on success
 * alone, but its one enabling caller, the TigerSync master switch, has
 * already persisted the value through `AppState.cloudSyncEnabled` before
 * calling it, so a rejected enable still leaves the preference on.
 * `SyncContentScreen`'s six switches (syncAssignments /
 * syncAssignmentReminders / syncLiveActivity / syncCourses /
 * syncCourseColors / syncCourseNames) persist locally and then PATCH,
 * unconditionally and with no revert, so a rejected PATCH leaves them
 * silently and permanently diverged from the server — the exact defect this
 * function exists to remove.
 *
 * @return `null` on success, the causing [Throwable] on failure.
 */
internal suspend fun applyOptOutIfAccepted(
    prefs: SharedPreferences,
    key: String,
    optOut: Boolean,
    attempt: suspend () -> Unit,
): Throwable? = try {
    attempt()
    prefs.edit().putBoolean(key, optOut).apply()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e
}
