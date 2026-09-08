package org.ntust.app.tigerduck.data.model

/**
 * One term on the GPA trend: the school's published ranking, or — while the
 * ranking is not out yet — a GPA computed from the grades that have arrived
 * so far. Grades land course by course, so a provisional point keeps moving
 * until the ranking replaces it.
 *
 * Derived from a [ScoreReport] on demand and never written to disk, so
 * unlike the models it is built from this one carries no Gson or upgrade
 * constraints.
 */
data class GpaTrendPoint(
    val term: String,
    val semester: RankingStats,
    val cumulative: RankingStats,
    val isProvisional: Boolean,
) {
    companion object {

        private fun published(ranking: SemesterRanking) = GpaTrendPoint(
            term = ranking.term,
            semester = ranking.semester,
            cumulative = ranking.cumulative,
            isProvisional = false,
        )

        private fun provisional(
            term: String,
            semesterGpa: Double,
            cumulativeGpa: Double?,
        ) = GpaTrendPoint(
            term = term,
            // Ranks are the school's to award. An estimate has none, and
            // inventing one from the courses on hand would be a number about
            // this student's classmates that we cannot see.
            semester = RankingStats(gpa = semesterGpa),
            cumulative = RankingStats(gpa = cumulativeGpa),
            isProvisional = true,
        )

        /**
         * Chronological trend for [report]: every published ranking, plus a
         * provisional point for each term that has graded courses but no
         * ranking yet (or a ranking the school posted without a GPA).
         *
         * A term whose courses are all still pending yields no point at all
         * — there is nothing to average, and a zero would read as a real bad
         * term rather than an absent one.
         */
        fun trendFor(report: ScoreReport): List<GpaTrendPoint> {
            val coursesByTerm = report.courses.groupBy { it.term }
            // First ranking wins on a duplicate term, matching iOS. NTUST has
            // never sent one; picking the same side on both platforms keeps
            // them from disagreeing if it ever does.
            val rankings = LinkedHashMap<String, SemesterRanking>()
            for (ranking in report.rankings) {
                if (!rankings.containsKey(ranking.term)) rankings[ranking.term] = ranking
            }

            return (coursesByTerm.keys + rankings.keys).sorted().mapNotNull { term ->
                val ranking = rankings[term]
                if (ranking?.semester?.gpa != null) return@mapNotNull published(ranking)
                val semesterGpa = NtustGradePoints.gpaOf(coursesByTerm[term].orEmpty())
                    ?: return@mapNotNull null
                provisional(
                    term = term,
                    semesterGpa = semesterGpa,
                    // The cumulative estimate spans every graded course up to
                    // and including this term, so that series keeps running
                    // past the last term the school has ranked.
                    cumulativeGpa = NtustGradePoints.gpaOf(
                        report.courses.filter { it.term <= term },
                    ),
                )
            }
        }
    }
}
