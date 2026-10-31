// Which source one term's fetch reads, and so what it looks up: course
// selection's answer is the term it serves; a past term the transcript holds
// whole is rebuilt from it; any other term only gains the transcript's
// classes; with neither, the term keeps its courses. Moodle adds no course to
// any term: it lists course sites and keeps a dropped class, so it gives only
// names, Moodle IDs and links. As iOS AppServiceBridge.fetchCourses
// (tigerduck-app#890).

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.MAX_CONCURRENT_SCHOOL_REQUESTS

internal sealed class ClassTableTermSource {
    /** The courses looked up, in order. */
    abstract val rosterNos: List<String>

    /** QueryCourse lookups in flight at once, or null for no limit. */
    open val lookupsAtOnce: Int? get() = null

    /** Course selection's answer: the term is its list, as fetched. */
    data class Selection(override val rosterNos: List<String>) : ClassTableTermSource()

    /** The transcript's classes added to the cached courses: one it adds joins with class times. */
    data class Gaining(override val rosterNos: List<String>) : ClassTableTermSource()

    /** An ended term rebuilt from [report]: its classes looked up; see TranscriptTerms.rebuiltPastTerm. */
    data class Rebuild(override val rosterNos: List<String>, val report: ScoreReport) : ClassTableTermSource() {
        override val lookupsAtOnce: Int get() = MAX_CONCURRENT_SCHOOL_REQUESTS

        // The report holds the student's grades, which have no place in a log.
        override fun toString(): String = "Rebuild(rosterNos=$rosterNos)"
    }

    /** No source answers: the term keeps its courses, the hand-added ones looked up again. */
    data object Unanswered : ClassTableTermSource() {
        override val rosterNos: List<String> = emptyList()
    }

    companion object {
        /**
         * [term]'s source, from course selection's answer, [selection]; the
         * transcript, [report]; and [cached], the term's list as it stands.
         * [servedBySelection] says course selection serves the term, which
         * is then never rebuilt.
         */
        fun of(
            term: String,
            selection: List<String>?,
            report: ScoreReport?,
            servedBySelection: Boolean,
            cached: List<Course>,
        ): ClassTableTermSource {
            if (report != null && TranscriptTerms.rebuildsFromTranscript(report, term, servedBySelection)) {
                return Rebuild(TranscriptTerms.classRows(report, term).map { it.code }.distinct(), report)
            }
            val courses = TranscriptTerms.termFrom(selection, report, term, cached) ?: return Unanswered
            return if (courses.roster != null) Selection(courses.courseNos) else Gaining(courses.courseNos)
        }
    }
}
