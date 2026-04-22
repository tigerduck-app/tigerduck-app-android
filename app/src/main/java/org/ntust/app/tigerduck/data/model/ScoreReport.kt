package org.ntust.app.tigerduck.data.model

data class ScoreReport(
    val student: String,
    val currentTerm: String,
    val rankings: List<SemesterRanking>,
    val courses: List<CourseGrade>,
    val creditSummary: CreditSummary
) {
    companion object {
        val EMPTY = ScoreReport(
            student = "",
            currentTerm = "",
            rankings = emptyList(),
            courses = emptyList(),
            creditSummary = CreditSummary.EMPTY
        )
    }
}

data class SemesterRanking(
    val term: String,
    val semester: RankingStats,
    val cumulative: RankingStats
)

data class RankingStats(
    val classRank: Int? = null,
    val deptRank: Int? = null,
    val gpa: Double? = null
)

data class CourseGrade(
    val index: Int? = null,
    val term: String,
    val code: String,
    val name: String,
    val credits: Int? = null,
    val creditType: CreditType = CreditType.UNKNOWN,
    val grade: String = "",
    val status: GradeStatus = GradeStatus.UNKNOWN,
    val remark: String = "",
    val geDimension: String? = null,
    val distanceLearning: Boolean = false
) {
    val id: String get() = "$term-$code-${index ?: -1}"
}

enum class CreditType {
    NORMAL,
    EDUCATION_PROGRAM,
    NOT_COUNTED,
    NOT_REQUIRED,
    NOT_EARNED,
    UNKNOWN
}

enum class GradeStatus {
    GRADED,
    PENDING,
    PASSED,
    WITHDREW,
    EXEMPTED,
    UNKNOWN
}

data class CreditSummary(
    val earned: CreditBreakdown,
    val enrolled: CreditBreakdown,
    val total: CreditBreakdown
) {
    companion object {
        val EMPTY = CreditSummary(
            earned = CreditBreakdown.ZERO,
            enrolled = CreditBreakdown.ZERO,
            total = CreditBreakdown.ZERO
        )
    }
}

data class CreditBreakdown(
    val inPerson: Int,
    val distance: Int,
    val total: Int
) {
    companion object {
        val ZERO = CreditBreakdown(0, 0, 0)
    }
}
