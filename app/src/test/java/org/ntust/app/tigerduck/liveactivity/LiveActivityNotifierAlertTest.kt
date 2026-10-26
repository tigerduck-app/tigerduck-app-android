package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import java.util.Date
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** When the Live Update sounds on a change of scenario, through the real notifier. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityNotifierAlertTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val appPreferences = AppPreferences(context)
    private val preferences = LiveActivityPreferences(context)
    private lateinit var notifier: LiveActivityNotifier

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        preferences.soundClassPreparing = true
        preferences.soundInClass = true
        preferences.soundAssignment = true
        notifier = LiveActivityNotifier(
            context,
            preferences,
            appPreferences,
            NotificationChannelRegistrar(context, appPreferences),
        )
    }

    private fun snapshot(
        scenario: LiveActivityScenario,
        duringClass: Boolean = scenario == LiveActivityScenario.IN_CLASS,
    ) = LiveActivitySnapshot(
        scenario = scenario,
        title = "Operating Systems",
        subtitle = "10:20–12:10",
        locationText = null,
        instructor = null,
        countdownTarget = Date(System.currentTimeMillis() + 600_000),
        progress = null,
        accentHex = 0,
        sourceId = scenario.name,
        duringClass = duringClass,
    )

    private fun posted(): Notification = shadowOf(manager).getNotification(LiveActivityNotifier.NOTIFICATION_ID)

    /** NotificationCompat's setSilent puts a notification with no group of its own in this one. */
    private val Notification.isSilent: Boolean get() = group == NotificationCompat.GROUP_KEY_SILENT

    @Test
    fun `class preparing keeps its own sound while class reminders are off`() {
        appPreferences.classReminderEnabled = false

        notifier.apply(snapshot(LiveActivityScenario.CLASS_PREPARING))

        assertFalse(posted().isSilent)
    }

    @Test
    fun `class preparing is silent while class reminders are on, which sound for the class themselves`() {
        appPreferences.classReminderEnabled = true

        notifier.apply(snapshot(LiveActivityScenario.CLASS_PREPARING))

        assertTrue(posted().isSilent)
    }

    @Test
    fun `the other scenarios keep their sound with class reminders on`() {
        appPreferences.classReminderEnabled = true

        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))
        assertFalse(posted().isSilent)

        notifier.cancel()
        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT))
        assertFalse(posted().isSilent)
    }

    @Test
    fun `a deadline taking over during class switches quietly`() {
        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))
        assertFalse(posted().isSilent)

        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT, duringClass = true))

        assertTrue(posted().isSilent)
    }

    @Test
    fun `the class taking the Live Update back from a deadline switches quietly`() {
        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))
        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT, duringClass = true))

        // The deadline passed, or was marked done, while the class goes on.
        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))

        assertTrue(posted().isSilent)
    }

    @Test
    fun `a deadline that shows as the class ends sounds`() {
        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))

        // 12:10: the class is over, and a deadline comes within its lead time.
        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT, duringClass = false))

        assertFalse(posted().isSilent)
    }

    @Test
    fun `a class that starts as a deadline passes sounds for the class`() {
        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT, duringClass = false))

        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))

        assertFalse(posted().isSilent)
    }

    @Test
    fun `a deadline showing first, before any class, still sounds`() {
        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS))
        notifier.cancel()

        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT))

        assertFalse(posted().isSilent)
    }
}
