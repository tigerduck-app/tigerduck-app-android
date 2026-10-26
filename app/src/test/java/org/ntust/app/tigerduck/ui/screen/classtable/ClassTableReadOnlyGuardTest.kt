package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Someone else's class table is read-only: every edit of the user's own class table refuses
 * while it shows, whatever the screen offers. The page hides the edits there, but a dialog left
 * open across a switch, or a stray call, must not change the user's courses from it.
 * ClassTableViewModel needs Hilt and the whole network stack, so, as
 * ClassTableViewModelInitOrderTest does, this reads its source.
 */
class ClassTableReadOnlyGuardTest {

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

    /** The first statement of the function declared as `fun [name](`. */
    private fun firstStatement(name: String): String {
        val start = source.indexOfFirst { it.trimStart().startsWith("fun $name(") }
        if (start < 0) throw AssertionError("ClassTableViewModel has no `fun $name(`")
        val bodyStart = (start until source.size).first { source[it].trimEnd().endsWith("{") }
        return source.drop(bodyStart + 1).first { it.isNotBlank() }.trim()
    }

    @Test
    fun `every edit of the user's own class table refuses while someone else's shows`() {
        val edits = mapOf(
            "addCourse" to "if (isShowingSomeoneElse) return false",
            "setCustomCourseName" to "if (isShowingSomeoneElse) return",
            "revertCourseName" to "if (isShowingSomeoneElse) return",
            "toggleIgnoreAssignments" to "if (isShowingSomeoneElse) return",
            "deleteCourse" to "if (isShowingSomeoneElse) return",
            "updateCourseColor" to "if (isShowingSomeoneElse) return",
            "resetCourses" to "if (isShowingSomeoneElse) return",
        )

        assertEquals(edits, edits.keys.associateWith(::firstStatement))
    }

    @Test
    fun `the read-only check is whose class table the page shows`() {
        val check = source.single { "private val isShowingSomeoneElse" in it }
        assertEquals("private val isShowingSomeoneElse: Boolean get() = _displayed.value.personId != null", check.trim())
    }
}
