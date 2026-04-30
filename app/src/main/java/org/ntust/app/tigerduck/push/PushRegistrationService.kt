package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coalesces the two events that trigger a server-side device registration —
 * an FCM token arriving and the user signing in — and POSTs once both are
 * available. Mirrors the iOS PushRegistrationService actor.
 */
@Singleton
class PushRegistrationService @Inject constructor(
    private val identity: PushIdentity,
    private val api: PushApiClient,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var fcmToken: String? = null
    private var debounceJob: Job? = null

    suspend fun update(fcmToken: String) {
        val changed = mutex.withLock {
            if (fcmToken == this.fcmToken) return@withLock false
            this.fcmToken = fcmToken
            true
        }
        if (changed) scheduleRegister()
    }

    suspend fun onSignedIn(userId: String) {
        identity.setUserId(userId)
        scheduleRegister()
    }

    fun unregister() {
        scope.launch {
            val deviceId = identity.deviceId()
            runCatching { api.unregister(deviceId) }
                .onFailure { Log.w(TAG, "unregister failed", it) }
            mutex.withLock { fcmToken = null }
        }
    }

    private fun scheduleRegister() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            // Coalesce the token + sign-in arrivals so we only POST once.
            delay(250)
            performRegister()
        }
    }

    private suspend fun performRegister() {
        val token = mutex.withLock { fcmToken } ?: return
        val userId = identity.userId() ?: return
        val deviceId = identity.deviceId()
        runCatching {
            api.register(
                DeviceRegisterRequest(
                    userId = userId,
                    deviceId = deviceId,
                    ptsTokenHex = token,
                )
            )
        }.onFailure { Log.w(TAG, "register failed", it) }
    }

    private companion object {
        const val TAG = "Push.Register"
    }
}
