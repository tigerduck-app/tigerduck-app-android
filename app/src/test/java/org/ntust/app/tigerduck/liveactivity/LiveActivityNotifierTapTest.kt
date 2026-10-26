package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Where a tap on the Live Update lands, through the real notifier. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityNotifierTapTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: LiveActivityNotifier

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val appPreferences = AppPreferences(context)
        notifier = LiveActivityNotifier(
            context,
            LiveActivityPreferences(context),
            appPreferences,
            NotificationChannelRegistrar(context, appPreferences),
        )
    }

    private fun snapshot(scenario: LiveActivityScenario, link: AppLink?) = LiveActivitySnapshot(
        scenario = scenario,
        title = "Lab 2",
        subtitle = "Operating Systems",
        locationText = null,
        instructor = null,
        countdownTarget = Date(System.currentTimeMillis() + 3_600_000),
        progress = null,
        accentHex = 0,
        sourceId = "assignment-900002",
        link = link,
    )

    private fun postedTap(): Intent {
        val posted = shadowOf(manager).getNotification(LiveActivityNotifier.NOTIFICATION_ID)
        val tap = shadowOf(posted.contentIntent)
        assertTrue(tap.isActivity)
        assertTrue("a tap target others cannot fill in", tap.isImmutable)
        return tap.savedIntent
    }

    @Test
    fun `tapping the due-soon Live Update opens the assignment`() {
        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT, AppLink.Assignment("900002")))

        val intent = postedTap()
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("tigerduck://assignment/900002", intent.dataString)
    }

    @Test
    fun `tapping a class Live Update opens the class table`() {
        notifier.apply(snapshot(LiveActivityScenario.IN_CLASS, AppLink.ClassTable))

        assertEquals("tigerduck://classtable", postedTap().dataString)
    }

    @Test
    fun `a Live Update with no link opens the app`() {
        notifier.apply(snapshot(LiveActivityScenario.ASSIGNMENT_URGENT, link = null))

        val intent = postedTap()
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.data)
    }
}
