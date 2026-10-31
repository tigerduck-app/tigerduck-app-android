package org.ntust.app.tigerduck.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where [ReviewPrompt] is told about the class table and where the ask is
 * launched (tigerduck-app-android#332). The two view models take some twenty
 * collaborators each and cannot be built in a JVM test, so what this pins is
 * that they, their pages and MainActivity still make the calls; read from
 * the source, as ClassTableWiringTest does.
 */
class ReviewPromptWiringTest {

    private val classTable = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt"
    private val home = "src/main/java/org/ntust/app/tigerduck/ui/screen/home/HomeViewModel.kt"
    private val classTableScreen = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableScreen.kt"
    private val homeScreen = "src/main/java/org/ntust/app/tigerduck/ui/screen/home/HomeScreen.kt"
    private val mainActivity = "src/main/java/org/ntust/app/tigerduck/MainActivity.kt"

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $rel from ${File("").absolutePath}")
    }

    /** Member function [name] of the file at [rel], up to the next member or doc comment. */
    private fun body(rel: String, name: String): String {
        val lines = source(rel)
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("$rel has no fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend) )*(?:fun|val|var) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertCountsAfterSaving(rel: String, name: String, save: String) {
        val body = body(rel, name)
        val saved = body.indexOf(save)
        val counted = body.indexOf("reviewPrompt.classTableLoaded()")
        assertTrue("$rel: $name no longer counts a day of use once it has saved the fetched courses", saved in 0 until counted)
    }

    @Test
    fun `a fetch counts as a day of use once the class table's courses are saved`() {
        // The class table's fetch saves each term's courses in fetchTermCourses.
        assertCountsAfterSaving(classTable, "fetchTermCourses", "dataCache.saveCourses(merged, semester)")
        assertCountsAfterSaving(home, "fetchData", "dataCache.saveCourses(courses)")
    }

    /** A warm of the other terms runs on its own, and must not lift a failed load's hold. */
    @Test
    fun `only the page's own fetch counts, not a warm of the other terms`() {
        assertTrue(
            "fetchTermCourses no longer counts only when asked to",
            "if (countsAsUse) reviewPrompt.classTableLoaded()" in body(classTable, "fetchTermCourses"),
        )
        assertTrue(
            "fetchData no longer asks its term fetch to count a day of use",
            "fetchTermCourses(semester, source, moodleByNo, countsAsUse = true)" in body(classTable, "fetchData"),
        )
        assertFalse("warmTerm counts a day of use", "countsAsUse" in body(classTable, "warmTerm"))
    }

    @Test
    fun `a class table fetch that failed is reported, so the ask waits`() {
        val body = body(classTable, "fetchData")
        assertTrue("fetchData no longer reports a failed fetch", "reviewPrompt.loadFailed()" in body)
    }

    /** The moment comes from the page on screen, not from a foreground return whatever page shows. */
    @Test
    fun `a resume of Home or the class table, on screen, is the moment to ask at`() {
        for (rel in listOf(classTable, home)) {
            assertTrue(
                "$rel: onPageResumed no longer offers a calm moment with the page's own resumed state",
                "reviewPrompt.calmMoment(pageResumed)" in body(rel, "onPageResumed"),
            )
            for (name in listOf("onPageShown", "onAppForeground")) {
                assertFalse("$rel: $name offers a calm moment whatever page is on screen", "reviewPrompt.calmMoment(" in body(rel, name))
            }
        }
    }

    /** An ask must not outlive the page that offered the moment. */
    @Test
    fun `the page pausing or being left withdraws the ask`() {
        for (rel in listOf(classTable, home)) {
            for (name in listOf("onPagePaused", "onPageLeft")) {
                assertTrue("$rel: $name no longer withdraws the ask", "reviewPrompt.withdraw()" in body(rel, name))
            }
        }
    }

    /**
     * A return from the background can find the class table's course sheet open. Home opens a
     * course as the navigation's course detail sheet, a window of its own, which the launch's
     * window-focus check covers.
     */
    @Test
    fun `no moment over the course detail sheet`() {
        for (rel in listOf(classTable)) {
            val body = body(rel, "onPageResumed")
            val sheet = body.indexOf("_selectedCourse.value != null")
            assertTrue("$rel: onPageResumed no longer holds the moment back over the course sheet", sheet in 0 until body.indexOf("reviewPrompt.calmMoment("))
        }
    }

    @Test
    fun `each page reports its own resume and pause, and answers whether it is still resumed`() {
        for (rel in listOf(classTableScreen, homeScreen)) {
            val text = source(rel).joinToString("\n")
            assertTrue("$rel no longer reports ON_RESUME to its view model", "Lifecycle.Event.ON_RESUME" in text && "viewModel.onPageResumed" in text)
            assertTrue("$rel no longer reports ON_PAUSE to its view model", "Lifecycle.Event.ON_PAUSE" in text && "viewModel.onPagePaused()" in text)
            assertTrue("$rel no longer answers with its own lifecycle", "isAtLeast(Lifecycle.State.RESUMED)" in text)
        }
    }

    /** A dialog, sheet or system prompt is a window of its own and takes the focus from the page's. */
    @Test
    fun `the host launches only while the activity's window has the focus`() {
        val text = source(mainActivity).joinToString("\n")
        val start = text.indexOf("reviewPrompt.launchIfStillCalm(")
        assertTrue("MainActivity no longer launches through launchIfStillCalm", start >= 0)
        val call = text.substring(start, text.indexOf("inAppReview.launch(", start))
        assertTrue("launchIfStillCalm no longer requires the window focus", "hasWindowFocus()" in call)
    }

    @Test
    fun `the host keeps the ask off the other dialogs`() {
        val text = source(mainActivity).joinToString("\n")
        val call = text.substring(text.indexOf("ReviewPromptHost("), text.indexOf("// Operator-issued popup"))
        for (state in listOf(
            "whatsNewFlow.value != null", "updatePending != null", "pendingPrompt != null", "popupPending != null",
            "permissionWarning", "needsReset", "apiRetired", "ntustSignInSheetRequested",
        )) {
            assertTrue("ReviewPromptHost no longer stays off $state", state in call)
        }
    }
}
