package org.ntust.app.tigerduck.widget

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/** A widget's course tile in dark mode, as the class table's: a Moodle color takes its dark variant too. */
class WidgetCourseColorTest {

    private val course = Course(courseNo = "CS1", courseName = "Data Structures")

    @Test
    fun `a Moodle color darkens in dark mode, and stays itself in light mode`() {
        val moodle = mapOf("CS1" to Color(0xFF569E9E))

        assertEquals(Color(0xFF375F5F), widgetCourseColor(course, moodle, isDark = true))
        assertEquals(Color(0xFF569E9E), widgetCourseColor(course, moodle, isDark = false))
    }

    @Test
    fun `a color of the student's own stays as picked in dark mode`() {
        assertEquals(Color(0xFF123456), widgetCourseColor(course, mapOf("CS1" to Color(0xFF123456)), isDark = true))
    }
}
