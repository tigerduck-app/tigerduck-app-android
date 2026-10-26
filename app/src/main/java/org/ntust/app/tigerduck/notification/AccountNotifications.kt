package org.ntust.app.tigerduck.notification

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.ntust.app.tigerduck.util.logSummary

/**
 * The signed-in student's notifications, which a sign-out takes down so the next student on the
 * phone does not see them: class and assignment reminders, and the bulletins, portal pushes and
 * sign-in notices stacked under [NotificationGroup.OTHER], each with its stack's summary.
 *
 * Not school mail: the mail account is separate from the school sign-in, and its own sign-out
 * takes its notifications down ([org.ntust.app.tigerduck.mail.MailAccount.signOut]). Not the
 * Live Update either, which LiveActivityManager owns.
 */
object AccountNotifications {

    private val groups = setOf(NotificationGroup.CLASS, NotificationGroup.ASSIGNMENT, NotificationGroup.OTHER)
        .map { it.key }
        .toSet()

    // A reminder or bulletin from before the stacks has no group. System is left out: the mail
    // sign-in failure is posted there, also without one.
    private val channels = setOf(
        NotificationChannels.CLASS_PREPARING,
        NotificationChannels.ASSIGNMENT_DUE,
        NotificationChannels.BULLETINS,
        NotificationChannels.BULLETINS_SOUND,
        NotificationChannels.BULLETINS_SILENT,
    )

    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val shown = runCatching { manager.activeNotifications }
            .onFailure { Log.w(TAG, "could not list the shown notifications: ${it.logSummary()}") }
            .getOrDefault(emptyList())
        shown.filter { it.isAccounts() }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun StatusBarNotification.isAccounts(): Boolean =
        notification.group in groups || notification.channelId in channels

    private const val TAG = "AccountNotifications"
}
