// Which courses a term takes from the transcript, and how an ended term is
// rebuilt from it. Rules only: the fetch paths read the transcript, look the
// courses up and save what these return. As iOS AppServiceBridge's rules from
// tigerduck-app#890.

package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.score.ScoreCourseRoster

object TranscriptTerms {

    /** A term's course numbers, and the roster its drops are read from, null when it has none. */
    data class TermCourses(val courseNos: List<String>, val roster: List<String>?) {
        /** With no roster the term only gains: its merge removes none of its courses. */
        val onlyGains: Boolean get() = roster == null
    }

    /** An ended term rebuilt from its transcript; see [rebuiltPastTerm]. */
    data class RebuiltTerm(
        /** What the term holds now. */
        val courses: List<Course>,
        /** The transcript's classes the term keeps, or waits on a lookup for. */
        val roster: List<String>,
        /** Every other course the term may hold here or on the server, for the drop list. */
        val dropping: List<String>,
    ) {
        /** What the rebuild adds to the drop list: [dropping] but the [roster]. */
        val dropped: Set<String> get() = dropping.toSet() - roster.toSet()
    }

    /** The school English proficiency test's course number. */
    private const val ENGLISH_TEST_NO = "CC1011301"
    private const val ENGLISH_TEST_NAME = "校定英文能力檢測"

    /**
     * A term's course numbers, or null when no source answers and the term
     * keeps its cached courses. Course selection's answer owns the term it
     * serves and is the roster its drops are read from. Any other term, or
     * that one when course selection gives nothing, adds the transcript's
     * classes to [cached]: a transcript lists a running term late or in part,
     * so it never removes one. Moodle keeps a dropped class, so it adds none.
     * An empty list counts as null, since a drifted page scrapes as nothing.
     *
     * As iOS `AppServiceBridge.termCourseNos`.
     */
    fun termCourseNos(selection: List<String>?, transcript: List<String>?, cached: List<String>): TermCourses? {
        val fromSelection = uniqueCourseNos(selection.orEmpty())
        if (fromSelection.isNotEmpty()) return TermCourses(fromSelection, fromSelection)
        val fromTranscript = uniqueCourseNos(transcript.orEmpty())
        if (fromTranscript.isEmpty()) return null
        return TermCourses(uniqueCourseNos(fromTranscript + cached), roster = null)
    }

    /**
     * [termCourseNos] of [term], with the transcript's classes read off
     * [report] and [cached], the term's list, as its portal courses: a manual
     * row, added by hand or merged from the server, is the fetch's to keep as
     * it is. Never a rebuild, which [rebuildsFromTranscript] decides apart.
     *
     * Home and the background sync use it as it is, with the transcript on
     * disk, and never sign in to the score system; a past term is rebuilt by
     * the class table's own fetch.
     */
    fun termFrom(selection: List<String>?, report: ScoreReport?, term: String, cached: List<Course>): TermCourses? =
        termCourseNos(
            selection,
            report?.let { classRows(it, term).map { row -> row.code } },
            cached.filterNot { it.isManual }.map { it.courseNo },
        )

    /**
     * A term the transcript lists is rebuilt only once it lists a later one
     * too: the term has ended and the transcript holds all of it, even when
     * that leaves no class to draw. The newest term listed may still run or
     * miss grades, and the term course selection serves is its own, so both
     * only add. An exempted row can be filed under any term and counts for
     * neither.
     *
     * Terms are ordered by year, then 1 < 2 < H. iOS reads only the digits,
     * so its `114H` falls below every four-digit term.
     *
     * As iOS `AppServiceBridge.rebuildsFromTranscript`.
     */
    fun rebuildsFromTranscript(report: ScoreReport?, term: String, servedBySelection: Boolean): Boolean {
        if (servedBySelection || report == null) return false
        val code = termCode(term) ?: return false
        val order = termOrder(code) ?: return false
        val listed = report.rows().filter { it.status != GradeStatus.EXEMPTED }.mapNotNull { termCode(it.term) }
        return code in listed && listed.any { other -> termOrder(other)?.let { it > order } == true }
    }

    /**
     * A class the transcript adds joins its term only with class times: a
     * row without them, or whose lookup failed, has nothing to draw, and a
     * later fetch adds it once the lookup answers. Course selection's courses
     * and the term's [cached] ones stay as they are.
     *
     * As iOS `AppServiceBridge.drawnCourses`.
     */
    fun drawnCourses(courses: List<Course>, fromSelection: Boolean, cached: Set<String>): List<Course> =
        if (fromSelection) courses else courses.filter { it.courseNo in cached || hasTimes(it) }

    /**
     * A term only gaining's [fetched] rows without those [dropped], the
     * term's drop list read at the commit, names: the fetch looked up the
     * term as it began, and a rebuild that saved meanwhile took those off.
     * As [rebuiltPastTerm] drops neither, a course added here, in [current],
     * the term's list, stays, and so does one in [waitingAdds], whose add
     * has not gone up yet.
     */
    fun withoutDrops(fetched: List<Course>, dropped: Set<String>, current: List<Course>, waitingAdds: Set<String>): List<Course> {
        if (dropped.isEmpty()) return fetched
        val addedHere = current.filter(::isAddedHere).mapTo(mutableSetOf()) { it.courseNo } + waitingAdds
        return fetched.filter { it.courseNo !in dropped || it.courseNo in addedHere }
    }

    /**
     * [current], a term only gaining's list as its fetch commits, without the courses fetched
     * here that [dropped], the drop list read at the commit, names: [withoutDrops] leaves out
     * their answers, and the list's own copy would keep them, as such a term removes none of its
     * courses. So a fetch whose every course was dropped saves none of them. A row added by hand,
     * here or merged from the server, is the sync's to take (`CourseSyncReconciler`), and a
     * course in [waitingAdds] stays. As iOS `AppServiceBridge.savedCourses` (tigerduck-app#918).
     */
    fun withoutDroppedRows(current: List<Course>, dropped: Set<String>, waitingAdds: Set<String>): List<Course> =
        if (dropped.isEmpty()) current
        else current.filter { it.isManual || it.courseNo !in dropped || it.courseNo in waitingAdds }

    /**
     * An ended term rebuilt from [report], the transcript that lists [term]
     * and a later one: its classes the course query found with class times,
     * and the cached copy of one it did not find or of a row it cannot look
     * up, as neither removes a course. `roster` names those and the classes
     * still waiting for a lookup. Every other course the term may hold here
     * or on the server is in `dropping`: [cached], [moodleNos] and every row
     * the transcript files under the term. A course added by hand here never
     * is, nor one in [waitingAdds], whose add has not gone up yet.
     *
     * [looked] is the lookup of each of [classRows]'s codes. [cached] is the
     * term's list as `DataCache.loadCourses` returns it: iOS keeps its
     * hand-added courses apart, but here they are the list's manual rows.
     *
     * As iOS `AppServiceBridge.rebuiltPastTerm`.
     */
    fun rebuiltPastTerm(
        looked: List<LookedUpCourse>,
        report: ScoreReport,
        term: String,
        cached: List<Course>,
        moodleNos: Collection<String>,
        waitingAdds: Set<String>,
    ): RebuiltTerm {
        val unreadable = unreadableClassRows(report, term).map { it.code }
        val cachedByNo = cached.distinctBy { it.courseNo }.associateBy { it.courseNo }
        val courses = looked.mapNotNull { lookup ->
            if (lookup.answered) lookup.course?.takeIf(::hasTimes) else cachedByNo[lookup.courseNo]
        } + cached.filter { it.courseNo in unreadable }
        val roster = looked.filter { !it.answered || it.course?.let(::hasTimes) == true }.map { it.courseNo } + unreadable
        val addedHere = cached.filter(::isAddedHere).mapTo(mutableSetOf()) { it.courseNo } + waitingAdds
        val dropping = (cached.map { it.courseNo } + moodleNos + termRowCodes(report, term))
            .filter { it !in addedHere }
            .distinct()
        return RebuiltTerm(courses.distinctBy { it.courseNo }, roster.distinct(), dropping)
    }

    /**
     * The term's transcript rows that stand for a class. Withdrawn and
     * exempted rows and the English proficiency test have none to draw.
     * Codes are read with [CourseService.isCourseNo], which takes NTU and
     * NTNU courses too.
     *
     * As iOS `AppServiceBridge.classRows`.
     */
    fun classRows(report: ScoreReport, term: String): List<CourseGrade> =
        ruleRows(report, term).filter { CourseService.isCourseNo(it.code.orBlank()) }

    /**
     * The rows the rule keeps whose code is no course number, so the course
     * query cannot look them up.
     *
     * As iOS `AppServiceBridge.unreadableClassRows`.
     */
    fun unreadableClassRows(report: ScoreReport, term: String): List<CourseGrade> =
        ruleRows(report, term).filter { it.code.orBlank().isNotBlank() && !CourseService.isCourseNo(it.code.orBlank()) }

    /**
     * The school English proficiency test, a graduation requirement the
     * transcript files as a course row, under its own course number or,
     * should that change, its exact name.
     *
     * As iOS `AppServiceBridge.isEnglishProficiencyTest`.
     */
    fun isEnglishProficiencyTest(row: CourseGrade): Boolean =
        row.code.orBlank().trim() == ENGLISH_TEST_NO || row.name.orBlank().trim() == ENGLISH_TEST_NAME

    /** Added by hand on this phone rather than merged from the server, which gives a Moodle ID. */
    fun isAddedHere(course: Course): Boolean = course.isManual && course.moodleIdNumber.isNullOrEmpty()

    private fun ruleRows(report: ScoreReport, term: String): List<CourseGrade> {
        val code = termCode(term) ?: return emptyList()
        return report.rows().filter {
            termCode(it.term) == code &&
                it.status != GradeStatus.WITHDREW &&
                it.status != GradeStatus.EXEMPTED &&
                !isEnglishProficiencyTest(it)
        }
    }

    // Every row's code, whatever the rule says of it.
    private fun termRowCodes(report: ScoreReport, term: String): List<String> {
        val code = termCode(term) ?: return emptyList()
        return report.rows().filter { termCode(it.term) == code }.map { it.code.orBlank() }.filter { it.isNotBlank() }
    }

    private fun hasTimes(course: Course): Boolean = course.schedule.values.any { it.isNotEmpty() }

    private fun uniqueCourseNos(courseNos: List<String>): List<String> =
        courseNos.filter { it.isNotEmpty() }.distinct()

    // The class table's code for a transcript term, so "11401" is "1141";
    // one in no form it knows is compared as written.
    private fun termCode(term: String?): String? {
        val text = term.orBlank().trim().uppercase()
        if (text.isEmpty()) return null
        return ScoreCourseRoster.semesterCode(text) ?: text
    }

    // Year, then 1 < 2 < H; null for a code with neither.
    private fun termOrder(code: String): Int? {
        val year = code.dropLast(1).toIntOrNull() ?: return null
        val part = when (val last = code.last()) {
            in '0'..'9' -> last - '0'
            'H' -> 10
            else -> return null
        }
        return year * 100 + part
    }

    // A cached report read back by Gson holds null in a missing field, whatever its declared type.
    private fun ScoreReport.rows(): List<CourseGrade> = courses.presentRows()

    private fun List<CourseGrade?>?.presentRows(): List<CourseGrade> = this?.filterNotNull().orEmpty()

    private fun String?.orBlank(): String = this ?: ""
}
