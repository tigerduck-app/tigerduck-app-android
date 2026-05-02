package org.ntust.app.tigerduck.push

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.notification.NotificationChannels
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var registration: PushRegistrationService
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        scope.launch { registration.update(token) }
    }

    // The backend must send data-only messages (no `notification` payload).
    // Messages with a `notification` payload bypass onMessageReceived when the
    // app is backgrounded/killed, so the deep-link PendingIntent below would
    // never be attached and tapping the notification would land on the home
    // screen instead of the article.
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.notification != null) {
            // Falling back to notification.title/body would mask the real bug:
            // a `notification` payload causes Android to auto-display when the
            // app is backgrounded/killed, bypassing onMessageReceived entirely
            // and detaching the deep-link PendingIntent below — so the user
            // would silently land on the home screen instead of the article.
            Log.w(
                TAG,
                "Received FCM message with notification payload — deep-link will not work when backgrounded. Backend must send data-only messages.",
            )
        }
        val data = message.data
        val bulletinId = data["bulletin_id"]?.toIntOrNull() ?: return
        val title = data["title"] ?: return
        val body = data["body"].orEmpty()
        showBulletinNotification(bulletinId, title, body)
    }

    private fun showBulletinNotification(id: Int, title: String, body: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "tigerduck://announcement/$id".toUri(),
            this,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val manager = NotificationManagerCompat.from(this)
        // Some OEMs throw SecurityException from notify() when the runtime
        // POST_NOTIFICATIONS permission is denied; an uncaught throw here
        // would crash FirebaseMessagingService and the whole process.
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            // Status-bar small icon must be a transparent monochrome
            // silhouette; passing the full-color launcher mipmap lets
            // Android fall back to a generic circle.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "notify failed for bulletin $id", it) }
    }

    companion object {
        const val CHANNEL_ID = NotificationChannels.BULLETINS
        private const val TAG = "FcmService"
    }
}
