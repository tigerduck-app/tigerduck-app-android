package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.shared.Course

/**
 * The class reminder alarms, as LiveActivityManager arms and cancels them;
 * [ClassPreparingNotificationScheduler] is the real one. An interface so what
 * the manager decides can be checked against a fake.
 */
interface ClassReminderAlarms {
    /**
     * Arms a reminder [leadTimeSec] ahead of each class in the days ahead,
     * replacing every one armed before. Each day's classes come from its
     * own term's courses in [coursesByTerm], keyed by term code.
     *
     * Returns the start of the next class these reminders cover, epoch ms in
     * AppClock time, or null for none. A class whose reminder has already
     * gone off counts until it begins, as its banner is still up then.
     */
    fun scheduleAll(
        coursesByTerm: Map<String, List<Course>>,
        skippedDates: Map<String, List<String>>,
        leadTimeSec: Long,
        calendar: AcademicCalendar,
        optedInHolidayIds: Set<Int>,
    ): Long?

    /** Cancels every reminder armed. */
    fun cancelAllTracked()
}
