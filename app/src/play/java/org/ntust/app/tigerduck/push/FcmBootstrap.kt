package org.ntust.app.tigerduck.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor wiring for FCM. Pulls the current registration token at app
 * start so the server has a fresh entry for fresh installs (`onNewToken`
 * only fires on rotation). When `google-services.json` is absent at build
 * time `FirebaseApp.getApps` returns empty and this becomes a silent no-op,
 * which keeps debug builds without Firebase config buildable end-to-end.
 *
 * The fdroid variant ships a stub at the same FQN so TigerDuckApp can call
 * `start()` from `main/` without conditional code.
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
    }
}
