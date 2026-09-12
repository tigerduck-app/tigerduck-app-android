package org.ntust.app.tigerduck.push

import android.content.Context
import android.content.SharedPreferences
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
 */
data class PushDiagnostic(
    val hasFcmToken: Boolean,
    val isRegistered: Boolean,
    val lastRegistrationAt: Long?,
    val lastSyncAt: Long?,
    val lastError: String?,
)

/**
 * Coalesces the two events that trigger a server-side device registration —
 * an FCM token arriving and the user signing in — and POSTs once both are
 * available. Mirrors the iOS PushRegistrationService actor.
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

    private suspend fun scheduleRegister() {
        mutex.withLock {
            if (isUnregistering) return@withLock
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
                    lastRegistrationAt = null,
                    lastSyncAt = null,
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
            if (isUnregistering) null else fcmToken
        }
        if (token == null) return false
        // Nothing identifying leaves the device until the user has been
        // through onboarding and seen the privacy page. `fcmBootstrap.start()`
        // runs from Application.onCreate and an FCM token needs no permission,
        // so without this the very first launch would announce the device id
        // and token before the user had agreed to anything. Mirrors iOS, where
        // AppState only calls `pushCoordinator.enable()` once
        // `hasCompletedOnboarding` is true. `onOnboardingCompleted()` re-fires
        // this the moment consent lands, so the token is not lost.
        if (!appPreferences.hasCompletedOnboarding) return false
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
                    lastRegistrationAt = if (announceError == null) {
                        System.currentTimeMillis()
                    } else {
                        it.lastRegistrationAt
                    },
                    lastError = announceError?.let { e -> e.message ?: e::class.java.simpleName },
                )
            }
            return announceError == null
        }
        return runCatching {
            api.register(
                DeviceRegisterRequest(
                    clientDeviceId = clientDeviceId,
                    deviceClass = identity.deviceClass(),
                    appVersion = BuildConfig.VERSION_NAME,
                    osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
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
                        lastRegistrationAt = System.currentTimeMillis(),
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
        )
    }

    /**
     * User-triggered re-registration from the Server Push settings screen.
     * Bumps `lastSyncAt` on success so the operator can see a "last sync"
     * timestamp separate from the FCM-token / sign-in driven registrations
     * — mirrors iOS PushServerSettingsView's "Sync now" button.
     *
     * On a no-op return (no FCM token yet, or unregister in flight)
     * surfaces the reason via `lastError` so the UI's spinner-stops-without-
     * feedback doesn't silently lie about success.
     */
    suspend fun syncNow(): Boolean {
        val ok = performRegister()
        if (ok) {
            updateDiagnostic { it.copy(lastSyncAt = System.currentTimeMillis()) }
        } else {
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
            if (next.lastRegistrationAt == null) editor.remove(KEY_LAST_REG)
            else editor.putLong(KEY_LAST_REG, next.lastRegistrationAt)
            if (next.lastSyncAt == null) editor.remove(KEY_LAST_SYNC)
            else editor.putLong(KEY_LAST_SYNC, next.lastSyncAt)
            if (next.lastError == null) editor.remove(KEY_LAST_ERR)
            else editor.putString(KEY_LAST_ERR, next.lastError)
            editor.apply()
        }
    }

    private fun loadInitialDiagnostic(): PushDiagnostic = PushDiagnostic(
        hasFcmToken = prefs.getBoolean(KEY_HAS_TOKEN, false),
        isRegistered = prefs.getBoolean(KEY_REGISTERED, false),
        lastRegistrationAt = prefs.getLong(KEY_LAST_REG, 0L).takeIf { it > 0 },
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 },
        lastError = prefs.getString(KEY_LAST_ERR, null),
    )

    /** Current value of the user-facing server-push opt-out. Default `false`
     *  (i.e. opted in). Reads SharedPreferences synchronously — safe for
     *  initial UI hydration in the settings screen. */
    fun isServerPushOptedOut(): Boolean = prefs.getBoolean(KEY_SERVER_PUSH_OPT_OUT, false)

    /** PATCH the backend, and persist the opt-out locally only once the
     *  backend has accepted it — a rejected change must not survive
     *  anywhere, not just in the in-memory switch
     *  (`SettingsViewModel.setServerPushOn` already reverts that half; this
     *  is the other half, see task-4-review.md Important 1). Returns `true`
     *  on full success (backend + local), `false` if the call failed, in
     *  which case the pref is left exactly where it was. `lastError` is
     *  surfaced via the diagnostic for the status card either way. See
     *  [applyOptOutIfAccepted] for the extracted, unit-tested invariant. */
    suspend fun updateServerPushOptOut(optOut: Boolean): Boolean {
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

    suspend fun updateCloudSyncEnabled(enabled: Boolean) {
        val deviceId = identity.uuid()
        api.updateDevicePreferences(deviceId, cloudSyncEnabled = enabled)
    }

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
        const val KEY_LAST_REG = "last_registration_at"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_ERR = "last_error"
        const val KEY_SERVER_PUSH_OPT_OUT = "server_push_user_opt_out"
    }
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
 * Fixes task-4-review.md Important 1: `updateServerPushOptOut` used to write
 * [prefs] unconditionally *before* attempting the call, on the theory that a
 * failure would eventually reconcile via the next `performRegister` /
 * `announceDevice`. That silently contradicted
 * `SettingsViewModel.setServerPushOn`'s revert-and-Toast on failure, which
 * tells the user the change did not take effect while the persisted value
 * kept the rejected one — surviving a screen revisit or process death and
 * still feeding `announceDevice`'s reconciliation. The rule: record a
 * preference only once the thing it claims has actually happened.
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
