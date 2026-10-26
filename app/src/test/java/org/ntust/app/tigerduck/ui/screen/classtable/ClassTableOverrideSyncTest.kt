package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rename or a colour reaches other devices for the course that was edited. Renamed from the
 * today strip while the grid shows another term, that course is the live term's copy: looked up
 * on the grid instead, the rename went nowhere, or to the older term's course of the same number.
 * ClassTableViewModel needs Hilt and the whole network stack, so, as
 * ClassTableReadOnlyGuardTest does, this reads its source.
 */
class ClassTableOverrideSyncTest {

    private val source: List<String> = run {
        val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.isFile }?.let { return@run it.readLines() }
            File(dir, "app/$rel").takeIf { it.isFile }?.let { return@run it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate ClassTableViewModel.kt")
    }

    /** The lines of the function declared as `fun [name](`, up to its closing brace. */
    private fun body(name: String): List<String> {
        val start = source.indexOfFirst { "fun $name(" in it }
        if (start < 0) throw AssertionError("ClassTableViewModel has no `fun $name(`")
        val indent = source[start].takeWhile { it == ' ' }
        val end = (start + 1 until source.size).first { source[it] == "$indent}" }
        return source.subList(start, end + 1)
    }

    @Test
    fun `the sync is sent for the course it is given, not looked up on the grid`() {
        val sync = body("syncCourseOverride")
        assertTrue(sync.joinToString("\n"), sync.any { "course: Course" in it })
        assertFalse(sync.joinToString("\n"), sync.any { "_courses.value" in it })
    }

    @Test
    fun `the selected course is only ever cleared together with its term`() {
        // A term left behind would send the next rename from the grid's menu to the live term's copy.
        val clears = source.withIndex().filter { "_selectedCourse.value = null" in it.value }
        val clearSelection = body("clearSelection")
        assertEquals(
            clears.joinToString("\n") { "${it.index + 1}: ${it.value.trim()}" },
            listOf(clearSelection.single { "_selectedCourse.value = null" in it }),
            clears.map { it.value },
        )
        assertTrue(clearSelection.any { "selectedCourseSemester = null" in it })
    }

    /**
     * tigerduck-app-android#380: the outbox lets a change go on a 404 once its course has left the
     * term it was made in, so the term is the one the course was read from — found by identity,
     * before the lists are rebuilt with the rename and every element replaced.
     */
    @Test
    fun `a rename records the term its course came from, read before the lists are rebuilt`() {
        for (name in listOf("setCustomCourseName", "revertCourseName")) {
            val text = body(name).joinToString("\n")
            val term = text.indexOf("termOf(course)")
            val rebuilt = text.indexOf("ClassTableSelection.renamed(")
            assertTrue("$name: termOf(course) must come before the lists are rebuilt", term in 0 until rebuilt)
            assertTrue("$name: the term goes with the sync", "syncCourseOverride(course, semester" in text)
        }
        val termOf = body("termOf").joinToString("\n")
        assertTrue(termOf, "it === course" in termOf)
    }

    @Test
    fun `a rename and its revert pick the course by where it was opened from`() {
        val call = Regex("""ClassTableSelection\.renameTarget\(([^)]*)\)""")
        for (name in listOf("setCustomCourseName", "revertCourseName")) {
            val text = body(name).joinToString("\n")
            val args = call.findAll(text).map { it.groupValues[1] }.toList()
            assertEquals("$name: $args", 1, args.size)
            assertTrue("$name: ${args.single()}", "selectedFromAnotherTerm" in args.single())
        }
    }
}
