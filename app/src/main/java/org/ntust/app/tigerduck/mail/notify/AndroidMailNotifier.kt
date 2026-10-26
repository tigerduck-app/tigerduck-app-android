package org.ntust.app.tigerduck.mail.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.StartRoute
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.notification.NotificationGroup
import org.ntust.app.tigerduck.notification.brandedNotification
import org.ntust.app.tigerduck.util.replaceIosArg
import javax.inject.Inject
import javax.inject.Singleton

/** Posts what [MailNotificationPlanner] decides. Taps open the mail through MainActivity's [StartRoute] extra. */
@Singleton
class AndroidMailNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationChannels: NotificationChannelRegistrar,
) : MailNotifier {
    private val manager get() = NotificationManagerCompat.from(context)

    private fun allowed(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun tap(route: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(StartRoute.EXTRA, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun base(title: String, text: String, route: String, requestCode: Int) =
        context.brandedNotification(NotificationChannels.SCHOOL_MAIL)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setGroup(NotificationGroup.MAIL.key)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(tap(route, requestCode))

    @android.annotation.SuppressLint("MissingPermission")
    override fun postNewMail(folder: String, messages: List<MailSummary>) {
        if (!allowed()) return
        notificationChannels.ensureRegistered()
        val plan = MailNotificationPlanner.plan(
            messages,
            context.getString(R.string.school_mail_no_sender),
            context.getString(R.string.school_mail_no_subject),
        ) ?: return
        when (plan) {
            is MailNotificationPlanner.Plan.Individual -> plan.items.forEach { item ->
                manager.notify(
                    MailNotificationPlanner.TAG,
                    MailNotificationPlanner.notificationId(item.uid),
                    base(item.title, item.text, MailRoutes.message(folder, item.uid), MailNotificationPlanner.notificationId(item.uid)).build(),
                )
            }
            is MailNotificationPlanner.Plan.Summary -> {
                val title = context.getString(R.string.school_mail_new_mail_count).replaceIosArg(1, plan.count.toString())
                manager.notify(
                    MailNotificationPlanner.TAG,
                    MailNotificationPlanner.SUMMARY_ID,
                    base(title, context.getString(R.string.feature_school_mail), MailRoutes.LIST, MailNotificationPlanner.SUMMARY_ID).build(),
                )
            }
        }
        postGroupSummary()
    }

    private fun postGroupSummary() = NotificationGroup.MAIL.postSummary(
        context,
        contentIntent = tap(MailRoutes.LIST, MailNotificationPlanner.SUMMARY_ID),
    )

    @android.annotation.SuppressLint("MissingPermission")
    override fun postAuthFailure() {
        if (!allowed()) return
        notificationChannels.ensureRegistered()
        manager.notify(
            MailNotificationPlanner.AUTH_FAILED_ID,
            context.brandedNotification(NotificationChannels.SYSTEM)
                .setContentTitle(context.getString(R.string.school_mail_auth_failed_notification_title))
                .setContentText(context.getString(R.string.school_mail_auth_failed_notification_text))
                .setAutoCancel(true)
                // In the mail stack, since mail is what it stops, but still on
                // the System channel: a sync that has died quietly is worse
                // than an interruption.
                .setGroup(NotificationGroup.MAIL.key)
                .setContentIntent(tap(MailRoutes.LIST, MailNotificationPlanner.AUTH_FAILED_ID))
                .build(),
        )
        postGroupSummary()
    }

    override fun cancelAuthFailure() = NotificationGroup.MAIL.cancel(context, MailNotificationPlanner.AUTH_FAILED_ID)

    override fun cancelMessage(uid: Long) {
        val id = MailNotificationPlanner.notificationId(uid)
        // 2.3.1 and before posted a mail's notification with no tag, and one can still be in the
        // shade after the update. Taken down only from the mail stack: an untagged id in this
        // range can be another notification's, as the Live Update's is.
        val olderPost = manager.activeNotifications.any {
            it.tag == null && it.id == id && it.notification.group == NotificationGroup.MAIL.key
        }
        if (olderPost) NotificationGroup.MAIL.cancel(context, id)
        NotificationGroup.MAIL.cancel(context, id, MailNotificationPlanner.TAG)
    }

    override fun cancelPosted(messages: List<MailSummary>) {
        // By id, not from activeNotifications as cancelAll goes: the system lists a notification
        // only some time after it was posted, and this runs right after the post. Every id the
        // post may have used, as it was one by one or one count.
        messages.forEach { manager.cancel(MailNotificationPlanner.TAG, MailNotificationPlanner.notificationId(it.uid)) }
        manager.cancel(MailNotificationPlanner.TAG, MailNotificationPlanner.SUMMARY_ID)
        NotificationGroup.MAIL.cancelSummary(context)
    }

    override fun cancelAll() {
        // By id as well as group: an auth failure posted before it joined the stack has no group.
        manager.activeNotifications
            .filter { it.id == MailNotificationPlanner.AUTH_FAILED_ID || it.notification.group == NotificationGroup.MAIL.key }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
