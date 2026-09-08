package org.ntust.app.tigerduck.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provisional GPA is a number the school has not published, shown next
 * to numbers it has. These pin the arithmetic and the promotion rule, so an
 * estimate can never quietly present itself as an official figure — and so
 * the table stays in step with the iOS `GPATrendTests` that mirror it.
 */
private fun course(
    term: String,
    code: String,
    credits: Int?,
    grade: String,
    status: GradeStatus = GradeStatus.GRADED,
) = CourseGrade(
    term = term,
    code = code,
    name = code,
    credits = credits,
    creditType = CreditType.NORMAL,
    grade = grade,
    status = status,
)

class NtustGradePointsTest {

    @Test
    fun `letters follow the official table`() {
        assertEquals(4.3, NtustGradePoints.pointsForGrade("A+"))
        assertEquals(2.7, NtustGradePoints.pointsForGrade(" b- "))
        assertEquals(2.3, NtustGradePoints.pointsForGrade("Ｃ＋"))
        assertEquals(0.0, NtustGradePoints.pointsForGrade("X"))
    }

    @Test
    fun `numbers fall back to the percent bands`() {
        assertEquals(4.3, NtustGradePoints.pointsForGrade("90"))
        assertEquals(3.7, NtustGradePoints.pointsForGrade("84"))
        assertEquals(1.0, NtustGradePoints.pointsForGrade("59"))
        assertEquals(0.0, NtustGradePoints.pointsForGrade("12"))
    }

    @Test
    fun `non-grades carry no points`() {
        assertNull(NtustGradePoints.pointsForGrade("通過"))
        assertNull(NtustGradePoints.pointsForGrade("成績未到"))
        assertNull(NtustGradePoints.pointsForGrade(""))
    }

    @Test
    fun `gpa is credit-weighted over graded courses only`() {
        val courses = listOf(
            course("1142", "A", credits = 3, grade = "A+"),          // 4.3 × 3
            course("1142", "B", credits = 1, grade = "C"),           // 2.0 × 1
            course("1142", "C", credits = 2, grade = "成績未到", status = GradeStatus.PENDING),
            course("1142", "D", credits = 1, grade = "通過", status = GradeStatus.PASS_FAIL_GRADED),
            course("1142", "E", credits = 0, grade = "A"),
            course("1142", "F", credits = null, grade = "A"),
        )
        assertEquals((4.3 * 3 + 2.0) / 4, NtustGradePoints.gpaOf(courses)!!, 1e-9)
    }

    @Test
    fun `a term with nothing graded yet has no gpa at all`() {
        val pending = listOf(
            course("1142", "C", credits = 2, grade = "成績未到", status = GradeStatus.PENDING),
        )
        assertNull(NtustGradePoints.gpaOf(pending))
    }
}

class GpaTrendPointTest {

    private val report = ScoreReport(
        student = "B11315000",
        currentTerm = "1142",
        rankings = listOf(
            SemesterRanking(
                term = "1141",
                semester = RankingStats(classRank = 3, deptRank = 10, gpa = 3.9),
                cumulative = RankingStats(classRank = 4, deptRank = 12, gpa = 3.85),
            ),
        ),
        courses = listOf(
            course("1141", "A", credits = 3, grade = "A"),      // published row wins; math ignored
            course("1142", "B", credits = 2, grade = "A+"),     // 4.3 × 2
            course("1142", "C", credits = 2, grade = "B"),      // 3.0 × 2
            course("1142", "D", credits = 3, grade = "成績未到", status = GradeStatus.PENDING),
            // Nothing graded, so this term contributes no point at all.
            course("1151", "E", credits = 3, grade = "成績未到", status = GradeStatus.PENDING),
        ),
        creditSummary = CreditSummary.EMPTY,
    )

    @Test
    fun `published ranking wins and an unranked term gets a provisional point`() {
        val trend = GpaTrendPoint.trendFor(report)
        assertEquals(listOf("1141", "1142"), trend.map { it.term })
        assertFalse(trend[0].isProvisional)
        assertEquals(3.9, trend[0].semester.gpa)
        assertTrue(trend[1].isProvisional)
        assertEquals(3.65, trend[1].semester.gpa!!, 1e-9)
    }

    @Test
    fun `an estimate carries no ranks`() {
        val provisional = GpaTrendPoint.trendFor(report).first { it.isProvisional }
        assertNull(provisional.semester.classRank)
        assertNull(provisional.semester.deptRank)
    }

    @Test
    fun `the cumulative estimate spans every graded course so far`() {
        val trend = GpaTrendPoint.trendFor(report)
        // (4.0×3 + 4.3×2 + 3.0×2) / 7 — the published term's courses count
        // here even though its own point came from the school's ranking.
        assertEquals(
            (4.0 * 3 + 4.3 * 2 + 3.0 * 2) / 7,
            trend[1].cumulative.gpa!!,
            1e-9,
        )
    }

    @Test
    fun `a ranking posted without a gpa still yields an estimate`() {
        val unpublishedGpa = report.copy(
            rankings = listOf(
                SemesterRanking(
                    term = "1142",
                    semester = RankingStats(classRank = null, deptRank = null, gpa = null),
                    cumulative = RankingStats(),
                ),
            ),
        )
        val point = GpaTrendPoint.trendFor(unpublishedGpa).first { it.term == "1142" }
        assertTrue(point.isProvisional)
        assertEquals(3.65, point.semester.gpa!!, 1e-9)
    }

    @Test
    fun `an empty report has no trend`() {
        assertEquals(emptyList<GpaTrendPoint>(), GpaTrendPoint.trendFor(ScoreReport.EMPTY))
    }
}
