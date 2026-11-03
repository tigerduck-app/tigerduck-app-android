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

    /**
     * Inside the commit's lock, after its checks, as before; the term waiting for the pull is
     * built under that lock again, and checked again there.
     */
    @Test
    fun `the class table's term fetch uploads its term after the first pull`() {
        val body = body("ui/screen/classtable/ClassTableViewModel.kt", "fetchTermCourses")
        assertHas(
            "fetchTermCourses", body,
            "schoolUploads.term(signIn, semester, merged, roster, { pendingCourseAdds.uploading(it) }, mayCommit, answer = answer) {",
            "runCatching { pushApiClient.uploadCourses(upload.courses, semester, signIn, rosterTerms = upload.rosterTerms) }",
        )
        val lock = body.indexOf("pendingCourseAdds.uploading {")
        val term = body.indexOf("schoolUploads.term(")
        assertTrue("the term no longer goes from inside the commit's lock", lock in 0 until term)
    }

    @Test
    fun `the background's term fetch uploads after the first pull, through the app's push client`() {
        val source = source("notification/TermCourseSync.kt")
        assertHas(
            "TermCourseSync", source,
            "pushApiClient.uploadCourses(courses, semester, signIn, resend = resend, rosterTerms = rosterTerms)",
            "uploads = schoolUploads,",
            "uploads.term(signIn, semester, merged, term.roster, uploading, requested = rosterRequested, answer = answer) {",
        )
    }

    @Test
    fun `the background uploads the assignments its round wrote after the first pull`() {
        assertHas(
            "syncAssignments", body("notification/BackgroundSyncWorker.kt", "syncAssignments"),
            "schoolUploads.assignments(written, answer.signIn) {",
            "prefs.setLastSyncSource(SyncSource.LOCAL)",
        )
    }

    @Test
    fun `a status an assignment's page fetched goes up with the list it wrote after the first pull`() {
        assertHas(
            "AssignmentStatusWriter", source("data/moodle/AssignmentStatusWriter.kt"),
            "runCatching { schoolUploads.assignments(assignments, signIn) }",
        )
    }

    /** The full sync's own resend of a term goes in the pass, before its pull lands: never through the gate. */
    @Test
    fun `the full sync's resend of a term does not wait for the pull it is part of`() {
        for (rel in listOf("sync/reconcile/RosterReconciler.kt", "sync/reconcile/CurrentTermCourses.kt")) {
            val source = source(rel)
            assertTrue("$rel no longer resends the term itself", "pushApiClient.uploadCourses(outcome.toUpload, semester," in source)
            assertTrue("$rel waits for the first pull", ".term(" !in source && "afterFirstPull" !in source)
        }
    }

    /** Codex on PR R, finding 1: both passes' course list reads the terms waiting to go up in the term's step. */
    @Test
    fun `the pull's course list spares the courses whose upload waits for it`() {
        assertHas(
            "RosterReconciler", source("sync/reconcile/RosterReconciler.kt"),
            "uploadingNos = termCourseSync.uploadsWaitingIn(semester),", "termCourseSync::uploadsWaitingIn,",
        )
        assertHas("CurrentTermCourses", source("sync/reconcile/CurrentTermCourses.kt"), "uploadingNos = uploadsWaitingIn(semester),")
        assertHas("TermCourseSync", source("notification/TermCourseSync.kt"), "fun uploadsWaitingIn(semester: String): Set<String> = uploads.waitingIn(semester)")
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
