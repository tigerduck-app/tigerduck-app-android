package org.ntust.app.tigerduck.mail.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A mail's notification id comes from its UID, across a range of 100 000 ids, and the
 * app's other notifications pick their ids by themselves: the Live Update's sits inside that
 * range. Mail posts under a tag of its own, so neither replaces nor takes down the other.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AndroidMailNotifierOwnSlotsTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val notifier = AndroidMailNotifier(context, NotificationChannelRegistrar(context, AppPreferences(context)))
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** The mail whose notification id is the Live Update's. */
    private val uid = (LiveActivityNotifier.NOTIFICATION_ID - MailNotificationPlanner.SUMMARY_ID - 1).toLong()

    private fun mail(uid: Long) = MailSummary(
        uid, MailAddress("教務處", "o@x.tw"), emptyList(), emptyList(), emptyList(),
        "s$uid", null, null, MailFlags.NONE, 1, false, null, null, null,
    )

    private fun liveUpdateTitle(): String? =
        manager.activeNotifications.singleOrNull { it.id == LiveActivityNotifier.NOTIFICATION_ID && it.tag == null }
            ?.notification?.extras?.getString("android.title")

    @Test
    fun `a mail whose id is the Live Update's leaves the Live Update standing, posted and opened`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannelRegistrar(context, AppPreferences(context)).ensureRegistered()
        assertEquals(LiveActivityNotifier.NOTIFICATION_ID, MailNotificationPlanner.notificationId(uid))
        manager.notify(
            LiveActivityNotifier.NOTIFICATION_ID,
            NotificationCompat.Builder(context, NotificationChannels.SCHOOL_MAIL).setContentTitle("Calculus").build(),
        )

        notifier.postNewMail("INBOX", listOf(mail(uid)))
        assertEquals("Calculus", liveUpdateTitle())
        assertEquals(1, manager.activeNotifications.count { it.notification.extras.getString("android.title") == "s$uid" })

        notifier.cancelMessage(uid)
        assertEquals("Calculus", liveUpdateTitle())
    }
}
