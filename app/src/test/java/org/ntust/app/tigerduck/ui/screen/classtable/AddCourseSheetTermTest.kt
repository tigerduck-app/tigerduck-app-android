package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Add course sheet searches and adds for the term it shows: the class table can move to
 * another term while the sheet is open (a refresh, when no term was picked), and a search model or
 * an added-course set kept from the old term would add the old term's times and rooms to the new
 * one's table. Read from the source, as [AddCourseTakeBackTest] does.
 */
class AddCourseSheetTermTest {

    private fun sheetSource(): String {
        val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/AddCourseSheet.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readText() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate AddCourseSheet.kt from ${File("").absolutePath}")
    }

    @Test
    fun `the search model and the courses added here start again for another term`() {
        val source = sheetSource()

        assertTrue("the model is kept per term", Regex("""val model = remember\(semester\)""").containsMatchIn(source))
        assertTrue("the added courses are kept per term", Regex("""sessionAddedCourseNos by remember\(semester\)""").containsMatchIn(source))
        assertTrue("the search runs again for another term", Regex("""LaunchedEffect\([^)]*\bsemester\b""").containsMatchIn(source))
    }
}
