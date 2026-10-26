package org.ntust.app.tigerduck.ui.screen.settings

import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler

/** How many courses only this phone lists, and how many only the server does. */
internal data class CourseListDifference(val localOnly: Int, val serverOnly: Int) {
    val differs: Boolean get() = localOnly > 0 || serverOnly > 0
}

/**
 * The courses re-enable check's comparison, term by term over every term
 * [cached] names (the server's and the catalogue's): the course numbers this
 * phone caches for a term, less the ones [hiding] hides there, against the
 * server's rows filed under it. Only a term both sides list courses for
 * counts, since the reconcile uploads or merges a term only one side has.
 *
 * It used to compare the current term alone, while Use Local acts on every
 * term, so a course deleted here in another term was overwritten without
 * the question being asked (tigerduck-app-android#297). iOS compares the same
 * terms the same way (`AppState.checkPendingConflicts`).
 */
internal fun courseListDifference(
    serverCourses: List<ServerCourse>,
    cached: Map<String, Collection<String>>,
    hiding: Set<String>,
): CourseListDifference {
    var localOnly = 0
    var serverOnly = 0
    for ((semester, courseNos) in cached.toSortedMap()) {
        val serverNos = serverCourses
            .filter { it.semester == semester && CourseSyncReconciler.isFiled(it.moodleId, it.courseNo, semester) }
            .mapTo(mutableSetOf()) { it.courseNo }
        val hidden = CourseTombstoneKeys.hiddenIn(semester, hiding)
        val localNos = courseNos.filterTo(mutableSetOf()) { it !in hidden }
        if (serverNos.isEmpty() || localNos.isEmpty()) continue
        localOnly += (localNos - serverNos).size
        serverOnly += (serverNos - localNos).size
    }
    return CourseListDifference(localOnly, serverOnly)
}

/**
 * What Use Server hides for the courses re-enable check, by term: in each
 * term [cached] names where both this phone and the server list courses, the
 * ones only this phone lists. The terms compared are the check's
 * ([courseListDifference]); a term only one side lists is left to the
 * reconcile, which merges the server's down. It used to act on the current
 * term alone, while the check compares every term.
 */
internal fun coursesUseServerHides(
    serverCourses: List<ServerCourse>,
    cached: Map<String, Collection<String>>,
): Map<String, Set<String>> = buildMap {
    for ((semester, courseNos) in cached.toSortedMap()) {
        val serverNos = serverCourses
            .filter { it.semester == semester && CourseSyncReconciler.isFiled(it.moodleId, it.courseNo, semester) }
            .mapTo(mutableSetOf()) { it.courseNo }
        val localNos = courseNos.toSet()
        if (serverNos.isEmpty() || localNos.isEmpty()) continue
        (localNos - serverNos).takeIf { it.isNotEmpty() }?.let { put(semester, it) }
    }
}
