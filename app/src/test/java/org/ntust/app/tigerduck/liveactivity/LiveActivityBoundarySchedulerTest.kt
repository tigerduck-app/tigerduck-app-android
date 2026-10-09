// Pins how the Live Update's two alarms reach AlarmManager. BoundaryTriggerTest
// pins when they fire; this pins what kind of alarm each one is, which no
// trigger time shows: the state alarm wakes the phone and gets through Doze,
// with exact-alarm access or without it, and the minute alarm never wakes it.

package org.ntust.app.tigerduck.liveactivity

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityBoundarySchedulerTest {

    private lateinit var context: Application
    private lateinit var scheduler: LiveActivityBoundaryScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scheduler = LiveActivityBoundaryScheduler(context)
    }

    private fun scheduled(): List<ScheduledAlarm> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun alarmFor(appMillis: Long): ScheduledAlarm =
        scheduled().single { it.triggerAtMs == AppClock.realTimeFor(appMillis) }

    @Test
    fun `with exact alarms the state alarm wakes the phone on time, through Doze`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.scheduleAt(STATE_AT)

        val alarm = alarmFor(STATE_AT)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue("held back by Doze", alarm.isAllowWhileIdle)
        assertEquals("not exact", EXACT, alarm.windowLengthMs)
    }

    @Test
    fun `with exact alarms the minute alarm is on time but never wakes the phone`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT)

        val alarm = alarmFor(MINUTE_AT)
        assertEquals(AlarmManager.RTC, alarm.type)
        assertFalse("allowed while idle", alarm.isAllowWhileIdle)
        assertEquals("not exact", EXACT, alarm.windowLengthMs)
    }

    @Test
    fun `without exact alarms the state alarm still gets through Doze, and the minute alarm waits`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.scheduleAt(STATE_AT)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT)

        assertInexactFallback()
    }

    @Test
    @Config(shadows = [ExactAccessRevokedMidCall::class])
    fun `exact access revoked between the check and the call falls back the same way`() {
        scheduler.scheduleAt(STATE_AT)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT)

        assertInexactFallback()
    }

    private fun assertInexactFallback() {
        val state = alarmFor(STATE_AT)
        assertEquals(AlarmManager.RTC_WAKEUP, state.type)
        assertTrue("state alarm held back by Doze", state.isAllowWhileIdle)

        val minute = alarmFor(MINUTE_AT)
        assertEquals(AlarmManager.RTC, minute.type)
        assertFalse("minute alarm allowed while idle", minute.isAllowWhileIdle)

        assertEquals(2, scheduled().size)
    }

    @Test
    fun `setting either alarm again moves it rather than adding another`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.scheduleAt(STATE_AT)
        scheduler.scheduleAt(STATE_AT + 60_000L)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT + 60_000L)

        assertEquals(
            setOf(AppClock.realTimeFor(STATE_AT + 60_000L), AppClock.realTimeFor(MINUTE_AT + 60_000L)),
            scheduled().map { it.triggerAtMs }.toSet(),
        )
        assertEquals(2, scheduled().size)
    }

    @Test
    fun `no countdown left to spell out clears the minute alarm only`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.scheduleAt(STATE_AT)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT)

        scheduler.scheduleMinuteChangeAt(null)

        assertEquals(AlarmManager.RTC_WAKEUP, scheduled().single().type)
    }

    @Test
    fun `cancel clears both alarms`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.scheduleAt(STATE_AT)
        scheduler.scheduleMinuteChangeAt(MINUTE_AT)

        scheduler.cancel()

        assertEquals(emptyList<ScheduledAlarm>(), scheduled())
    }

    /**
     * Exact-alarm access that the user takes away between
     * `canScheduleExactAlarms()` and the exact call, which then throws.
     */
    @Implements(AlarmManager::class)
    class ExactAccessRevokedMidCall : ShadowAlarmManager() {
        @Implementation
        override fun canScheduleExactAlarms(): Boolean = true

        @Implementation
        override fun setExact(type: Int, triggerAtTime: Long, operation: PendingIntent): Unit =
            throw SecurityException("SCHEDULE_EXACT_ALARM revoked")

        @Implementation
        override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent): Unit =
            throw SecurityException("SCHEDULE_EXACT_ALARM revoked")
    }

    private companion object {
        // Far enough ahead that nothing treats either as already due.
        const val STATE_AT = 4_102_444_800_000L
        const val MINUTE_AT = STATE_AT + 37_000L

        /** `AlarmManager.WINDOW_EXACT`, which the SDK hides. */
        const val EXACT = 0L
    }
}
