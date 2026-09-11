package org.ntust.app.tigerduck.push

import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.serverpush.ServerPushIntentToken
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject
    lateinit var registration: PushRegistrationService
    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope
    @Inject
    lateinit var intentToken: ServerPushIntentToken

    // Deprecated in firebase-messaging 25.1.2 with no replacement callback —
    // see the note in FcmBootstrap.start(). Suppressed rather than marked
    // @Deprecated, which would push the warning onto every caller of a
    // framework entry point we do not control.
    @Suppress("OVERRIDE_DEPRECATION")
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
        // Accept any case-variant the backend might emit ("true"/"True"/"TRUE")
        // plus "1"; anything else (including null) defaults to silent.
        val forceRing = data["force_ring"]?.lowercase() in setOf("true", "1")
        when (data["kind"]) {
            "sync_trigger" -> {
                Log.d(TAG, "Silent sync trigger received — enqueuing background sync")
                WorkManager.getInstance(this)
                    .enqueue(OneTimeWorkRequestBuilder<BackgroundSyncWorker>().build())
                return
            }
            "custom_push_bulletin" -> {
                val bulletinId = data["bulletin_id"]?.toIntOrNull() ?: return
                val title = data["title"].orEmpty()
                val body = data["body"].orEmpty()
                val channelId =
                    if (forceRing) NotificationChannels.BULLETINS_SOUND
                    else NotificationChannels.BULLETINS_SILENT
                showBulletinNotification(bulletinId, title, body, channelId, forceRing)
            }
            "custom_push_popup" -> {
                val nid = data["notification_id"] ?: return
                val title = data["title"].orEmpty()
                val body = data["body"].orEmpty()
                showServerPopupNotification(nid, title, body, forceRing)
            }
            "reauth_required" -> {
                val title = data["title"].orEmpty()
                val body = data["body"].orEmpty()
                // The server always carries the copy; a missing string means
                // the backend misfired, and a blank notification is worse than
                // none — that blank row is exactly the old behaviour being
                // fixed here.
                if (title.isBlank() || body.isBlank()) {
                    Log.w(TAG, "reauth_required push arrived without copy — dropping")
                    return
                }
                showReauthNotification(title, body)
            }
            else -> {
                // Legacy / scraped subscription bulletins. Route through the
                // pre-existing BULLETINS channel so any per-channel mute or
                // sound override the user set on prior versions is preserved
                // — BULLETINS_SOUND is reserved for operator one-offs.
                val bulletinId = data["bulletin_id"]?.toIntOrNull() ?: return
                val title = data["title"] ?: return
                val body = data["body"].orEmpty()
                showBulletinNotification(
                    bulletinId,
                    title,
                    body,
                    NotificationChannels.BULLETINS,
                    forceRing = true,
                )
            }
        }
    }

    private fun showBulletinNotification(
        id: Int,
        title: String,
        body: String,
        channelId: String,
        forceRing: Boolean,
    ) {
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
        val notification = NotificationCompat.Builder(this, channelId)
            // Status-bar small icon must be a transparent monochrome
            // silhouette; passing the full-color launcher mipmap lets
            // Android fall back to a generic circle.
            .setSmallIcon(R.drawable.ic_notification)
            // Brand tint for the shade badge; the status-bar glyph stays mono.
            .setColor(ContextCompat.getColor(this, R.color.duck_yellow))
            // Large icon (rendered in the notification body) is the
            // full-color TigerDuck character — gives the brand visible
            // presence without violating the silhouette-only contract
            // the status bar enforces above.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (forceRing) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .build()
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "notify failed for bulletin $id", it) }
    }

    private fun showServerPopupNotification(
        notificationId: String,
        title: String,
        body: String,
        forceRing: Boolean,
    ) {
        // Encode the id as a single path segment so any URI-reserved char
        // (`?`, `/`, `#`, `&`) in a backend-issued id can't shift query
        // boundaries and corrupt MainActivity.handleServerPushIntent's parse.
        // Title / body are carried in full: a prior cap of 256 chars left the
        // notification shade showing more than the in-app AlertDialog, which
        // truncated mid-instruction.
        val encoded = Uri.encode(notificationId) +
            "?title=${Uri.encode(title)}" +
            "&body=${Uri.encode(body)}"
        val intent = Intent(
            Intent.ACTION_VIEW,
            "tigerduck://server-push/$encoded".toUri(),
            this,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Stamp the PendingIntent so MainActivity can tell it apart from
            // an explicit intent crafted by another installed app — MainActivity
            // is the exported launcher, so the deep-link path would otherwise
            // accept attacker-chosen title / body.
            putExtra(ServerPushIntentToken.EXTRA_NAME, intentToken.value)
        }
        // PendingIntent equality uses Intent.filterEquals, which compares the
        // Data URI — distinct nids already yield distinct PendingIntents
        // regardless of requestCode, so hashCode collisions here are benign.
        val pi = PendingIntent.getActivity(
            this,
            notificationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val channelId =
            if (forceRing) NotificationChannels.BULLETINS_SOUND
            else NotificationChannels.BULLETINS_SILENT
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            // Brand tint for the shade badge; the status-bar glyph stays mono.
            .setColor(ContextCompat.getColor(this, R.color.duck_yellow))
            // Same brand presence in the notification body as the bulletin
            // path — see showBulletinNotification for the silhouette /
            // large-icon split rationale.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(
                if (forceRing) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .build()
        // Use the raw notificationId as the notify() tag with a fixed int id
        // so distinct nids never collide in the shade — relying on
        // nid.hashCode() as the int id alone would let two different popups
        // overwrite each other on a 32-bit hash collision.
        runCatching { manager.notify(notificationId, NOTIFY_ID_SERVER_POPUP, notification) }
            .onFailure { Log.w(TAG, "notify failed for popup $notificationId", it) }
    }

    /**
     * Fixed id: a second reauth notice replaces the first rather than
     * stacking. The account is either broken or it isn't — one row says
     * that as well as five.
     */
    private fun showReauthNotification(title: String, body: String) {
        // `tigerduck://home` is not a registered deep link — the manifest only
        // declares the `announcement` host and MainActivity routes nothing for
        // a `home` authority — so open the launcher entry point rather than
        // mint a URI no one consumes.
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            this,
            REAUTH_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, NotificationChannels.SYSTEM)
            .setSmallIcon(R.drawable.ic_notification)
            // Brand tint for the shade badge; the status-bar glyph stays mono.
            .setColor(ContextCompat.getColor(this, R.color.duck_yellow))
            // Same brand presence in the notification body as the bulletin
            // path — see showBulletinNotification for the silhouette /
            // large-icon split rationale.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            // Title and body are rendered server-side in the device's
            // language; the client must never compose its own copy here.
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(REAUTH_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed for reauth", it) }
    }

    companion object {
        const val CHANNEL_ID = NotificationChannels.BULLETINS
        private const val TAG = "FcmService"
        // Fixed notify() id paired with per-popup tag (notificationId) so
        // the (tag, id) pair stays unique without hashing collision risk.
        private const val NOTIFY_ID_SERVER_POPUP = 1
        // Negative so it can never collide with the bulletin namespace, which
        // is keyed by positive `bulletin_id` values.
        private const val REAUTH_NOTIFICATION_ID = -1001
    }
}
