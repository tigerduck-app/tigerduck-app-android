package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where each upload derived from a school or Moodle fetch is made, and a local edit's, read from
 * the source: the view models, the worker and the Hilt wiring cannot be built in a JVM test, as
 * ClassTableWiringTest says. When each goes is SchoolUploadsTest's.
 */
class SchoolUploadsWiringTest {

    private fun source(rel: String): String {
        val path = "src/main/java/org/ntust/app/tigerduck/$rel"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).let { if (it.isFile) return it.readText() }
            File(dir, "app/$path").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path from ${File("").absolutePath}")
    }

    /** Member function [name] of [rel], up to the next member or doc comment. */
    private fun body(rel: String, name: String): String {
        val lines = source(rel).lines()
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("$rel has no member fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend|@\w+) )*(?:fun|val|var|init) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertHas(where: String, text: String, vararg calls: String) {
        for (call in calls) assertTrue("$where no longer has $call", call in text)
    }

    @Test
    fun `the class table's term fetch uploads its term as it commits it`() {
        assertHas(
            "fetchTermCourses", body("ui/screen/classtable/ClassTableViewModel.kt", "fetchTermCourses"),
            "runCatching { pushApiClient.uploadCourses(upload.courses, semester, signIn, rosterTerms = upload.rosterTerms) }",
        )
    }

    @Test
    fun `the background's term fetch uploads through the app's push client`() {
        assertHas(
            "TermCourseSync", source("notification/TermCourseSync.kt"),
            "pushApiClient.uploadCourses(courses, semester, signIn, resend = resend, rosterTerms = rosterTerms)",
        )
    }

    @Test
    fun `the background uploads the assignments its round wrote as it writes them`() {
        assertHas(
            "syncAssignments", body("notification/BackgroundSyncWorker.kt", "syncAssignments"),
            "runCatching { pushApiClient.uploadAssignments(written, answer.signIn) }",
        )
    }

    @Test
    fun `a status an assignment's page fetched goes up with the list it wrote as it writes it`() {
        assertHas(
            "AssignmentStatusWriter", source("data/moodle/AssignmentStatusWriter.kt"),
            "runCatching { pushApiClient.uploadAssignments(assignments, signIn) }",
        )
    }

    /** `sync-order`, "A local edit SHALL be sent at once": a delete's upload waits for no pull. */
    @Test
    fun `a local edit's upload goes at once`() {
        assertHas(
            "deleteCourse", body("ui/screen/classtable/ClassTableViewModel.kt", "deleteCourse"),
            "runCatching { pushApiClient.uploadCourses(updated, semester, signIn) }",
        )
    }
}
