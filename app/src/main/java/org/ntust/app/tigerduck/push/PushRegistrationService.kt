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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun onSignedIn(userId: String) {
        identity.setUserId(userId)
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
            val deviceId = identity.deviceId()
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
                identity.clearUserId()
                withContext(NonCancellable) {
                    mutex.withLock { isUnregistering = false }
                }
            }
            updateDiagnostic {
                PushDiagnostic(
                    hasFcmToken = false,
                    isRegistered = false,
                    lastRegistrationAt = null,
                    lastError = null,
                )
            }
        }
    }

    private suspend fun performRegister() {
        val token = mutex.withLock { if (isUnregistering) null else fcmToken } ?: return
        val deviceId = identity.deviceId()
        // Bulletin push is opt-in via subscriptions, not gated on sign-in.
        // Without a signed-in user we register under an anonymous user_id
        // so the device row exists and subscriptions PUT doesn't 404.
        val userId = identity.userId() ?: "anon-$deviceId"
        runCatching {
            api.register(
                DeviceRegisterRequest(
                    userId = userId,
                    deviceId = deviceId,
                    ptsTokenHex = token,
                )
            )
        }.onSuccess {
            updateDiagnostic {
                it.copy(
                    hasFcmToken = true,
                    isRegistered = true,
                    lastRegistrationAt = System.currentTimeMillis(),
                    lastError = null,
                )
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "register failed", e)
            updateDiagnostic { it.copy(lastError = e.message ?: e::class.java.simpleName) }
        }
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
            if (next.lastError == null) editor.remove(KEY_LAST_ERR)
            else editor.putString(KEY_LAST_ERR, next.lastError)
            editor.apply()
        }
    }

    private fun loadInitialDiagnostic(): PushDiagnostic = PushDiagnostic(
        hasFcmToken = prefs.getBoolean(KEY_HAS_TOKEN, false),
        isRegistered = prefs.getBoolean(KEY_REGISTERED, false),
        lastRegistrationAt = prefs.getLong(KEY_LAST_REG, 0L).takeIf { it > 0 },
        lastError = prefs.getString(KEY_LAST_ERR, null),
    )

    private companion object {
        const val TAG = "Push.Register"
        const val PREFS_NAME = "push_diagnostics"
        const val KEY_HAS_TOKEN = "has_token"
        const val KEY_REGISTERED = "registered"
        const val KEY_LAST_REG = "last_registration_at"
        const val KEY_LAST_ERR = "last_error"
    }
}
