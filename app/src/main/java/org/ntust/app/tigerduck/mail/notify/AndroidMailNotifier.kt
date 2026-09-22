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
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.util.replaceIosArg
import javax.inject.Inject
import javax.inject.Singleton

/** Posts what [MailNotificationPlanner] decides. Taps open the mail through MainActivity's `start_route`. */
@Singleton
class AndroidMailNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
            .putExtra("start_route", route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun base(title: String, text: String, route: String, requestCode: Int) =
        NotificationCompat.Builder(context, NotificationChannels.SCHOOL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setContentIntent(tap(route, requestCode))

    @android.annotation.SuppressLint("MissingPermission")
    override fun postNewMail(folder: String, messages: List<MailSummary>) {
        if (!allowed()) return
        val plan = MailNotificationPlanner.plan(
            messages,
            context.getString(R.string.school_mail_no_sender),
            context.getString(R.string.school_mail_no_subject),
            context.getString(R.string.school_mail_notification_title),
        ) ?: return
        when (plan) {
            is MailNotificationPlanner.Plan.Individual -> {
                plan.items.forEach { item ->
                    manager.notify(
                        MailNotificationPlanner.notificationId(item.uid),
                        base(item.title, item.text, MailRoutes.message(folder, item.uid), MailNotificationPlanner.notificationId(item.uid)).build(),
                    )
                }
                if (plan.items.size > 1) postSummary(plan.items.size)
            }
            is MailNotificationPlanner.Plan.Summary -> postSummary(plan.count)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postSummary(count: Int) {
        val title = context.getString(R.string.school_mail_new_mail_count).replaceIosArg(1, count.toString())
        manager.notify(
            MailNotificationPlanner.SUMMARY_ID,
            base(title, context.getString(R.string.feature_school_mail), MailRoutes.LIST, MailNotificationPlanner.SUMMARY_ID)
                .setGroupSummary(true)
                .build(),
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun postAuthFailure() {
        if (!allowed()) return
        manager.notify(
            MailNotificationPlanner.AUTH_FAILED_ID,
            NotificationCompat.Builder(context, NotificationChannels.SYSTEM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.school_mail_auth_failed_notification_title))
                .setContentText(context.getString(R.string.school_mail_auth_failed_notification_text))
                .setAutoCancel(true)
                .setContentIntent(tap(MailRoutes.LIST, MailNotificationPlanner.AUTH_FAILED_ID))
                .build(),
        )
    }

    override fun cancelMessage(uid: Long) = manager.cancel(MailNotificationPlanner.notificationId(uid))

    override fun cancelAll() {
        manager.activeNotifications
            .filter { it.id == MailNotificationPlanner.AUTH_FAILED_ID || it.notification.group == GROUP }
            .forEach { manager.cancel(it.id) }
    }

    private companion object {
        const val GROUP = "school_mail"
    }
}
