package org.ntust.app.tigerduck.mail.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AndroidMailNotifier.cancelPosted] takes back a post by id, the stack's
 * summary with it, for a check whose account signed out while it posted
 * (tigerduck-app/tigerduck-app-android#294).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AndroidMailNotifierTakeBackTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val notifier = AndroidMailNotifier(context, NotificationChannelRegistrar(context, AppPreferences(context)))
    private val shade get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun mail(uid: Long) = MailSummary(
        uid, MailAddress("教務處", "o@x.tw"), emptyList(), emptyList(), emptyList(),
        "s$uid", null, null, MailFlags.NONE, 1, false, null, null, null,
    )

    private fun takenBack(messages: List<MailSummary>) {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.postNewMail("INBOX", messages)
        assertTrue("nothing was posted to take back", shade.allNotifications.isNotEmpty())

        notifier.cancelPosted(messages)

        assertEquals(emptyList<Any>(), shade.allNotifications.map { it.group to it.extras.getString("android.title") })
    }

    @Test
    fun `mail posted one by one is taken back with its stack's summary`() {
        takenBack(listOf(mail(1), mail(2)))
    }

    @Test
    fun `mail posted as one count is taken back with its stack's summary`() {
        takenBack((1L..(MailNotificationPlanner.COLLAPSE_ABOVE + 1L)).map(::mail))
    }
}
