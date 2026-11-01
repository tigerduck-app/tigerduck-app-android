package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.shared.Course

/**
 * Which source a term's fetch reads (iOS `AppServiceBridge.fetchCourses`, tigerduck-app#890):
 * course selection's answer is the term it serves; a past term the transcript holds whole is
 * rebuilt from it; any other term only gains the transcript's classes. Moodle adds no course to
 * any term: it lists course sites and keeps a dropped class. Made-up course numbers.
 */
class ClassTableTermSourceTest {

    private fun transcript(vararg rows: Pair<String, String>) = ScoreReport(
        student = "", currentTerm = "", rankings = emptyList(),
        courses = rows.map { (term, code) -> CourseGrade(term = term, code = code, name = code, status = GradeStatus.GRADED) },
        creditSummary = CreditSummary.EMPTY,
    )

    private val cached = listOf(
        Course(courseNo = "XC0000003", courseName = "Cached"),
        // Added by hand, or merged from the server: the fetch keeps these itself.
        Course(courseNo = "XF0000006", courseName = "Manual", isManual = true),
    )

    private fun source(term: String, selection: List<String>?, report: ScoreReport?, servedBySelection: Boolean) =
        ClassTableTermSource.of(term, selection, report, servedBySelection, cached)

    @Test
    fun `course selection's answer is the term it serves, whatever the transcript lists`() {
        val report = transcript("1151" to "XA0000001", "1152" to "XZ0000099")
        val source = source("1151", listOf("XB0000002", "XA0000001", "XB0000002"), report, servedBySelection = true)

        assertEquals(ClassTableTermSource.Selection(listOf("XB0000002", "XA0000001")), source)
        assertNull(source.lookupsAtOnce)
    }

    @Test
    fun `with course selection empty or unreachable, its term only gains the transcript's classes`() {
        val report = transcript("1151" to "XA0000001")
        for (selection in listOf(null, emptyList<String>())) {
            assertEquals(
                ClassTableTermSource.Gaining(listOf("XA0000001", "XC0000003")),
                source("1151", selection, report, servedBySelection = true),
            )
        }
    }

    @Test
    fun `a term the course selection system serves is never rebuilt, even with a later term listed`() {
        val report = transcript("1151" to "XA0000001", "1152" to "XZ0000099")
        assertEquals(
            ClassTableTermSource.Gaining(listOf("XA0000001", "XC0000003")),
            source("1151", emptyList(), report, servedBySelection = true),
        )
    }

    @Test
    fun `a past term the transcript holds whole is rebuilt from its classes, four lookups at a time`() {
        val report = transcript("1141" to "XA0000001", "1141" to "XG0000007", "1141" to "XA0000001", "1142" to "XZ0000099")
        val source = source("1141", emptyList(), report, servedBySelection = false)

        assertEquals(ClassTableTermSource.Rebuild(listOf("XA0000001", "XG0000007"), report), source)
        assertEquals(4, source.lookupsAtOnce)
    }

    @Test
    fun `the newest term the transcript lists only gains`() {
        val report = transcript("1141" to "XA0000001", "1142" to "XZ0000099")
        assertEquals(
            ClassTableTermSource.Gaining(listOf("XZ0000099", "XC0000003")),
            source("1142", emptyList(), report, servedBySelection = false),
        )
    }

    /** Before #890 Moodle's sites were the roster of every term course selection did not answer. */
    @Test
    fun `with neither course selection nor the transcript, the term keeps its courses and Moodle adds none`() {
        for (report in listOf(null, transcript("1142" to "XZ0000099"))) {
            val source = source("1141", emptyList(), report, servedBySelection = false)
            assertEquals(ClassTableTermSource.Unanswered, source)
            assertEquals(emptyList<String>(), source.rosterNos)
        }
    }

    // --- Home and the background sync: the cached transcript only, never a rebuild ---

    @Test
    fun `Home and the background sync take course selection, else the cached transcript's classes, else keep the term`() {
        val report = transcript("1151" to "XA0000001", "1152" to "XZ0000099")

        val selection = TranscriptTerms.termFrom(listOf("XB0000002"), report, "1151", cached)
        assertEquals(TranscriptTerms.TermCourses(listOf("XB0000002"), roster = listOf("XB0000002")), selection)

        // A past term too only gains here: the class table's own fetch rebuilds it.
        val gaining = TranscriptTerms.termFrom(null, report, "1151", cached)
        assertEquals(TranscriptTerms.TermCourses(listOf("XA0000001", "XC0000003"), roster = null), gaining)

        assertNull(TranscriptTerms.termFrom(emptyList(), null, "1151", cached))
        assertNull(TranscriptTerms.termFrom(emptyList(), report, "1142", cached))
    }
}
