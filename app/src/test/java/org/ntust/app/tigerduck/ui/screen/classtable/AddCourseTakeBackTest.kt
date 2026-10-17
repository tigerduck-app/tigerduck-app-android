package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Taking a course back out of Add course in the session it was added must not
 * leave a tombstone: one in `deletedCourseNos` would later hide a real enrolled
 * course with the same `courseNo` from cache and network merges. It still goes
 * through the regular delete, so the backend row the add already uploaded is
 * deleted too, or the next full sync brings the course back. As on the iPhone
 * (`removeUserAddedCourse`, tigerduck-app#84).
 *
 * Read from the source, as [ClassTableLocalEditsTest] does: the view model
 * takes fifteen collaborators, and what is pinned is the shape of two calls.
 */
class AddCourseTakeBackTest {

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
    fun `taking an added course back out is a delete without a tombstone`() {
        val lines = viewModelSource()
        val remove = body(lines, "removeAddedCourse")

        assertTrue("removeAddedCourse only takes back a hand-added course", remove.any { "isManual" in it })
        assertTrue(
            "removeAddedCourse goes through deleteCourse without a tombstone",
            remove.any { "deleteCourse(" in it && "tombstone = false" in it },
        )
        assertFalse("removeAddedCourse writes no tombstone itself", remove.any { "CourseTombstoneKeys" in it })
    }

    @Test
    fun `deleteCourse writes its tombstone only when asked`() {
        val lines = viewModelSource()
        val delete = body(lines, "deleteCourse")

        assertTrue("deleteCourse defaults to a tombstone", delete.first().contains("tombstone: Boolean = true"))
        val hide = delete.indexOfFirst { "CourseTombstoneKeys.hide(" in it }
        assertTrue("deleteCourse still tombstones a regular delete", hide >= 0)
        assertTrue(
            "the tombstone is written under `if (tombstone)`",
            delete.subList(0, hide).any { it.trim().startsWith("if (tombstone)") },
        )
    }
}
