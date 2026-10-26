package org.ntust.app.tigerduck.mail.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.notification.NotificationGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A mail's notification posted by 2.3.1 or before, which had no tag, still in the shade
 * after the update: opening the mail takes it down as it does one this version posted.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AndroidMailNotifierOlderPostsTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val notifier = AndroidMailNotifier(context, NotificationChannelRegistrar(context, AppPreferences(context)))
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannelRegistrar(context, AppPreferences(context)).ensureRegistered()
    }

    /** What 2.3.1 posted for a mail: the id and the stack this version uses, with no tag. */
    private fun postAsOlderVersion(uid: Long) {
        manager.notify(
            MailNotificationPlanner.notificationId(uid),
            NotificationCompat.Builder(context, NotificationChannels.SCHOOL_MAIL)
                .setContentTitle("s$uid")
                .setGroup(NotificationGroup.MAIL.key)
                .build(),
        )
        NotificationGroup.MAIL.postSummary(context)
    }

    /** Everything in the shade, as tag to id. */
    private fun shade() = manager.activeNotifications.map { it.tag to it.id }.toSet()

    /** The shade's notifications other than stack summaries, as tag to id. */
    private fun members() = manager.activeNotifications
        .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
        .map { it.tag to it.id }
        .toSet()

    @Test
    fun `opening a mail takes down its notification from the older version, with the stack's summary`() {
        postAsOlderVersion(7)

        notifier.cancelMessage(7)

        assertEquals(emptySet<Any>(), shade())
    }

    @Test
    fun `opening a mail leaves another mail's notification from the older version standing`() {
        postAsOlderVersion(7)
        postAsOlderVersion(8)

        notifier.cancelMessage(7)

        assertEquals(setOf(null to MailNotificationPlanner.notificationId(8)), members())
        assertEquals(1, shade().size - members().size)
    }
}
