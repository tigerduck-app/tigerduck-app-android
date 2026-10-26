package org.ntust.app.tigerduck.widget.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every widget names a course as the class table does, by `displayName`: the user's rename when
 * there is one. The Week widget's two-course conflict tile used to pass `courseName`, so a
 * renamed course kept its school name there alone.
 */
class WidgetCourseNamesTest {

    @Test
    fun `every widget label takes a course's display name`() {
        val sources = File("src/main/java/org/ntust/app/tigerduck/widget").walk()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("found only ${sources.size} files", sources.size > 10)
        // A label argument fed from a Course: `name = course.x` or `courseName = cell.courseA.x`.
        val label = Regex("""\b(?:courseName|name)\s*=\s*([\w.]*\b(?:course|courseA|courseB)\.\w+)""")
        val raw = sources.flatMap { file ->
            label.findAll(file.readText()).map { it.groupValues[1] }
                .filterNot { it.endsWith(".displayName") }
                .map { "${file.name}: $it" }
                .toList()
        }
        assertEquals(emptyList<String>(), raw)
    }
}
