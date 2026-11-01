package org.ntust.app.tigerduck.network

import org.ntust.app.tigerduck.shared.Course

/**
 * How long an answer from a school server is handed to the next caller that
 * asks for the same thing — see [org.ntust.app.tigerduck.util.SharedFetch].
 * Long enough to cover the screens of one launch, short enough that nothing
 * automatic can serve a minute-old roster as new. A pull to refresh drops
 * what is kept (`expireSharedResults`), so it never gets one at all.
 */
internal const val SHARED_ANSWER_WINDOW_MS = 60_000L

/**
 * One course number looked up: [course] as it came back, and whether
 * QueryCourse [answered] with rows for it. When it did not, [course] is the
 * row the term already had, or Moodle's stand-in, or null for neither — see
 * [CourseService.lookupOrFallback]. As iOS `AppServiceBridge.LookedUpCourse`.
 */
data class LookedUpCourse(val courseNo: String, val course: Course?, val answered: Boolean)

sealed class CourseServiceError : Exception() {
    class NotAuthenticated : CourseServiceError()
    class RedirectedToSSO : CourseServiceError()
    class NoCourseData : CourseServiceError()
    data class NetworkError(val cause_: Exception) : CourseServiceError()
}
