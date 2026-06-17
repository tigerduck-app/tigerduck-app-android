package org.ntust.app.tigerduck.push

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    @ApplicationContext context: Context,
    private val identity: PushIdentity,
    private val api: PushApiClient,
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

    fun unregister() {
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
                runCatching { api.unregister(deviceId) }
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

    private suspend fun performRegister(): Boolean {
        // Snapshot token and opt-out under the same mutex so a concurrent
        // updateServerPushOptOut can't flip the pref between read and POST
        // and race the PATCH on the wire.
        val (token, serverPushOptOut) = mutex.withLock {
            if (isUnregistering) null to false
            else fcmToken to prefs.getBoolean(KEY_SERVER_PUSH_OPT_OUT, false)
        }
        if (token == null) return false
        val deviceId = identity.uuid()
        return runCatching {
            api.register(
                DeviceRegisterRequest(
                    userId = deviceId,
                    deviceId = deviceId,
                    ptsTokenHex = token,
                    serverPushEnabled = !serverPushOptOut,
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

    /** Persist the opt-out and PATCH the backend so the change takes effect
     *  before the next register() rolls around. Returns `true` on full success
     *  (local + backend), `false` if the PATCH failed. On failure the local
     *  pref is still flipped so the next `performRegister` reconciles, and
     *  `lastError` is surfaced via the diagnostic for the status card. */
    suspend fun updateServerPushOptOut(optOut: Boolean): Boolean {
        val deviceId = identity.uuid()
        // Hold the mutex across the pref write AND the PATCH so a concurrent
        // performRegister (which snapshots under the same mutex) can't read
        // an in-flight value, and so rapid toggle taps serialize their
        // PATCH calls on the wire instead of racing to last-write-wins.
        // updateDiagnostic also acquires the mutex, so it has to run outside
        // this critical section to avoid self-deadlock.
        val error: String? = mutex.withLock {
            prefs.edit().putBoolean(KEY_SERVER_PUSH_OPT_OUT, optOut).apply()
            runCatching {
                api.updateDevicePreferences(deviceId, serverPushEnabled = !optOut)
            }.fold(
                onSuccess = { null },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "preferences PATCH failed", e)
                    e.message ?: e::class.java.simpleName
                },
            )
        }
        // Clear stale errors on success, set them on failure — either way the
        // status card now reflects backend reachability for this PATCH.
        updateDiagnostic { it.copy(lastError = error) }
        return error == null
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
