package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the class table's tested rules are called. The view model takes some twenty
 * collaborators and cannot be built in a JVM test, so what these pin is that it still calls
 * them; read from the source, as [ClassTableViewModelInitOrderTest] does.
 */
class ClassTableWiringTest {

    private val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt"

    private fun source(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate ClassTableViewModel.kt from ${File("").absolutePath}")
    }

    /** Member function [name], up to the next member or doc comment. */
    private fun body(name: String): String {
        val lines = source()
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("ClassTableViewModel has no fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend) )*(?:fun|val|var) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertCalls(name: String, vararg calls: String) {
        val body = body(name)
        for (call in calls) assertTrue("$name no longer calls $call", call in body)
    }

    /** tigerduck-app-android#241. */
    @Test
    fun `a reset goes through the backend first, and says so when the backend could not take it`() {
        assertCalls("wipeTerm", "termReset.run(semester) { pushApiClient.deleteAllCourses(semester) }")
        assertCalls("resetCourses", "if (wiped) fetchData() else _resetFailedEvent.tryEmit(semester)")
    }

    /**
     * tigerduck-app-android#347: an add landing after the DELETE would bring the course back.
     * A delete goes through PendingCourseDeletes, which keeps it until it lands and holds the
     * adds back itself (see PendingCourseDeletesTest), so the view model must not take that
     * lock around it: the lock is not reentrant.
     */
    @Test
    fun `a delete and a reset reach the backend with no course add being sent`() {
        val delete = body("deleteCourse")
        assertTrue("deleteCourse no longer deletes through the outbox", "pendingCourseDeletes.send(semester, courseNo, queuedAt)" in delete)
        assertTrue("deleteCourse takes the adds lock the outbox takes itself", "pendingCourseAdds.removing {" !in delete)

        val wipe = body("wipeTerm")
        val held = wipe.indexOf("pendingCourseAdds.removing {")
        assertTrue("wipeTerm no longer holds the course adds back over the reset", held in 0 until wipe.indexOf("termReset.run(semester)"))
    }

    /**
     * A retried delete of the term's, sent after the reset's refetch re-uploaded the roster,
     * would take a course off the server again and tombstone it: the term's queued deletes go
     * before the adds lock does, which the retry sends under.
     */
    @Test
    fun `a reset drops the term's queued deletes before letting the adds lock go`() {
        val wipe = body("wipeTerm")
        val reset = wipe.indexOf("termReset.run(semester)")
        val forgot = wipe.indexOf("pendingCourseDeletes.forgetTerm(semester)")
        val released = wipe.indexOf("?: return false")
        assertTrue("wipeTerm forgets the term's queued deletes after the reset lands", forgot > reset)
        assertTrue("wipeTerm forgets them inside the adds lock", forgot < released)
    }

    /** tigerduck-app-android#380: sent again later, a waiting colour or name would bring a row the user removed back. */
    @Test
    fun `a deleted course, and a reset term, take their waiting colours and names with them`() {
        assertCalls("deleteCourse", "pendingCourseOverrides.forget(")
        val wipe = body("wipeTerm")
        val reset = wipe.indexOf("termReset.run(semester)")
        val forgot = wipe.indexOf("pendingCourseOverrides.forget(")
        assertTrue("wipeTerm no longer forgets the term's waiting colours and names after the reset", forgot > reset)
    }

    /** tigerduck-app-android#242. */
    @Test
    fun `the page counts as settled once the first load's fetch is done`() {
        val body = body("loadNow")
        val fetch = body.indexOf("fetchFlight.join(::fetchData)")
        val settled = body.indexOf("_hasSettled.value = true")
        assertTrue("loadNow no longer marks the first load settled after its fetch", fetch in 0 until settled)
    }

    /** tigerduck-app-android#243. */
    @Test
    fun `a selection opens on its block`() {
        assertCalls("selectCourse", "_selectedPeriods.value = periods ?: ClassTableSelection.block(")
    }
}
