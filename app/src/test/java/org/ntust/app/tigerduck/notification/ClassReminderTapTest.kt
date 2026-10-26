package org.ntust.app.tigerduck.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.MainActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Where a class reminder's tap lands, the class table, and the language it is in. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassReminderTapTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `tapping a class reminder opens the class table`() {
        val now = System.currentTimeMillis()
        ClassPreparingNotificationReceiver.post(
            context,
            appLanguage = "system",
            notificationId = 7,
            courseName = "Data Structures",
            classroom = "TR-412",
            instructor = "",
            startMs = now + 10 * 60_000L,
            endMs = now + 60 * 60_000L,
            leadTimeMs = 10 * 60_000L,
        )

        val posted: Notification = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(7)
        val tap = shadowOf(posted.contentIntent)
        assertTrue(tap.isActivity)
        assertTrue("a tap target others cannot fill in", tap.isImmutable)
        assertEquals(Intent.ACTION_VIEW, tap.savedIntent.action)
        assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)
        assertEquals("tigerduck://classtable", tap.savedIntent.dataString)
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `a class reminder is in the app's language, not the phone's`() {
        val now = System.currentTimeMillis()
        ClassPreparingNotificationReceiver.post(
            context,
            appLanguage = "en",
            notificationId = 8,
            courseName = "Data Structures",
            classroom = "",
            instructor = "",
            startMs = now + 10 * 60_000L,
            endMs = now + 60 * 60_000L,
            leadTimeMs = 10 * 60_000L,
        )

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(8)
        assertEquals(
            "Class starting soon: Data Structures",
            posted.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
    }
}
