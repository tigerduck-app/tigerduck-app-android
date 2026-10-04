package org.ntust.app.tigerduck.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.ntust.app.tigerduck.R

/**
 * The stacks TigerDuck's notifications sort into in the shade: classes about
 * to start, homework, school mail, and everything else. iOS builds the same
 * stacks from each notification's `thread-id`.
 *
 * Android folds every notification posted without a group into a single
 * "TigerDuck" stack, and from Android 16 a group with no summary as well, so
 * a notification joins its stack with `setGroup(key)` and is followed by
 * [postSummary]. The Live Update belongs to none of them: it is one ongoing
 * notification, not something to stack.
 */
enum class NotificationGroup(
    val key: String,
    private val summaryId: Int,
    /** Names the stack in its header; [OTHER] goes by the app's name alone. */
    @param:StringRes private val label: Int?,
) {
    CLASS("class", 1, R.string.notification_class_preparing_channel_name),
    ASSIGNMENT("assignment", 2, R.string.notification_assignment_due_channel_name),
    // The key mail notifications were already posted under, so ones still in
    // the shade from before stack with the new.
    MAIL("school_mail", 3, R.string.notification_school_mail_channel_name),
    OTHER("other", 4, null);

    /**
     * Post this stack's summary, which is what holds it together; call it
     * right after posting into the stack. The summary goes on [channelId], that
     * notification's own channel, so it is never on a channel the person has
     * turned off while the one they just let through is on. It never sounds
     * itself — the notification under it already has.
     *
     * Android leaves a summary standing as an empty row once everything under
     * it is gone by any way but the person's own hand, so a stack whose
     * members expire passes their [timeoutAfterMs], and one that cancels its
     * own goes through [cancel].
     */
    @SuppressLint("MissingPermission")
    fun postSummary(
        context: Context,
        channelId: String,
        contentIntent: PendingIntent? = null,
        timeoutAfterMs: Long = 0L,
    ) {
        val name = context.getString(label ?: R.string.app_name)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.duck_yellow))
            .setContentTitle(name)
            .setGroup(key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        if (label != null) builder.setSubText(name)
        if (timeoutAfterMs > 0L) builder.setTimeoutAfter(timeoutAfterMs)
        // Tagged, so the id cannot collide with any untagged one the posters
        // pick for themselves.
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_TAG, summaryId, builder.build()) }
            .onFailure { Log.w(TAG, "notify failed for the $key summary", it) }
    }

    /** Cancel one notification in this stack, and the summary with it if nothing else is left. */
    fun cancel(context: Context, id: Int, tag: String? = null) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(tag, id)
        // The cancel is still on its way to the system, so the notification
        // it names is skipped by hand rather than trusted to be gone.
        val othersLeft = manager.activeNotifications.any {
            it.notification.group == key && it.tag != SUMMARY_TAG && !(it.id == id && it.tag == tag)
        }
        if (!othersLeft) manager.cancel(SUMMARY_TAG, summaryId)
    }

    private companion object {
        const val SUMMARY_TAG = "group_summary"
        const val TAG = "NotificationGroup"
    }
}
