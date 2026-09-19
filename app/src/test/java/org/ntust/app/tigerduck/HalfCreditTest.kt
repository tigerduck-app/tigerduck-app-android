package org.ntust.app.tigerduck

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.NtustGradePoints
import org.ntust.app.tigerduck.network.NtustScoreParser
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.formatCredits
import java.util.Locale

/**
 * NTUST issues 0.5-credit courses. Credits used to be an `Int` end to end, so
 * every one of them parsed to zero: absent from the class table's total, blank
 * on the transcript row, and weightless in the GPA.
 */
class HalfCreditTest {

    private fun transcript(credits: String): String = """
        <html><body>
        <div class="box">
          <div class="box-header"><h2>歷年學業成績列表</h2></div>
          <table>
            <tr><th>#</th><th>學期</th><th>課號</th><th>課名</th><th>學分</th>
                <th>成績</th><th>備註</th><th>向度</th><th>遠距</th></tr>
            <tr><td>1</td><td>1142</td><td>GE3001301</td><td>藝術與人生</td>
                <td>$credits</td><td>A</td><td></td><td>C</td><td>否</td></tr>
          </table>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `a half credit survives the transcript parser`() {
        val course = NtustScoreParser.parse(transcript("0.5")).courses.single()
        assertEquals(0.5f, course.credits)
        assertEquals(CreditType.NORMAL, course.creditType)
    }

    /**
     * The bracketed credit types share the pattern list, so a half credit has
     * to survive those too rather than falling through to UNKNOWN with no
     * credit at all.
     */
    @Test
    fun `a bracketed half credit keeps both its value and its type`() {
        val course = NtustScoreParser.parse(transcript("[0.5]")).courses.single()
        assertEquals(0.5f, course.credits)
        assertEquals(CreditType.EDUCATION_PROGRAM, course.creditType)
    }

    @Test
    fun `whole credits still parse`() {
        assertEquals(3f, NtustScoreParser.parse(transcript("3")).courses.single().credits)
    }

    @Test
    fun `half credits carry their weight in the gpa`() {
        fun graded(code: String, credits: Float, grade: String) = CourseGrade(
            term = "1142", code = code, name = code, credits = credits,
            creditType = CreditType.NORMAL, grade = grade, status = GradeStatus.GRADED,
        )
        // A+ (4.3) over 0.5 credits and C (2.0) over 1.5 → weighted 2.575.
        val gpa = NtustGradePoints.gpaOf(
            listOf(graded("A", 0.5f, "A+"), graded("B", 1.5f, "C"))
        )
        assertEquals((4.3 * 0.5 + 2.0 * 1.5) / 2.0, gpa!!, 1e-9)
    }

    /** A whole number carries no fraction; a half does. */
    @Test
    fun `credits print without a trailing point zero`() {
        assertEquals("3", 3f.formatCredits(Locale.US))
        assertEquals("0.5", 0.5f.formatCredits(Locale.US))
        assertEquals("1.5", 1.5f.formatCredits(Locale.US))
        // Locale-aware: a comma-decimal locale writes the half its own way.
        assertEquals("0,5", 0.5f.formatCredits(Locale.GERMANY))
        assertEquals("3", 3f.formatCredits(Locale.GERMANY))
    }

    /**
     * A cache written while credits were an `Int` must still read back —
     * JSON has one number type, so no DataMigration step is needed for the
     * type change. This is the check that would catch it if that stopped
     * being true.
     */
    @Test
    fun `a pre-half-credit cache row still deserializes`() {
        val legacyRow = """
            [{"courseNo":"CS101","courseName":"Algorithms","instructor":"Ada",
              "credits":3,"classroom":"T3-101","scheduleJson":"{\"1\":[\"2\",\"3\"]}",
              "classroomMapJson":"{}","moodleIdNumber":"1141CS101","isManual":false}]
        """.trimIndent()
        val type = object : TypeToken<List<Course>>() {}.type
        val course: Course = Gson().fromJson<List<Course>>(legacyRow, type).single()
        assertEquals(3f, course.credits, 0f)
    }
}
