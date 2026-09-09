package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus

/**
 * Unit tests for [NtustScoreParser].
 *
 * Fixtures are minimal valid HTML strings derived from the actual selectors
 * used by the parser:
 *   - Student name: ul.navbar-right a.nav-link (first non-logout/English entry)
 *   - Current term: div.alert-info containing 期末評量時間 YYYY
 *   - Rankings: div.box > .box-header h2 containing 排名資料
 *   - Courses: div.box > .box-header h2 containing 歷年學業成績列表
 *   - Credit summary: #DataTables_Table_0_info table
 */
class NtustScoreParserTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Wraps content in a minimal HTML page with a Bootstrap-style navbar. */
    private fun page(
        navLinks: String = "",
        alertInfo: String = "",
        rankingBox: String = "",
        courseBox: String = "",
        creditInfo: String = ""
    ): String = """
        <!DOCTYPE html>
        <html>
        <head><title>NTUST Score</title></head>
        <body>
          <nav>
            <ul class="navbar-right">
              $navLinks
            </ul>
          </nav>
          $alertInfo
          $rankingBox
          $courseBox
          <div id="DataTables_Table_0_info">
            $creditInfo
          </div>
        </body>
        </html>
    """.trimIndent()

    /** One course row: 9 cells matching parseCourses column order. */
    private fun courseRow(
        index: String,
        term: String,
        code: String,
        name: String,
        credits: String,
        grade: String,
        remark: String,
        geDim: String = "",
        distance: String = "否"
    ): String = """
        <tr>
          <td>$index</td><td>$term</td><td>$code</td><td>$name</td>
          <td>$credits</td><td>$grade</td><td>$remark</td>
          <td>$geDim</td><td>$distance</td>
        </tr>
    """.trimIndent()

    /** Wraps rows in a div.box for 歷年學業成績列表. */
    private fun courseBox(vararg rows: String): String {
        val rowsHtml = rows.joinToString("\n")
        return """
            <div class="box">
              <div class="box-header"><h2>歷年學業成績列表</h2></div>
              <table>
                <tr><th>序</th><th>學期</th><th>科號</th><th>科目</th><th>學分</th><th>成績</th><th>備註</th><th>GE</th><th>遠距</th></tr>
                $rowsHtml
              </table>
            </div>
        """.trimIndent()
    }

    /** Credit summary table inside #DataTables_Table_0_info. */
    private fun creditTable(
        earnedInPerson: Int, earnedDistance: Int, earnedTotal: Int,
        enrolledInPerson: Int, enrolledDistance: Int, enrolledTotal: Int,
        totalInPerson: Int, totalDistance: Int, totalTotal: Int
    ): String = """
        <table>
          <tr><th>類別</th><th>實地</th><th>遠距</th><th>合計</th></tr>
          <tr><td>已實得學分數</td><td>$earnedInPerson</td><td>$earnedDistance</td><td>$earnedTotal</td></tr>
          <tr><td>修習中學分數</td><td>$enrolledInPerson</td><td>$enrolledDistance</td><td>$enrolledTotal</td></tr>
          <tr><td>合計</td><td>$totalInPerson</td><td>$totalDistance</td><td>$totalTotal</td></tr>
        </table>
    """.trimIndent()

    // -------------------------------------------------------------------------
    // End-to-end parse: student info + one grade row + credit summary
    // -------------------------------------------------------------------------

    @Test
    fun `parse extracts student name from navbar-right nav-link`() {
        val html = page(
            navLinks = """
                <li><a class="nav-link" href="/logout">登出</a></li>
                <li><a class="nav-link" href="/en">English</a></li>
                <li><a class="nav-link" href="/profile">王小明</a></li>
            """,
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", "")),
            creditInfo = creditTable(30, 0, 30, 3, 0, 3, 33, 0, 33)
        )
        val report = NtustScoreParser.parse(html)
        assertEquals("王小明", report.student)
    }

    @Test
    fun `parse extracts currentTerm from alert-info containing 期末評量時間`() {
        val html = page(
            alertInfo = """<div class="alert-info">期末評量時間 1130 開始</div>""",
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", ""))
        )
        val report = NtustScoreParser.parse(html)
        assertEquals("1130", report.currentTerm)
    }

    @Test
    fun `parse returns empty currentTerm when no alert-info present`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", ""))
        )
        val report = NtustScoreParser.parse(html)
        assertEquals("", report.currentTerm)
    }

    @Test
    fun `parse extracts normal grade row with NORMAL credit type and GRADED status`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", ""))
        )
        val report = NtustScoreParser.parse(html)
        assertEquals(1, report.courses.size)
        val course = report.courses[0]
        assertEquals("11201", course.term)
        assertEquals("CS101", course.code)
        assertEquals("程式設計", course.name)
        assertEquals(3, course.credits)
        assertEquals(CreditType.NORMAL, course.creditType)
        assertEquals("85", course.grade)
        assertEquals(GradeStatus.GRADED, course.status)
    }

    @Test
    fun `parse extracts credit summary from DataTables_Table_0_info table`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", "")),
            creditInfo = creditTable(28, 2, 30, 3, 0, 3, 31, 2, 33)
        )
        val report = NtustScoreParser.parse(html)
        val summary = report.creditSummary
        assertEquals(28, summary.earned.inPerson)
        assertEquals(2, summary.earned.distance)
        assertEquals(30, summary.earned.total)
        assertEquals(3, summary.enrolled.inPerson)
        assertEquals(0, summary.enrolled.distance)
        assertEquals(3, summary.enrolled.total)
        assertEquals(31, summary.total.inPerson)
        assertEquals(2, summary.total.distance)
        assertEquals(33, summary.total.total)
    }

    // -------------------------------------------------------------------------
    // Credit-type classification via parse()
    // -------------------------------------------------------------------------

    @Test
    fun `credit type bracket 3 maps to EDUCATION_PROGRAM`() {
        // [N] = square-bracket form → EDUCATION_PROGRAM
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "PE001", "體育", "[3]", "90", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(CreditType.EDUCATION_PROGRAM, course.creditType)
        assertEquals(3, course.credits)
    }

    @Test
    fun `credit type less-than-3-greater-than maps to NOT_COUNTED`() {
        // <N> in the fixture must be HTML-encoded as &lt;N&gt; so Jsoup delivers
        // the literal angle-bracket string to cleanText(); the parser then
        // matches the regex ^<\s*(\d+)\s*>$ against that literal text.
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS200", "選修", "&lt;3&gt;", "75", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(CreditType.NOT_COUNTED, course.creditType)
        assertEquals(3, course.credits)
    }

    @Test
    fun `credit type #3 maps to NOT_REQUIRED`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "GE001", "通識", "#3", "80", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(CreditType.NOT_REQUIRED, course.creditType)
        assertEquals(3, course.credits)
    }

    @Test
    fun `credit type (3) maps to NOT_EARNED`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS300", "實習", "(3)", "70", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(CreditType.NOT_EARNED, course.creditType)
        assertEquals(3, course.credits)
    }

    @Test
    fun `credit type plain 3 maps to NORMAL`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程設", "3", "85", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(CreditType.NORMAL, course.creditType)
        assertEquals(3, course.credits)
    }

    @Test
    fun `unrecognised credit format maps to UNKNOWN with null credits`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS999", "未知", "??", "85", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(CreditType.UNKNOWN, course.creditType)
        assertNull(course.credits)
    }

    // -------------------------------------------------------------------------
    // Grade-status classification via parse()
    // -------------------------------------------------------------------------

    @Test
    fun `grade status 二次退選 in grade field maps to WITHDREW`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "課程A", "3", "二次退選", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.WITHDREW, course.status)
    }

    @Test
    fun `grade status 二次退選 in remark field maps to WITHDREW`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "課程B", "3", "", "二次退選"))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.WITHDREW, course.status)
    }

    @Test
    fun `grade status remark containing 抵免 maps to EXEMPTED`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "課程C", "3", "", "抵免通過"))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.EXEMPTED, course.status)
    }

    @Test
    fun `grade status 成績未到 in grade field maps to PENDING`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11301", "CS102", "課程D", "3", "成績未到", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.PENDING, course.status)
    }

    @Test
    fun `grade status 通過 maps to PASS_FAIL_GRADED`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "PE002", "體育II", "0", "通過", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.PASS_FAIL_GRADED, course.status)
    }

    @Test
    fun `grade status 不通過 maps to PASS_FAIL_GRADED`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "PE003", "體育III", "0", "不通過", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.PASS_FAIL_GRADED, course.status)
    }

    @Test
    fun `grade status numeric grade maps to GRADED`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS103", "課程E", "3", "92", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.GRADED, course.status)
    }

    @Test
    fun `grade status empty grade with no special remark maps to UNKNOWN`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS104", "課程F", "3", "", ""))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(GradeStatus.UNKNOWN, course.status)
    }

    // -------------------------------------------------------------------------
    // Malformed / empty HTML — must not throw
    // -------------------------------------------------------------------------

    @Test
    fun `parse returns ScoreReport EMPTY for empty string input`() {
        // Jsoup.parse("") succeeds and returns an empty document; parseCourses
        // finds no boxes so courses is empty. parse() never throws.
        val report = NtustScoreParser.parse("")
        assertNotNull(report)
        assertTrue(report.courses.isEmpty())
        assertEquals("", report.student)
        assertEquals("", report.currentTerm)
    }

    @Test
    fun `parse returns empty courses for html with no course box`() {
        val html = "<html><body><p>Nothing here</p></body></html>"
        val report = NtustScoreParser.parse(html)
        assertNotNull(report)
        assertTrue(report.courses.isEmpty())
    }

    @Test
    fun `parse returns empty rankings for html with no ranking box`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", ""))
        )
        val report = NtustScoreParser.parse(html)
        assertTrue(report.rankings.isEmpty())
    }

    @Test
    fun `parse handles course row with fewer than 9 cells without throwing`() {
        // Rows with <9 cells are silently skipped.
        val shortRow = "<tr><td>1</td><td>11201</td><td>CS999</td></tr>"
        val html = """
            <div class="box">
              <div class="box-header"><h2>歷年學業成績列表</h2></div>
              <table>
                <tr><th>序</th></tr>
                $shortRow
              </table>
            </div>
        """.trimIndent()
        val report = NtustScoreParser.parse(html)
        assertNotNull(report)
        assertTrue(report.courses.isEmpty())
    }

    @Test
    fun `parse returns EMPTY credit summary when DataTables_Table_0_info has no table`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "程式設計", "3", "85", "")),
            creditInfo = ""
        )
        val report = NtustScoreParser.parse(html)
        assertEquals(0, report.creditSummary.earned.total)
        assertEquals(0, report.creditSummary.enrolled.total)
        assertEquals(0, report.creditSummary.total.total)
    }

    // -------------------------------------------------------------------------
    // Miscellaneous
    // -------------------------------------------------------------------------

    @Test
    fun `parse skips nav-link entries for 登出 and English when finding student name`() {
        val html = page(
            navLinks = """
                <li><a class="nav-link" href="/logout">登出</a></li>
                <li><a class="nav-link" href="/en">English</a></li>
                <li><a class="nav-link" href="/profile">陳大文</a></li>
            """,
            courseBox = courseBox(courseRow("1", "11201", "CS101", "課程", "3", "80", ""))
        )
        val report = NtustScoreParser.parse(html)
        assertEquals("陳大文", report.student)
    }

    @Test
    fun `parse returns empty student string when no valid nav-link found`() {
        val html = page(
            navLinks = """
                <li><a class="nav-link" href="/logout">登出</a></li>
            """,
            courseBox = courseBox(courseRow("1", "11201", "CS101", "課程", "3", "80", ""))
        )
        val report = NtustScoreParser.parse(html)
        assertEquals("", report.student)
    }

    @Test
    fun `parse distanceLearning is true for non-N non-否 distance value`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "線上課程", "3", "85", "", "", "是"))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertTrue(course.distanceLearning)
    }

    @Test
    fun `parse distanceLearning is false for 否`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "實體課程", "3", "85", "", "", "否"))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(false, course.distanceLearning)
    }

    @Test
    fun `parse distanceLearning is false for N`() {
        val html = page(
            courseBox = courseBox(courseRow("1", "11201", "CS101", "實體課程", "3", "85", "", "", "N"))
        )
        val course = NtustScoreParser.parse(html).courses[0]
        assertEquals(false, course.distanceLearning)
    }
}
