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
        val fetch = at(body, "fetchCoursesAndAssignments(studentId, password, afterPull)")
        val afterPull = at(body, "afterPull()")
        val reread = at(body, "courses = dataCache.loadCourses().filterNot { it.courseNo in hiddenNow() }")
        val commit = at(body, "commitFetchedTerm(")
        val store = at(body, "moodleService.storeAssignments(it.assignments, it.answer)")
        assertTrue("the fetch no longer starts beside the pull", order < fetch && fetch < afterPull)
        assertTrue("the fetch no longer waits for the pull before it applies anything", afterPull < reread && reread < commit && afterPull < store)
        assertTrue("Home pulls outside the refresh order", body.indexOf("pullFromBackend()") < 0)
    }

    /**
     * Codex on PR R, finding 4: only the school's own answers are fetched beside the pull. What the
     * fetch derives from this phone's data (its record of the answer, the cached term and
     * transcript a failed lookup falls back on, the cached assignments) is read after `afterPull()`,
     * so the pull's writes are in it, as on dev, where the fetch began after the pull.
     */
    @Test
    fun `the school fetch reads and writes this phone's data only after the pull`() {
        val body = body("fetchCoursesAndAssignments")
        val afterPull = at(body, "afterPull()")
        val enrolled = at(body, "moodleService.fetchEnrolledCourses()")
        val selection = at(body, "courseService.fetchEnrolledCourseNos(studentId, password)")
        assertTrue("the school's answers no longer go beside the pull", enrolled < afterPull && selection < afterPull)
        for (read in listOf(
            "dataCache.recordSelectionRoster(", "dataCache.loadCourses(semester)", "dataCache.loadScoreReport(studentId)",
            "courseService.lookupOrFallback(", "dataCache.loadAssignments()", "moodleService.fetchAssignments(",
        )) {
            val at = at(body, read)
            assertTrue("$read comes before the pull is applied", at > afterPull)
        }
        assertTrue("fetchData no longer hands the fetch its afterPull", "fetchCoursesAndAssignments(studentId, password, afterPull)" in body("fetchData"))
    }

    /**
     * Codex on PR R, fix round 4, finding 1: recorded after the pull, an answer course selection gave
     * before it would be dated then, and taken for newer than one another fetch committed meanwhile.
     * Its time is taken as course selection answers, before any wait, and recorded with it, as on dev,
     * where the record came straight after the answer; `recordSelectionRoster` then refuses it as older.
     */
    @Test
    fun `Home dates its course selection answer when it came, not when it is recorded after the pull`() {
        val body = body("fetchCoursesAndAssignments")
        val fetched = at(body, "courseService.fetchEnrolledCourseNos(studentId, password)")
        val dated = at(body, "answeredAt = System.currentTimeMillis()")
        val afterPull = at(body, "afterPull()")
        val recorded = at(body, "dataCache.recordSelectionRoster(semester, it, signIn, answeredAt)")
        assertTrue("dated before the waits", fetched < dated && dated < afterPull)
        assertTrue("recorded with that time, after the pull", afterPull < recorded)
    }

    /** What that time decides, on the real cache: an answer older than one kept meanwhile is refused. */
    @Test
    fun `an answer older than one kept meanwhile is refused, and the newer stays`() = kotlinx.coroutines.runBlocking {
        val context = org.ntust.app.tigerduck.util.JvmContext()
        val prefs = org.ntust.app.tigerduck.data.preferences.AppPreferences(context)
        val cache = org.ntust.app.tigerduck.data.cache.DataCache(
            context, org.ntust.app.tigerduck.academic.AcademicCalendarStore(context, okhttp3.OkHttpClient(), prefs),
        )
        val signIn = org.ntust.app.tigerduck.util.testSignIn()
        cache.recordSelectionRoster("1151", listOf("XA0000001", "XC0000003"), signIn, answeredAt = 2_000L)

        val older = cache.recordSelectionRoster("1151", listOf("XA0000001", "XB0000002"), signIn, answeredAt = 1_000L)

        assertTrue("refused", older == org.ntust.app.tigerduck.data.RecordedSelection.Refused)
        assertTrue(cache.selectionAnswerIn("1151")?.courseNos == setOf("XA0000001", "XC0000003"))
    }

    @Test
    fun `the assignments the fetch wrote go up after the first pull on v4`() {
        val body = body("fetchData")
        val store = at(body, "moodleService.storeAssignments(it.assignments, it.answer)")
        val upload = at(body, "schoolUploads.assignments(written, remoteAssignments.answer.signIn) { e ->")
        assertTrue(store < upload)
        assertTrue("Home uploads around the gate", "pushApiClient" !in source().joinToString("\n"))
    }
}
