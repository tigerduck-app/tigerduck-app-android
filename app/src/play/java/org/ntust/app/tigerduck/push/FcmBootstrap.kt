package org.ntust.app.tigerduck.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Play-flavor wiring for FCM. Pulls the current registration token at app
 * start so the server has a fresh entry for fresh installs (`onNewToken`
 * only fires on rotation). When `google-services.json` is absent at build
 * time `FirebaseApp.getApps` returns empty and this becomes a silent no-op,
 * which keeps debug builds without Firebase config buildable end-to-end.
 *
 * Also the FCM half of the automatic registration retry
 * ([retryRegistrationIfDue]); the rules for when one is due live in
 * [PushRegistrationService], which is flavor-neutral.
 *
 * The fdroid variant ships a stub at the same FQN so `main/` can call
 * `start()` and `retryRegistrationIfDue()` without conditional code.
 */
@Singleton
class FcmBootstrap @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registration: PushRegistrationService,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    // firebase-messaging 25.1.2 (BOM 34.18.0) deprecated most of the token
    // surface at once — getInstance(), getToken(), deleteToken(), and
    // FirebaseMessagingService.onNewToken() — without shipping a replacement
    // in the same artifact. There is nothing to migrate to yet, so this is
    // suppressed rather than rewritten. Revisit when Firebase publishes the
    // successor API; the paired suppression is on FcmService.onNewToken.
    @Suppress("DEPRECATION")
    fun start() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNullOrBlank()) return@addOnSuccessListener
            scope.launch { registration.update(token) }
        }
        // Connectivity coming back is when a registration that failed for
        // the lack of it can land. Registered once per process, here, where
        // Firebase is known to be configured.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = retryRegistrationIfDue()
                })
        }.onFailure { e -> Log.w(TAG, "could not watch connectivity for registration retries", e) }
    }

    /**
     * Retries a device registration that has not landed, if one is due:
     * [PushRegistrationService.retryRegistrationIfDue] decides that, applying
     * the consent and flavor gates before anything is fetched or sent. Called
     * on app foreground (`MainActivity.onResume`), from
     * `BackgroundSyncWorker.doWork`, and when connectivity returns.
     */
    fun retryRegistrationIfDue() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        scope.launch { registration.retryRegistrationIfDue { currentToken() } }
    }

    // Same deprecation as start().
    @Suppress("DEPRECATION")
    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private companion object {
        const val TAG = "Push.FcmBootstrap"
    }
}
