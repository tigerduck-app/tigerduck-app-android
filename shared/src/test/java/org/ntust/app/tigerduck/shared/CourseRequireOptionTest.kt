package org.ntust.app.tigerduck.shared

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueryCourse's `RequireOption` ("R" required, "E" elective) rides along with
 * each course, as `dimension` and `allYear` do, so the class table can outline
 * required courses and the course details can name the type. Mirrors the
 * iPhone's `CourseRequireOptionTests` (tigerduck-app#208).
 *
 * The field is nullable for the reason [CourseGsonCompatTest] documents: a
 * cache written before it existed lacks the key, and Gson's Unsafe path leaves
 * it null rather than applying a Kotlin default.
 */
class CourseRequireOptionTest {

    private val gson = Gson()

    @Test
    fun `a cache row written before the field existed reads back as unknown`() {
        val course = gson.fromJson("""{"courseNo":"CS1003301","courseName":"計算機程式設計"}""", Course::class.java)
        assertNull(course.requireOption)
        assertFalse(course.isRequired)
    }

    @Test
    fun `only R is required, in either case`() {
        assertTrue(Course(courseNo = "CS1003301", courseName = "計算機程式設計", requireOption = "R").isRequired)
        assertTrue(Course(courseNo = "CS1003301", courseName = "計算機程式設計", requireOption = "r").isRequired)
        for (option in listOf("E", "e", "", " ", "X", null)) {
            assertFalse("$option is not required", Course(courseNo = "GE1001", courseName = "通識課", requireOption = option).isRequired)
        }
    }

    @Test
    fun `requireOption survives a cache round trip`() {
        val course = Course.fromSchedule(courseNo = "CS1003301", courseName = "計算機程式設計", requireOption = "R")
        val back = gson.fromJson(gson.toJson(course), Course::class.java)
        assertEquals("R", back.requireOption)
        assertTrue(back.isRequired)
    }
}
