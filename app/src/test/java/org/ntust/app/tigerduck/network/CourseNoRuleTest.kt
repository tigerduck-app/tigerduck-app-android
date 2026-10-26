package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one course-number rule, which the course-selection list, Moodle's course names and the share
 * codes read by: a course taken at NTU or NTNU, `3` and one letter, reaches the class table beside
 * NTUST's own (tigerduck-app-android#421, as iOS tigerduck-app#766).
 */
class CourseNoRuleTest {

    @Test
    fun `the list's course numbers are read, courses taken at NTU and NTNU too`() {
        val html = """
            <table>
            <tr><th>課程代碼</th><th>課程名稱</th></tr>
            <tr>
                <td>CS1010001</td><td>程式設計</td>
            </tr>
            <tr><td>3CS1010002</td><td>離散數學</td></tr>
            <tr><td>3N1010003</td><td>機器學習</td></tr>
            <tr><td>3T1010004</td><td>教育心理學</td></tr>
            <tr><td>A1010005</td><td>不是課號</td></tr>
            <tr><td>12</td><td>總學分</td></tr>
            </table>
        """.trimIndent()

        assertEquals(
            listOf("CS1010001", "3CS1010002", "3N1010003", "3T1010004"),
            CourseService.courseNosInListPage(html),
        )
    }

    @Test
    fun `a course number is a head of 3 and two letters, two letters, or 3 and one letter, then 6 or 7 letters or digits`() {
        val shapes = mapOf(
            "CS1010001" to true, "CS101001" to true, "3CS1010002" to true, "3N1010003" to true, "3T101004" to true,
            "3NG115701" to true, "N1010003" to false, "CS10100" to false, "3N10100030" to false, "cs1010001" to false,
            "CS1010-01" to false,
        )
        assertEquals(shapes, shapes.mapValues { (text, _) -> CourseService.isCourseNo(text) })
    }

    @Test
    fun `a Moodle course name drops a course number of another school`() {
        assertEquals("機器學習", MoodleService.courseName("115.1 3N1010003 機器學習"))
        assertEquals("程式設計", MoodleService.courseName("115.1 CS1010001 程式設計"))
        assertEquals("No code here", MoodleService.courseName("No code here"))
    }
}
