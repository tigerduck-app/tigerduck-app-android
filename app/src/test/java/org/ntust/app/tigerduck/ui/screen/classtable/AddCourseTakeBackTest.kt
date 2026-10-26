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
        // The edit a fetch reads until the write lands (see ClassTableTombstoneEdits), then the write,
        // which marks a hand-added course's hide (DataCache.hideDeletedCourse).
        val edits = delete.indices.filter { "CourseTombstoneKeys.hide(" in delete[it] }
        val writes = delete.indices.filter { "dataCache.hideDeletedCourse(" in delete[it] }
        assertTrue("deleteCourse still tombstones a regular delete", edits.size == 1 && writes.size == 1)
        val edit = edits.single()
        val write = writes.single()
        // The mark a course selection answer lifts comes once the DELETE is kept, and before it is
        // sent: lifted before it is kept, the course would go up with the roster and the DELETE
        // take it off again; marked after the send, a process killed during it would leave none.
        val mark = delete.indexOfFirst { "dataCache.hideDeletedCourse(courseNo, semester, deletedCourse)" in it }
        val kept = delete.indexOfFirst { "pendingCourseDeletes.keep(semester, courseNo, queuedAt)" in it }
        // Sent as kept, not queued again: a full sync's retry may have sent it meanwhile.
        val sent = delete.indexOfFirst { "pendingCourseDeletes.sendKept(semester, courseNo)" in it }
        assertTrue("the delete's hide is marked between keeping its DELETE and sending it", kept in 0 until mark && mark < sent)
        assertTrue(
            "the edit a fetch reads is made under `if (tombstone)`",
            delete.subList(0, edit + 1).any { "if (tombstone)" in it },
        )
        assertTrue(
            "the tombstone is written only with that edit",
            delete.subList(edit + 1, write).any { it.trim().startsWith("if (tombstoneEdit != null)") },
        )
    }
}
