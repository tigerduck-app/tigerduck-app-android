package org.ntust.app.tigerduck.ui

import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsRefreshPolicy
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarRefreshPolicy
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableRefreshPolicy
import org.ntust.app.tigerduck.ui.screen.home.HomeRefreshPolicy
import kotlin.time.Duration

/**
 * Every page's [RefreshPolicy] in one list, for what has to read them
 * together: the background worker, which refreshes data rather than pages,
 * and RefreshPolicyUsageTest. Each policy itself lives at the top of its
 * page's view model file.
 */
object RefreshPolicies {

    val byPage: Map<String, RefreshPolicy> = mapOf(
        "Home" to HomeRefreshPolicy,
        "ClassTable" to ClassTableRefreshPolicy,
        "Calendar" to CalendarRefreshPolicy,
        "Announcements" to AnnouncementsRefreshPolicy,
    )

    /**
     * How often the worker refreshes Moodle assignments: as often as the most
     * frequent of the pages showing them asks. Null when none of them asks.
     */
    val assignmentsEvery: Duration? =
        listOfNotNull(HomeRefreshPolicy.background, CalendarRefreshPolicy.background).minOrNull()

    /** How often the worker refreshes the timetable, which is the class table's. */
    val coursesEvery: Duration? = ClassTableRefreshPolicy.background

    /**
     * Pages whose data the worker has no way to refresh, so a background
     * period on them would do nothing. Announcements a user subscribes to
     * arrive by push instead.
     */
    val withoutBackgroundRefresh: Set<String> = setOf("Announcements")
}
