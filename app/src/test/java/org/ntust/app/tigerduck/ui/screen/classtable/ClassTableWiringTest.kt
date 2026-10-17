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

    /** tigerduck-app-android#347: an add landing after the DELETE would bring the course back. */
    @Test
    fun `a delete and a reset reach the backend with no course add being sent`() {
        for ((name, call) in listOf(
            "deleteCourse" to "pushApiClient.deleteCourse(courseKey)",
            "wipeTerm" to "termReset.run(semester)",
        )) {
            val body = body(name)
            val held = body.indexOf("pendingCourseAdds.removing {")
            assertTrue("$name no longer holds the course adds back over $call", held in 0 until body.indexOf(call))
        }
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

    /**
     * tigerduck-app-android#264: mergeFetched keeps hand-added rows as stored, so a fetch looks
     * them up first — through ClassTableTermFetch, which folds the answers onto the term's list
     * as it is after the lookups: for the term on screen the list every edit writes, told by
     * the term it is tagged with, not the picker, which moves before the list is swapped.
     */
    @Test
    fun `a fetch looks the term's hand-added courses up again, onto the list as the user left it`() {
        assertCalls("fetchData", "fetchTermCourses(semester, orderedCourseNos, moodleByNo, countsAsUse = true)")
        assertCalls("fetchTermCourses", "termFetch.lookUp(", "termFetch.foldLatest(", "latestCourses(semester)")
        assertCalls("latestCourses", "if (coursesSemester == semester) _courses.value else dataCache.loadCourses(semester)")
    }

    /** The list on screen moves with its tag, so a reader can tell the list from the picker. */
    @Test
    fun `the list on screen is swapped together with the term it belongs to`() {
        for (name in listOf("setSemester", "followNewestSemesterIfUnpicked", "loadNow")) {
            assertCalls(name, "showCourses(")
            assertTrue("$name still swaps the list without its tag", "_courses.value = " !in body(name))
        }
        assertCalls("showCourses", "coursesSemester = semester", "_courses.value = courses")
        assertCalls("lookUpMissingRooms", "coursesSemester != semester")
    }

    /** deleteCourse takes the row off the list at once and writes its tombstone after. */
    @Test
    fun `a delete's tombstone reaches a fetch before its write lands`() {
        for (name in listOf("deleteCourse", "addCourse")) {
            val body = body(name)
            val edit = body.indexOf("tombstoneEdits.edit {")
            assertTrue("$name no longer notes its tombstone edit before launching the write", edit in 0 until body.indexOf("viewModelScope.launch"))
            assertTrue("$name no longer retires the edit once written", "tombstoneEdits.saved(" in body)
        }
        assertCalls("fetchTermCourses", "withPendingEdits = tombstoneEdits::over")
    }

    /**
     * An edit made while a commit reads the term's cache may reach the cache after the read, with
     * the list on screen gone to another term by then; the commit counts the term's edits and
     * reads again — see ClassTableTermFetch.foldLatest, whose coroutine tests cover the retry.
     */
    @Test
    fun `every in-place edit of a term's list is counted at the tap, for a commit to read again`() {
        for (name in listOf(
            "addCourse", "deleteCourse", "setCustomCourseName", "revertCourseName", "updateCourseColor", "lookUpMissingRooms",
        )) {
            val body = body(name)
            val edit = body.indexOf("_courses.value = updated")
            val noted = body.indexOf("noteTermEdit(")
            assertTrue("$name no longer counts its edit right after making it", edit in 0 until noted)
        }
        // The abbreviation relabel is an edit too, made from a collector in init rather than a function.
        val source = source().joinToString("\n")
        val relabel = source.indexOf("_courses.value = relabeled")
        assertTrue("the relabel collector is gone", relabel >= 0)
        assertTrue(
            "the abbreviation relabel no longer counts its edit right after making it",
            source.indexOf("noteTermEdit(semester)", relabel) in relabel until source.indexOf("dataCache.saveCourses(relabeled", relabel),
        )
        assertCalls("fetchTermCourses", "editGeneration = { termEdits[semester] ?: 0 }")
    }

    /** A warm's older rows must not land over a reset of the term, or a fetch the user asked for. */
    @Test
    fun `a warm result superseded by a pick, a fetch or a reset is discarded, its upload included`() {
        for (name in listOf("setSemester", "followNewestSemesterIfUnpicked", "fetchData", "resetCourses")) {
            assertCalls(name, "termWarm.supersede(")
        }
        val reset = body("resetCourses")
        assertTrue(
            "resetCourses no longer supersedes the warm before anything else",
            reset.indexOf("termWarm.supersede(") < reset.indexOf("viewModelScope.launch"),
        )
        assertCalls("warmTerm", "fetchTermCourses(semester, rosterNos, moodleByNo) {", "isCurrent()")
        // The commit — the generation check, the snapshot, the fold, the save and the upload —
        // in the order the backend's DELETEs run in, and held through the upload: a reset's
        // DELETE waits for an upload under way, and an upload after it finds the term superseded.
        // The snapshot's own read order (the cache, then the file, then the list chosen and
        // folded with nothing suspending in between) is ClassTableTermFetch.foldLatest's, under
        // its coroutine tests; what is pinned here is that the commit hands it the right sources.
        val body = body("fetchTermCourses")
        val commit = body.indexOf("pendingCourseAdds.uploading {")
        val fold = body.indexOf("termFetch.foldLatest(")
        val save = body.indexOf("dataCache.saveCourses(")
        val upload = body.indexOf("pushApiClient.uploadCourses(")
        val checks = Regex("""if \(!mayCommit\(\)\) return""").findAll(body).map { it.range.first }.toList()
        assertTrue("fetchTermCourses no longer commits under PendingCourseAdds.uploading", commit in 0 until fold)
        assertTrue("fetchTermCourses no longer checks mayCommit under the lock before the snapshot", checks.any { it in commit until fold })
        assertTrue("fetchTermCourses no longer folds onto a snapshot taken under the lock", fold in commit until save)
        assertTrue("fetchTermCourses no longer checks mayCommit before the upload", checks.any { it in save until upload })
        assertCalls(
            "fetchTermCourses",
            "cachedCourses = { dataCache.loadCourses(semester) }",
            "storedTombstones = { dataCache.loadDeletedCourseNos() }",
            "shownCourses = { _courses.value.takeIf { coursesSemester == semester } }",
        )
    }

    /** tigerduck-app-android#264: rooms are announced late, and the page fetches on launch only. */
    @Test
    fun `each visit looks the courses without a room up again, and a hit reaches the widget`() {
        assertCalls("onPageShown", "lookUpMissingRooms()")
        assertCalls(
            "lookUpMissingRooms",
            "ClassTableCourseMerge.nosWithoutRoom(",
            "ClassTableCourseMerge.withRoomsFound(",
            "dataCache.saveCourses(",
            "widgetUpdater.requestUpdate()",
        )
    }

    /** tigerduck-app-android#265. */
    @Test
    fun `the first fetch of a session warms the other terms, and a sign-out ends the warm`() {
        val fetch = body("fetchData")
        val synced = fetch.indexOf("_syncCompleteEvent.tryEmit(Unit)")
        val warmed = fetch.indexOf("warmTerms(")
        assertTrue("fetchData no longer warms the other terms once its own is done", synced in 0 until warmed)
        assertCalls("warmTerms", "termWarm.run {")
        assertCalls("warmTerm", "fetchTermCourses(semester, rosterNos, moodleByNo)")
        val source = source().joinToString("\n")
        for (call in listOf("warmJob?.cancel()", "termWarm.reset()")) {
            assertTrue("a sign-out no longer calls $call", call in source)
        }
    }
}
