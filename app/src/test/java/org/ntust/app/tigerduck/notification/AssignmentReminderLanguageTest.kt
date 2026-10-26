package org.ntust.app.tigerduck.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What language an assignment reminder is in. The phone is set to
 * Traditional Chinese, so a reminder that follows the phone instead of the
 * app's language shows up as Chinese text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "zh-rTW")
class AssignmentReminderLanguageTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun postedReminder(): Notification =
        shadowOf(manager).allNotifications.single { it.group == NotificationGroup.ASSIGNMENT.key && !isSummary(it) }

    private fun isSummary(notification: Notification) =
        notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    @Test
    fun `a reminder is in the app's language, not the phone's`() {
        AssignmentNotificationReceiver.post(
            context,
            appLanguage = "en",
            title = "Homework 3",
            courseName = "Data Structures",
            assignmentId = "900001",
            offset = AssignmentReminderOffset.HR24,
        )

        assertEquals(
            "Assignment reminder: Data Structures",
            postedReminder().extras.getString(NotificationCompat.EXTRA_TITLE),
        )
    }

    @Test
    fun `following the system keeps the phone's language`() {
        AssignmentNotificationReceiver.post(
            context,
            appLanguage = "system",
            title = "Homework 3",
            courseName = "Data Structures",
            assignmentId = "900001",
            offset = AssignmentReminderOffset.HR24,
        )

        val title = postedReminder().extras.getString(NotificationCompat.EXTRA_TITLE).orEmpty()
        assertTrue(title, title.startsWith("作業提醒"))
    }
}
