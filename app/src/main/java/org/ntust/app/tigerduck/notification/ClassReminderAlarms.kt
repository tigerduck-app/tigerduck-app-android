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
     * replacing every one armed before, and brings a reminder already up in
     * step with its class. Each day's classes come from its own term's
     * courses in [coursesByTerm], keyed by term code.
     *
     * [startedFor] is the student ID stored when the refresh began, before it
     * loaded the courses. Nothing is armed when the ID stored now differs:
     * the student signed out, or another signed in, while the courses loaded,
     * and they are not the one the courses belong to. Null stands for nobody
     * signed in, as the demo account is.
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
        startedFor: String? = null,
    ): Long?

    /** Cancels every reminder armed. */
    fun cancelAllTracked()
}
