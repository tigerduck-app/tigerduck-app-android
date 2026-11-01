package org.ntust.app.tigerduck.notification

import android.app.Application
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The class start the real class reminder scheduler reports, which
 * LiveActivityManager keeps a wake for so the class's banner is cleared when
 * it begins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassReminderNextStartTest {

    private val scheduler = ClassPreparingNotificationScheduler(RuntimeEnvironment.getApplication())

    // Meets on Wednesdays, periods 3–4: 10:20–12:10.
    private val course = Course(
        courseNo = "CS3005301",
        courseName = "Data Structures",
        scheduleJson = """{"3":["3","4"]}""",
    )

    /** Wednesday 16 September 2026 in Taipei, unless [day] says otherwise. */
    private fun taipei(hour: Int, minute: Int, day: Int = 16): Long =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()

    private fun armAt(nowMs: Long, courses: List<Course> = listOf(course), leadMinutes: Long = 10): Long? {
        AppClock.setOverride(ClockOverride(nowMs, frozen = true, savedAtRealMillis = System.currentTimeMillis()))
        // With no calendar every day falls in the one term.
        val term = SemesterCodes.termOn(AcademicCalendar.EMPTY, LocalDate.now())
        return scheduler.scheduleAll(mapOf(term to courses), emptyMap(), leadMinutes * 60, AcademicCalendar.EMPTY, emptySet())
    }

    @After
    fun tearDown() {
        AppClock.setOverride(null)
    }

    @Test
    fun `the next class a reminder is armed for`() {
        assertEquals(taipei(10, 20), armAt(taipei(9, 0)))
    }

    @Test
    fun `a class whose reminder has gone off still counts until it begins`() {
        assertEquals(taipei(10, 20), armAt(taipei(10, 15)))
    }

    @Test
    fun `once today's class has begun, the next week's`() {
        assertEquals(taipei(10, 20, day = 23), armAt(taipei(10, 21)))
    }

    @Test
    fun `no courses, no class`() {
        assertNull(armAt(taipei(9, 0), courses = emptyList()))
    }
}
