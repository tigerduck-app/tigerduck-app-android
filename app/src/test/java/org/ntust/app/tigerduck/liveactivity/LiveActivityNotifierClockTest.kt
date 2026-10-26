package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The Live Update's countdown under the developer menu's clock: the chronometer counts against
 * the real clock, and Android 15+ drops a post whose `when` is weeks old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityNotifierClockTest {

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

    @After
    fun tearDown() {
        AppClock.setOverride(null)
    }

    @Test
    fun `under a clock set weeks back the countdown ends at the target's real time`() {
        val appNow = System.currentTimeMillis() - 30 * DAY_MS
        AppClock.setOverride(ClockOverride(appNow, frozen = true, savedAtRealMillis = System.currentTimeMillis()))

        notifier.apply(
            LiveActivitySnapshot(
                scenario = LiveActivityScenario.ASSIGNMENT_URGENT,
                title = "Lab 2",
                subtitle = "Operating Systems",
                locationText = null,
                instructor = null,
                countdownTarget = Date(appNow + HOUR_MS),
                progress = null,
                accentHex = 0,
                sourceId = "assignment-900002",
                link = AppLink.Assignment("900002"),
            ),
        )

        val posted = shadowOf(manager).getNotification(LiveActivityNotifier.NOTIFICATION_ID)
        assertEquals((System.currentTimeMillis() + HOUR_MS).toDouble(), posted.`when`.toDouble(), 5_000.0)
    }

    private companion object {
        const val HOUR_MS = 60 * 60_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
