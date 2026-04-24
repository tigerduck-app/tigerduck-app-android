package org.ntust.app.tigerduck.network

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditBreakdown
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.RankingStats
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.model.SemesterRanking

/**
 * Parses the NTUST StuScoreQueryServ DisplayAll HTML into a [ScoreReport].
 * Mirrors the iOS NTUSTScoreParser — layout selectors and regex patterns are
 * kept 1:1 so both stay swappable.
 */
object NtustScoreParser {

    // Order matters — `^(\d+)$` matches everything else so it must run last.
    private val creditPatterns: List<Pair<Regex, CreditType>> = listOf(
        Regex("""^\[\s*(\d+)\s*]$""") to CreditType.EDUCATION_PROGRAM,
        Regex("""^<\s*(\d+)\s*>$""") to CreditType.NOT_COUNTED,
        Regex("""^#\s*(\d+)\s*$""") to CreditType.NOT_REQUIRED,
        Regex("""^\(\s*(\d+)\s*\)$""") to CreditType.NOT_EARNED,
        Regex("""^(\d+)$""") to CreditType.NORMAL,
    )

    private val currentTermRegex = Regex("""期末評量時間\s*(\d{4})""")

    fun parse(html: String): ScoreReport {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return ScoreReport.EMPTY
        return ScoreReport(
            student = parseStudent(doc),
            currentTerm = parseCurrentTerm(doc),
            rankings = parseRankings(doc),
            courses = parseCourses(doc),
            creditSummary = parseCreditSummary(doc)
        )
    }

    private fun parseStudent(doc: Document): String {
        val excluded = setOf("登出", "Logout", "English")
        val links = doc.select("ul.navbar-right a.nav-link")
        for (link in links) {
            val name = cleanText(link)
            if (name.isNotEmpty() && name !in excluded) return name
        }
        return ""
    }

    private fun parseCurrentTerm(doc: Document): String {
        val alerts = doc.select("div.alert-info")
        for (alert in alerts) {
            val text = cleanText(alert)
            val match = currentTermRegex.find(text)
            if (match != null) return match.groupValues[1]
        }
        return ""
    }

    private fun parseRankings(doc: Document): List<SemesterRanking> {
        val box = findBox(doc, "排名資料") ?: return emptyList()
        val table = box.selectFirst("table") ?: return emptyList()
        val rows = rowsOf(table)
        if (rows.size <= 1) return emptyList()

        return rows.drop(1).mapNotNull { cells ->
            if (cells.size < 7) return@mapNotNull null
            SemesterRanking(
                term = cleanText(cells[0]),
                semester = RankingStats(
                    classRank = cleanText(cells[1]).toIntOrNull(),
                    deptRank = cleanText(cells[2]).toIntOrNull(),
                    gpa = cleanText(cells[3]).toDoubleOrNull()
                ),
                cumulative = RankingStats(
                    classRank = cleanText(cells[4]).toIntOrNull(),
                    deptRank = cleanText(cells[5]).toIntOrNull(),
                    gpa = cleanText(cells[6]).toDoubleOrNull()
                )
            )
        }
    }

    private fun parseCourses(doc: Document): List<CourseGrade> {
        val box = findBox(doc, "歷年學業成績列表") ?: return emptyList()
        val table = box.selectFirst("table") ?: return emptyList()
        val rows = rowsOf(table)
        if (rows.size <= 1) return emptyList()

        return rows.drop(1).mapNotNull { cells ->
            if (cells.size < 9) return@mapNotNull null
            val (credits, creditType) = parseCredits(cleanText(cells[4]))
            val grade = cleanText(cells[5])
            val remark = cleanText(cells[6])
            val geDim = cleanText(cells[7]).ifEmpty { null }
            val distanceRaw = cleanText(cells[8])
            val distanceLearning = distanceRaw.isNotEmpty() &&
                distanceRaw != "否" && distanceRaw != "N"

            CourseGrade(
                index = cleanText(cells[0]).toIntOrNull(),
                term = cleanText(cells[1]),
                code = cleanText(cells[2]),
                name = cleanText(cells[3]),
                credits = credits,
                creditType = creditType,
                grade = grade,
                status = classifyStatus(grade, remark),
                remark = remark,
                geDimension = geDim,
                distanceLearning = distanceLearning
            )
        }
    }

    private fun parseCreditSummary(doc: Document): CreditSummary {
        val info = doc.selectFirst("#DataTables_Table_0_info table") ?: return CreditSummary.EMPTY
        val rows = rowsOf(info)
        if (rows.size <= 1) return CreditSummary.EMPTY

        var earned = CreditBreakdown.ZERO
        var enrolled = CreditBreakdown.ZERO
        var total = CreditBreakdown.ZERO

        for (cells in rows.drop(1)) {
            if (cells.size < 4) continue
            val label = cleanText(cells[0])
            val breakdown = CreditBreakdown(
                inPerson = cleanText(cells[1]).toIntOrNull() ?: 0,
                distance = cleanText(cells[2]).toIntOrNull() ?: 0,
                total = cleanText(cells[3]).toIntOrNull() ?: 0
            )
            when (label) {
                "已實得學分數" -> earned = breakdown
                "修習中學分數" -> enrolled = breakdown
                "合計" -> total = breakdown
            }
        }
        return CreditSummary(earned = earned, enrolled = enrolled, total = total)
    }

    private fun cleanText(element: Element): String =
        element.text().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun parseCredits(raw: String): Pair<Int?, CreditType> {
        val trimmed = raw.trim()
        for ((regex, type) in creditPatterns) {
            val match = regex.find(trimmed) ?: continue
            val value = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            return value to type
        }
        return null to CreditType.UNKNOWN
    }

    private fun classifyStatus(grade: String, remark: String): GradeStatus {
        val g = grade.trim()
        val r = remark.trim()
        if (g.contains("二次退選") || r.contains("二次退選")) return GradeStatus.WITHDREW
        if (r.contains("抵免")) return GradeStatus.EXEMPTED
        if (g.contains("成績未到")) return GradeStatus.PENDING
        if (g == "通過" || g == "不通過") return GradeStatus.PASS_FAIL_GRADED
        if (g.isEmpty()) return GradeStatus.UNKNOWN
        return GradeStatus.GRADED
    }

    private fun findBox(doc: Document, title: String): Element? {
        val boxes = doc.select("div.box")
        for (box in boxes) {
            val header = box.selectFirst(".box-header h2") ?: continue
            if (cleanText(header).contains(title)) return box
        }
        return null
    }

    private fun rowsOf(table: Element): List<List<Element>> =
        table.select("tr").mapNotNull { tr ->
            val cells = tr.select("td, th")
            if (cells.isEmpty()) null else cells.toList()
        }
}
