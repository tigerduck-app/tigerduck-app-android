package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course

/**
 * A fetch's [fetched] rows of [semester], Home's or the background sync's, [merge]d onto the
 * term as it stands and its hides, under the courses' lock (`DataCache.updateCourses`), and the
 * list as written, which is what is shown and uploaded.
 *
 * A term that [onlyGains] leaves out the rows the drop list names as the commit finds it, but
 * the hand-adds ([TranscriptTerms.withoutDrops], with [waitingAdds]): its lookups began from
 * the term as it was, and a past term's rebuild that recorded its drops and saved meanwhile took
 * those courses off. Read with the term: the rebuild records its drops before it saves. So are
 * the rows a roster dropped on the server, as the last full sync found them
 * (`DataCache.fetchDropsIn`): a running term's transcript may still list them. Only course
 * selection's answer, which this phone always asks the school for, saves such a course, and its
 * roster enrolls it again. As iOS `AppServiceBridge.savedCourses` (tigerduck-app#918).
 */
internal suspend fun commitFetchedTerm(
    dataCache: DataCache,
    semester: String,
    fetched: List<Course>,
    onlyGains: Boolean,
    waitingAdds: suspend () -> Set<String>,
    merge: (fetched: List<Course>, current: List<Course>, hidden: Set<String>) -> List<Course>,
): List<Course> {
    var committed = emptyList<Course>()
    dataCache.updateCourses(semester) { current, hidden ->
        // The drop list's read takes only the user data's lock, never the drop list's own.
        val rows = if (!onlyGains) fetched else TranscriptTerms.withoutDrops(
            fetched, dataCache.fetchDropsIn(semester), current, waitingAdds(),
        )
        merge(rows, current, hidden).also { committed = it }
    }
    return committed
}
