package org.ntust.app.tigerduck.notification

import android.app.Application
import android.app.NotificationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A class reminder posted under the developer menu's clock stays up as long as under the real one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassReminderDebugClockTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        AppClock.setOverride(null)
    }

    @Test
    fun `a reminder under a clock set in the past is cleared when the class starts, not at once`() {
        val appNow = System.currentTimeMillis() - 30 * DAY_MS
        AppClock.setOverride(ClockOverride(appNow, frozen = true, savedAtRealMillis = System.currentTimeMillis()))

        // Class times come from the app's clock, as the scheduler arms them.
        ClassPreparingNotificationReceiver.post(
            context,
            appLanguage = "system",
            notificationId = 9,
            courseName = "Data Structures",
            classroom = "TR-412",
            instructor = "",
            startMs = appNow + 10 * MINUTE_MS,
            endMs = appNow + 60 * MINUTE_MS,
            leadTimeMs = 10 * MINUTE_MS,
        )

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(9)
        assertEquals(10 * MINUTE_MS.toDouble(), posted.timeoutAfter.toDouble(), 5_000.0)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
    }
}
