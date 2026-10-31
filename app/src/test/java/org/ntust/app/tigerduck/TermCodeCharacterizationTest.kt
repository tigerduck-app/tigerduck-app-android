package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GpaTrendPoint
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.ui.screen.calendar.calendarTermLabel
import org.ntust.app.tigerduck.ui.screen.classtable.semesterDisplayLabel
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.semesterLabel
import org.ntust.app.tigerduck.ui.screen.score.ScoreCourseRoster
import org.ntust.app.tigerduck.ui.screen.score.displayTermShort
import org.ntust.app.tigerduck.ui.screen.score.formatCurrentTerm
import org.ntust.app.tigerduck.ui.screen.score.termYearAndPart
import java.time.Instant
import java.time.LocalDate

/**
 * What each place that reads a term code does with the same odd inputs, as the code stood before
 * [org.ntust.app.tigerduck.shared.time.AcademicTerm] took the parsing, ordering and labeling over.
 * The expectations are the output of that code, kept as it was; the refactor commits leave them
 * alone, except where a commit says that an input no site can receive changed.
 */
class TermCodeCharacterizationTest {

    private val inputs = listOf("1141", "1142", "114H", "113H", "1131", "114h", " 1141 ", "11402", "", "abc")

    private fun table(f: (String) -> Any?): String =
        inputs.joinToString("\n") { "'$it' -> ${f(it)}" }

    // --- Parse ---

    @Test
    fun `SemesterCodes previous`() {
        assertEquals(
            """
            '1141' -> 1132
            '1142' -> 1141
            '114H' -> 114H
            '113H' -> 113H
            '1131' -> 1122
            '114h' -> 114H
            ' 1141 ' -> 1132
            '11402' -> 1141
            '' -> 
            'abc' -> abc
            """.trimIndent(),
            table { SemesterCodes.previous(it) },
        )
    }

    @Test
    fun `SemesterCatalog academicYear`() {
        assertEquals(
            """
            '1141' -> 114
            '1142' -> 114
            '114H' -> 114
            '113H' -> 113
            '1131' -> 113
            '114h' -> 114
            ' 1141 ' -> 114
            '11402' -> 114
            '' -> null
            'abc' -> null
            """.trimIndent(),
            table { SemesterCatalog.academicYear(it) },
        )
    }

    @Test
    fun `ScoreCourseRoster semesterCode`() {
        assertEquals(
            """
            '1141' -> 1141
            '1142' -> 1142
            '114H' -> 114H
            '113H' -> 113H
            '1131' -> 1131
            '114h' -> 114H
            ' 1141 ' -> 1141
            '11402' -> 1142
            '' -> null
            'abc' -> null
            """.trimIndent(),
            table { ScoreCourseRoster.semesterCode(it) },
        )
    }

    @Test
    fun `MoodleCourseIds semesterPrefix of an idnumber that starts with the input`() {
        assertEquals(
            """
            '1141' -> 1141
            '1142' -> 1142
            '114H' -> 114H
            '113H' -> 113H
            '1131' -> 1131
            '114h' -> 114H
            ' 1141 ' -> null
            '11402' -> null
            '' -> null
            'abc' -> null
            """.trimIndent(),
            table { MoodleCourseIds.semesterPrefix(it + "GD3115301") },
        )
    }

    // --- Order ---

    private fun transcript(vararg terms: String) = ScoreReport(
        student = "",
        currentTerm = "",
        rankings = emptyList(),
        courses = terms.mapIndexed { i, term -> CourseGrade(term = term, code = "C$i", name = "C$i", status = GradeStatus.GRADED) },
        creditSummary = CreditSummary.EMPTY,
    )

    /** For each input, the inputs the transcript term reads as later, by the transcript's rebuild rule. */
    @Test
    fun `TranscriptTerms orders terms by year, then 1 2 H`() {
        assertEquals(
            """
            '1141' -> [1142, 114H, 114h, 11402]
            '1142' -> [114H, 114h]
            '114H' -> []
            '113H' -> [1141, 1142, 114H, 114h,  1141 , 11402]
            '1131' -> [1141, 1142, 114H, 113H, 114h,  1141 , 11402]
            '114h' -> []
            ' 1141 ' -> [1142, 114H, 114h, 11402]
            '11402' -> [114H, 114h]
            '' -> []
            'abc' -> []
            """.trimIndent(),
            table { term ->
                inputs.filter { other ->
                    other.isNotBlank() &&
                        TranscriptTerms.rebuildsFromTranscript(transcript(term, other), term, servedBySelection = false)
                }.toString()
            },
        )
    }

    @Test
    fun `GpaTrendPoint trendFor lists terms by plain string sort`() {
        val graded = listOf("1141", "1142", "114H", "113H", "1131", "11402", "11401", "abc")
        val report = ScoreReport(
            student = "",
            currentTerm = "",
            rankings = emptyList(),
            courses = graded.map { CourseGrade(term = it, code = it, name = it, credits = 3f, creditType = CreditType.NORMAL, grade = "A", status = GradeStatus.GRADED) },
            creditSummary = CreditSummary.EMPTY,
        )
        assertEquals(
            "[1131, 113H, 11401, 11402, 1141, 1142, 114H, abc]",
            GpaTrendPoint.trendFor(report).map { it.term }.toString(),
        )
    }

    @Test
    fun `ImportedPerson semesters newest first by plain string sort`() {
        val person = ImportedPerson(
            id = "p", studentId = null, nickname = "n", importedAt = Instant.EPOCH, sharedAt = null,
            terms = listOf("1141", "1142", "114H", "113H", "1131", "114A", "1143").map {
                ImportedPerson.Term(it, emptyList(), emptyList())
            },
        )
        assertEquals("[114H, 114A, 1143, 1142, 1141, 113H, 1131]", person.semesters.toString())
    }

    // --- Labels ---

    @Test
    fun `class table label`() {
        assertEquals("""
            '1141' -> 114-1
            '1142' -> 114-2
            '114H' -> 114-H
            '113H' -> 113-H
            '1131' -> 113-1
            '114h' -> 114-h
            ' 1141 ' ->  1141- 
            '11402' -> 1140-2
            '' -> 
            'abc' -> ab-c
            """.trimIndent(), table { semesterDisplayLabel(it) })
    }

    @Test
    fun `class table import label`() {
        assertEquals("""
            '1141' -> 114-1
            '1142' -> 114-2
            '114H' -> 114-H
            '113H' -> 113-H
            '1131' -> 113-1
            '114h' -> 114-h
            ' 1141 ' ->  1141- 
            '11402' -> 1140-2
            '' -> -
            'abc' -> ab-c
            """.trimIndent(), table { semesterLabel(it) })
    }

    @Test
    fun `calendar event label`() {
        assertEquals("""
            '1141' -> 114-1
            '1142' -> 114-2
            '114H' -> 114-H
            '113H' -> 113-H
            '1131' -> 113-1
            '114h' -> 114-h
            ' 1141 ' ->  1141 
            '11402' -> 11402
            '' -> 
            'abc' -> abc
            """.trimIndent(), table { calendarTermLabel(it) })
    }

    @Test
    fun `score screen label parts`() {
        assertEquals("""
            '1141' -> (114, 1)
            '1142' -> (114, 2)
            '114H' -> (114, H)
            '113H' -> (113, H)
            '1131' -> (113, 1)
            '114h' -> (114, h)
            ' 1141 ' -> null
            '11402' -> null
            '' -> null
            'abc' -> null
            """.trimIndent(), table { termYearAndPart(it) })
    }

    @Test
    fun `score screen current term and trend chart short label`() {
        assertEquals("""
            '1141' -> 114學年度第1學期 | 114-1
            '1142' -> 114學年度第2學期 | 114-2
            '114H' -> 114學年度第H學期 | 114-H
            '113H' -> 113學年度第H學期 | 113-H
            '1131' -> 113學年度第1學期 | 113-1
            '114h' -> 114學年度第h學期 | 114-h
            ' 1141 ' ->  1141  |  1141 
            '11402' -> 11402 | 11402
            '' ->  | 
            'abc' -> abc | abc
            """.trimIndent(), table { "${formatCurrentTerm("%s學年度第%s學期", it)} | ${displayTermShort(it)}" })
    }

    // --- Current term ---

    @Test
    fun `the term of a date is the latest begun term, or the month guess with no calendar`() {
        val calendar = AcademicCalendar(
            revision = 1,
            terms = listOf(
                SemesterTerm("1141", LocalDate.of(2025, 9, 1), LocalDate.of(2026, 1, 20)),
                SemesterTerm("1142", LocalDate.of(2026, 2, 16), LocalDate.of(2026, 6, 20)),
            ),
            holidays = emptyList(),
        )
        val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 9, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 7, 1))
        assertEquals(
            "1141, 1141, 1141, 1142",
            dates.joinToString { org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler.termOn(calendar, it) },
        )
        assertEquals(
            SemesterCodes.heuristic(),
            org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler.termOn(AcademicCalendar.EMPTY, dates[0]),
        )
    }
}
