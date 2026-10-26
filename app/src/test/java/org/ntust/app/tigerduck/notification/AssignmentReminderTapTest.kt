package org.ntust.app.tigerduck.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.MainActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where an assignment reminder's tap lands: the assignment it names, which
 * MainActivity opens in the Moodle app or the browser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AssignmentReminderTapTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun postedReminder(): Notification =
        shadowOf(manager).allNotifications.single { it.group == NotificationGroup.ASSIGNMENT.key && !isSummary(it) }

    private fun isSummary(notification: Notification) =
        notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    @Test
    fun `tapping a reminder opens its assignment`() {
        AssignmentNotificationReceiver.post(context, "system", "Homework 3", "Data Structures", "900001", AssignmentReminderOffset.HR1)

        val tap = shadowOf(postedReminder().contentIntent)
        assertTrue(tap.isActivity)
        assertTrue("a tap target others cannot fill in", tap.isImmutable)
        val intent = tap.savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("tigerduck://assignment/900001", intent.dataString)
    }

    @Test
    fun `two assignments' reminders open two assignments`() {
        AssignmentNotificationReceiver.post(context, "system", "A", "Course", "11", AssignmentReminderOffset.HR1)
        AssignmentNotificationReceiver.post(context, "system", "B", "Course", "22", AssignmentReminderOffset.HR1)

        val links = shadowOf(manager).allNotifications
            .filter { it.group == NotificationGroup.ASSIGNMENT.key && !isSummary(it) }
            .map { shadowOf(it.contentIntent).savedIntent.dataString }
            .toSet()
        assertEquals(setOf("tigerduck://assignment/11", "tigerduck://assignment/22"), links)
    }

    @Test
    fun `an id no link can carry still opens the app`() {
        AssignmentNotificationReceiver.post(context, "system", "Preview", "Course", "debug-1", AssignmentReminderOffset.HR1)

        val intent = shadowOf(postedReminder().contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.data)
    }
}
