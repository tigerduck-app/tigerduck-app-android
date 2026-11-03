package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The order of Home's refresh, read from [HomeViewModel]'s source: the view model takes some twenty
 * collaborators and cannot be built in a JVM test, as ClassTableWiringTest says.
 */
class HomeRefreshOrderTest {

    private val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/home/HomeViewModel.kt"

    private fun source(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $rel from ${File("").absolutePath}")
    }

    /** Member function [name], up to the next member or doc comment. */
    private fun body(name: String): String {
        val lines = source()
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("HomeViewModel has no fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend) )*(?:fun|val|var) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun at(body: String, text: String): Int = body.indexOf(text).also {
        assertTrue("fetchData no longer has $text", it >= 0)
    }

    /**
     * Through [org.ntust.app.tigerduck.sync.RefreshOrder], whose order RefreshOrderTest holds: the
     * pull and the school's fetch start together, and what the fetch brought is read and
     * committed only once `afterPull()` says the pull has been applied.
     */
    @Test
    fun `the backend's pull and the school's fetch start together, and the fetch commits after the pull`() {
        val body = body("fetchData")
        val order = at(body, "RefreshOrder.run(pull = ::pullFromBackend, pushOutbox = {}) { afterPull ->")
        val fetch = at(body, "fetchCoursesAndAssignments(studentId, password)")
        val afterPull = at(body, "afterPull()")
        val reread = at(body, "courses = dataCache.loadCourses().filterNot { it.courseNo in hiddenNow() }")
        val commit = at(body, "commitFetchedTerm(")
        val store = at(body, "moodleService.storeAssignments(it.assignments, it.answer)")
        assertTrue("the fetch no longer starts beside the pull", order < fetch && fetch < afterPull)
        assertTrue("the fetch no longer waits for the pull before it applies anything", afterPull < reread && reread < commit && afterPull < store)
        assertTrue("Home pulls outside the refresh order", body.indexOf("pullFromBackend()") < 0)
    }

    @Test
    fun `the assignments the fetch wrote go up after the first pull`() {
        val body = body("fetchData")
        val store = at(body, "moodleService.storeAssignments(it.assignments, it.answer)")
        val upload = at(body, "schoolUploads.assignments(written, remoteAssignments.answer.signIn) { e ->")
        assertTrue(store < upload)
        assertTrue("Home uploads around the gate", "pushApiClient" !in source().joinToString("\n"))
    }
}
