package org.ntust.app.tigerduck.ui.screen.score

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GpaTrendPoint
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.mail.MainDispatcherRule

/**
 * The Score page puts terms in order the way iOS does, through `AcademicTerm`: a padded older term
 * such as `99 1` is older than `1141`, which plain string order gets backwards, and a code that is
 * not a term keeps a stable place at the end of the newest-first list.
 */
class ScoreTermOrderTest {
    @get:Rule val main = MainDispatcherRule()

    private fun graded(term: String, grade: String = "A") = CourseGrade(
        term = term, code = "C-$term", name = "Course $term", credits = 3f,
        creditType = CreditType.NORMAL, grade = grade, status = GradeStatus.GRADED,
    )

    private fun reportOf(vararg terms: String) = ScoreReport.EMPTY.copy(student = "B00015999", courses = terms.map { graded(it) })

    private fun viewModel(report: ScoreReport) = ScoreViewModel(
        authState = MutableStateFlow(true),
        storedStudentId = { "B00015999" },
        storedPassword = { "password" },
        cachedSnapshot = { null },
        fetchReport = { _, _, _ -> report },
        isOnline = { true },
        isDemo = { false },
        strings = { "string $it" },
        rosterCourses = { emptyList() },
    ).also { it.load() }

    @Test
    fun `terms are listed newest first, a padded old year last among terms and a non-term at the end`() {
        val vm = viewModel(reportOf("99 1", "1141", "abc", "1131", "114H", "1142", "11401"))

        // 11401 and 1141 are one term spelled twice and keep their string order, newest first.
        assertEquals(
            listOf("114H", "1142", "1141", "11401", "1131", "99 1", "abc"),
            vm.groupedCourses.map { it.first },
        )
    }

    @Test
    fun `the newest term is the one left open, not the largest string`() {
        val vm = viewModel(reportOf("99 1", "1141", "1131", "abc"))

        assertEquals(setOf("99 1", "1131", "abc"), vm.collapsedTerms.value)
    }

    @Test
    fun `the trend runs oldest first and a term's cumulative GPA counts every older term`() {
        val report = ScoreReport.EMPTY.copy(
            courses = listOf(graded("99 1", "A+"), graded("1141", "C"), graded("abc", "A+")),
        )

        val trend = GpaTrendPoint.trendFor(report)

        // A code that is not a term comes before every term, as iOS has it.
        assertEquals(listOf("abc", "99 1", "1141"), trend.map { it.term })
        // 1141's cumulative GPA covers every term before it, as iOS: abc and 99 1 (A+ 4.3 each) and itself
        // (C 2.0), where plain string order left 99 1 and abc out of it.
        assertEquals((4.3 + 4.3 + 2.0) / 3, trend.last().cumulative.gpa!!, 0.001)
        assertEquals(4.3, trend[1].cumulative.gpa!!, 0.001)
    }
}
