package org.ntust.app.tigerduck.ui.screen.coursemoodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The assignment page's files go through one opener for the whole page, as the materials page's
 * do: of two files tapped while they download only the latest opens, and a page opened on Moodle
 * from the bar or the Submit button stops a file still downloading from opening over it. Its
 * screen takes a Hilt view model, so this reads the source, as ReviewPromptWiringTest does.
 */
class MoodleAssignmentOpenerWiringTest {

    private val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/coursemoodle/MoodleAssignmentScreen.kt"

    private fun source(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readText() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $rel from ${File("").absolutePath}")
    }

    @Test
    fun `the page makes one opener for every file on it, and its bar and button stop a pending open`() {
        val text = source()
        assertEquals("one opener in the file", 1, Regex("""rememberMoodleFileOpener\(""").findAll(text).count())
        val screen = text.substring(text.indexOf("fun MoodleAssignmentScreen("), text.indexOf("fun AssignmentPageBody("))
        assertTrue("the screen no longer makes the page's opener", "rememberMoodleFileOpener(" in screen)
        assertTrue("the screen no longer hands its opener to the page's files", "files = files," in screen.substringAfter("AssignmentPageBody("))
        assertTrue("the bar no longer stops a pending open", "willOpenPage = { files?.forgetPendingOpen() }" in screen)
        val button = screen.substring(screen.indexOf("AssignmentSubmitButton("))
        assertTrue("the Submit button no longer stops a pending open", button.substringBefore("viewModel.noteMoodleHandOff()").contains("files?.forgetPendingOpen()"))
    }
}
