package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every class table edit that goes up to the backend tells Home's revision
 * poll ([org.ntust.app.tigerduck.ui.screen.home.LocalEdits]), so another
 * device's follow-up arrives at the active cadence, as after a mark tapped on
 * Home. Only a tap on Home did (tigerduck-app-android#299).
 *
 * Read from the source, as [ClassTableViewModelInitOrderTest] does: the view
 * model takes fifteen collaborators, and what is pinned is one call in each
 * edit.
 */
class ClassTableLocalEditsTest {

    private val edits = listOf(
        "addCourse",
        "setCustomCourseName",
        "revertCourseName",
        "toggleIgnoreAssignments",
        "deleteCourse",
        "updateCourseColor",
        "resetCourses",
    )

    private fun viewModelSource(): List<String> {
        val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate ClassTableViewModel.kt from ${File("").absolutePath}")
    }

    /** The lines of member function [name], up to the next member or doc comment. */
    private fun body(lines: List<String>, name: String): List<String> {
        val start = lines.indexOfFirst { it.startsWith("    fun $name(") }
        if (start < 0) throw AssertionError("ClassTableViewModel has no fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override) )*(?:fun|val|var) |^ {4}/\*\*""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end)
    }

    @Test
    fun `every edit that reaches the backend speeds up the revision poll`() {
        val lines = viewModelSource()

        val silent = edits.filterNot { name -> body(lines, name).any { "localEdits.noteEdit()" in it } }

        assertEquals("These edits do not call localEdits.noteEdit()", emptyList<String>(), silent)
    }
}
