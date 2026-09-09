package org.ntust.app.tigerduck.data.model

/**
 * NTUST 等第積分 — 學生學業成績作業要點 附表一, the column for students
 * admitted from 105 學年度 on (A+ = 4.3). Transcripts have carried letter
 * grades since 100 學年度; the percentage bands below are the same table's
 * 百分制分數區間, for the odd numeric entry.
 *
 * Kept in lockstep with iOS `NTUSTGradePoints`. Both compute the estimate a
 * student sees before the school posts their ranking, so a table changed on
 * one side alone shows the same person two different GPAs for the same
 * transcript.
 *
 * Students admitted 100–104 had A+ = 4.0. They have long graduated; if one
 * ever turns up, key the table off the year in their student id.
 */
object NtustGradePoints {

    private val byLetter = mapOf(
        "A+" to 4.3, "A" to 4.0, "A-" to 3.7,
        "B+" to 3.3, "B" to 3.0, "B-" to 2.7,
        "C+" to 2.3, "C" to 2.0, "C-" to 1.7,
        "D" to 1.0, "E" to 0.0, "X" to 0.0,
    )

    /**
     * Grade points for a transcript grade cell, or null when the cell is not
     * a grade at all — pass/fail (通過), 成績未到, or empty.
     */
    fun pointsForGrade(raw: String): Double? {
        val grade = foldFullwidth(raw.trim()).uppercase()
        byLetter[grade]?.let { return it }
        val score = grade.toDoubleOrNull() ?: return null
        return when {
            score >= 90 -> 4.3
            score >= 85 -> 4.0
            score >= 80 -> 3.7
            score >= 77 -> 3.3
            score >= 73 -> 3.0
            score >= 70 -> 2.7
            score >= 67 -> 2.3
            score >= 63 -> 2.0
            score >= 60 -> 1.7
            score >= 50 -> 1.0
            else -> 0.0
        }
    }

    /**
     * Credit-weighted average over the courses that already carry a grade;
     * null while nothing in [courses] is gradable yet. Pass/fail, exempted,
     * withdrawn and pending rows earn no grade points and drop out — which
     * is what lets the estimate exist at all, since a term in progress is
     * mostly those.
     *
     * Every graded course counts, 不計入 credits included: the school's
     * inclusion rule is unpublished. Adjust here if the official figure ever
     * turns out to disagree.
     */
    fun gpaOf(courses: List<CourseGrade>): Double? {
        var weighted = 0.0
        var credits = 0.0
        for (course in courses) {
            if (course.status != GradeStatus.GRADED) continue
            val credit = course.credits ?: continue
            if (credit <= 0) continue
            val points = pointsForGrade(course.grade) ?: continue
            weighted += points * credit
            credits += credit
        }
        return if (credits > 0) weighted / credits else null
    }

    /**
     * Fold the full-width forms some exports carry ("Ｂ＋") onto ASCII so
     * they match the table above. U+FF01–U+FF5E sit a fixed 0xFEE0 above
     * their ASCII counterparts.
     */
    private fun foldFullwidth(text: String): String = text.map { char ->
        if (char in '！'..'～') (char.code - 0xFEE0).toChar() else char
    }.joinToString("")
}
