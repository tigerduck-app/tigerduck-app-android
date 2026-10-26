// How a score row finds its class-table course, and how that course's
// schedule and the header's term line read in the course's detail. Mirrors
// iOS ScoreCourseDetailSheet.resolveRosterCourse / formatScheduleCode.

package org.ntust.app.tigerduck.ui.screen.score

import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.periodOrder
import org.ntust.app.tigerduck.util.replaceIosArg

internal object ScoreCourseRoster {

    /**
     * The course of [roster] that [course] is, by course number, or null.
     * [roster] is the cached class table of the course's own term, portal
     * rows and hand-added ones together as `DataCache.loadCourses` returns
     * them — iOS searches its user-added list separately only because it
     * keeps that list outside the term's cache. A blank number matches
     * nothing: a hand-added course may carry none, and it is not this one.
     */
    fun match(roster: List<Course>, course: CourseGrade): Course? {
        val code = course.code.trim()
        if (code.isEmpty()) return null
        return roster.firstOrNull { it.courseNo.trim() == code }
    }

    /** The class table's own code: three digits of year and a term digit, or H for summer. */
    private val classTableCode = Regex("""\d{3}[0-9A-Z]""")

    /** The same term with a 0 before the term digit, as the parser's fixtures spell it. */
    private val fiveCharacterCode = Regex("""\d{3}0[12]""")

    /**
     * The semester code the class table keys its cache by for a score row's
     * [term], or null when the term is in no form the cache uses. The cache
     * files are `courses_<code>.json` with the portal's four characters,
     * "1141" or "114H" for a summer term. The score parsers — this app's,
     * iOS's and the Python reference they mirror — hand the page's 學期 cell
     * through untouched, and every term either app's score code formats is
     * four characters; but this app's parser fixtures also carry the
     * five-character "11201", which read as a file name would miss a
     * populated courses_1121.json and say "no roster". That form is read as
     * the year and the term digit. Anything else is left unmatched rather
     * than guessed at, so no wrong term's roster is ever shown.
     */
    fun semesterCode(term: String): String? {
        val code = term.trim().uppercase()
        return when {
            classTableCode.matches(code) -> code
            fiveCharacterCode.matches(code) -> code.take(3) + code.last()
            else -> null
        }
    }

    /**
     * The class table's rows for [roster], label resource to value, leaving
     * out what the cache has nothing for: a course the portal lists without
     * a cap or a room shows no "0 / 0" and no blank row, as on iOS.
     */
    fun rows(roster: Course): List<Pair<Int, String>> = buildList {
        val instructor = roster.instructor.orEmptyField().trim()
        if (instructor.isNotEmpty()) {
            add(R.string.course_detail_instructor_label to instructor)
        }
        if (roster.maxCount > 0) {
            add(R.string.score_course_detail_enrollment_label to "${roster.enrolledCount} / ${roster.maxCount}")
        }
        Course.dedupRooms(roster.classroom.orEmptyField()).trim().takeIf { it.isNotEmpty() }?.let {
            add(R.string.course_detail_classroom_label to it)
        }
        scheduleCode(roster.schedule).takeIf { it.isNotEmpty() }?.let {
            add(R.string.score_course_detail_schedule_label to it)
        }
    }

    /**
     * A String field of a cached [Course] as Gson may have left it. A row
     * written before the field existed, or by hand with only a number and a
     * name, takes Gson's Unsafe path, which skips the Kotlin default, so a
     * field declared non-null holds null (CLAUDE.md, upgrade-safe
     * persistence), and `DataCache.usableRows` vouches for courseNo and
     * courseName only. The nullable receiver makes the check real: written
     * against the field's own type the compiler calls it senseless, as
     * `usableRows` has to suppress, and the value would otherwise reach the
     * first `String` parameter check and throw there.
     */
    private fun String?.orEmptyField(): String = this ?: ""

    /**
     * Weekday number to the letter the school's course-selection pages use,
     * the inverse of what CourseLookupService reads: M T W R F S U.
     */
    private val dayCodes = mapOf(1 to "M", 2 to "T", 3 to "W", 4 to "R", 5 to "F", 6 to "S", 7 to "U")

    /**
     * [schedule] as the node string the school prints, e.g. "M3, M4, T1":
     * weekdays Monday to Sunday, and within a day the periods as taught, so
     * the evening slots A–D follow 10 instead of sorting before 1. A period
     * the app does not know goes last, as on iOS, and a weekday without a
     * letter is dropped rather than written as a bare number.
     */
    fun scheduleCode(schedule: Map<Int, List<String>>): String =
        schedule.entries
            .sortedBy { it.key }
            .flatMap { (weekday, periods) ->
                val day = dayCodes[weekday] ?: return@flatMap emptyList<String>()
                periods
                    .sortedBy { periodOrder(it).takeIf { order -> order >= 0 } ?: Int.MAX_VALUE }
                    .map { day + it }
            }
            .joinToString(", ")

    /**
     * The header's "term · credits" line from `score_course_credits_meta`.
     * The submodule writes that template for iOS, with `%1$@` in the first
     * slot and `%2$s` in the second; Java's formatter rejects the `@`, so
     * both slots are filled by replacement rather than by `String.format`.
     */
    fun creditsMeta(template: String, term: String, credits: String): String =
        template.replaceIosArg(1, term).replace("%2\$s", credits)
}
