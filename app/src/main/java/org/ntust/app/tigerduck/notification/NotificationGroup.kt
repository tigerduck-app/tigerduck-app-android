package org.ntust.app.tigerduck.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
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
    /**
     * Every channel the stack's members post on, the stack's own first. The
     * summary goes on the first of them that is still turned on, in this
     * order rather than following whichever member came last: on a channel
     * that is off it would never show, and the members under it would go
     * without one.
     */
    private val channels: List<String>,
    /** Names the stack in its header; [OTHER] goes by the app's name alone. */
    @param:StringRes private val label: Int?,
) {
    CLASS(
        "class", 1,
        listOf(NotificationChannels.CLASS_PREPARING),
        R.string.notification_class_preparing_channel_name,
    ),
    ASSIGNMENT(
        "assignment", 2,
        listOf(NotificationChannels.ASSIGNMENT_DUE),
        R.string.notification_assignment_due_channel_name,
    ),
    // The key mail notifications were already posted under, so ones still in
    // the shade from before stack with the new. The sign-in failure is on System.
    MAIL(
        "school_mail", 3,
        listOf(NotificationChannels.SCHOOL_MAIL, NotificationChannels.SYSTEM),
        R.string.notification_school_mail_channel_name,
    ),
    // Scraped bulletins, the portal's pushes with and without sound, and the
    // sign-in notice.
    OTHER(
        "other", 4,
        listOf(
            NotificationChannels.BULLETINS,
            NotificationChannels.BULLETINS_SOUND,
            NotificationChannels.BULLETINS_SILENT,
            NotificationChannels.SYSTEM,
        ),
        null,
    );

    /**
     * Post this stack's summary, which is what holds it together; call it
     * right after posting into the stack. It never sounds itself — the
     * notification under it already has.
     *
     * Android leaves a summary standing as an empty row once everything under
     * it is gone by any way but the person's own hand, so a stack whose
     * members expire passes their [timeoutAfterMs], and one that cancels its
     * own goes through [cancel].
     *
     * A summary that expires takes every member still under it down with it,
     * so it is given the longest time any member has left rather than the
     * newest member's alone, and none at all while a member never expires.
     */
    @SuppressLint("MissingPermission")
    fun postSummary(
        context: Context,
        contentIntent: PendingIntent? = null,
        timeoutAfterMs: Long = 0L,
    ) {
        val manager = NotificationManagerCompat.from(context)
        val channelId = channels.firstOrNull { id ->
            manager.getNotificationChannelCompat(id)
                ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } == true
        } ?: return
        val name = context.getString(label ?: R.string.app_name)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(DeviceSkin.current().notificationSmallIcon)
            .setColor(ContextCompat.getColor(context, R.color.duck_yellow))
            .setContentTitle(name)
            .setGroup(key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // It says nothing but the stack's name. A private summary on a
            // lock screen that hides sensitive content stands in, redacted,
            // for the whole stack, hiding members that are public themselves,
            // such as class reminders.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (label != null) builder.setSubText(name)
        val timeout = if (timeoutAfterMs > 0L) longestTimeLeft(context, timeoutAfterMs) else 0L
        if (timeout > 0L) builder.setTimeoutAfter(timeout)
        // Tagged, so the id cannot collide with any untagged one the posters
        // pick for themselves.
        runCatching { manager.notify(SUMMARY_TAG, summaryId, builder.build()) }
            .onFailure { Log.w(TAG, "notify failed for the $key summary", it) }
    }

    /** Cancel one notification in this stack, and the summary with it if nothing else is left. */
    fun cancel(context: Context, id: Int, tag: String? = null) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(tag, id)
        // The cancel is still on its way to the system, so the notification
        // it names is skipped by hand rather than trusted to be gone.
        val rest = manager.activeNotifications.filter {
            it.notification.group == key && !(it.id == id && it.tag == tag)
        }
        if (rest.any { !it.isSummary }) return
        manager.cancel(SUMMARY_TAG, summaryId)
        // Any other summary too: the one mail was stacked under before this
        // one had a tag can still be standing beside it.
        rest.forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * The longest any member of this stack has left before it expires, at
     * least [newest] — the member just posted, which the system may not list
     * yet — or 0 if a member never expires.
     */
    private fun longestTimeLeft(context: Context, newest: Long): Long {
        val now = System.currentTimeMillis()
        var longest = newest
        for (sbn in NotificationManagerCompat.from(context).activeNotifications) {
            if (sbn.notification.group != key || sbn.isSummary) continue
            val after = sbn.notification.timeoutAfter
            if (after <= 0L) return 0L
            longest = maxOf(longest, sbn.postTime + after - now)
        }
        return longest
    }

    private companion object {
        const val SUMMARY_TAG = "group_summary"
        const val TAG = "NotificationGroup"
    }
}

private val StatusBarNotification.isSummary: Boolean
    get() = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
