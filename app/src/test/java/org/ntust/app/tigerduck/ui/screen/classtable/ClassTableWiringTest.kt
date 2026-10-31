package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertFalse
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

    private fun source(): List<String> = source(rel)

    private fun source(path: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$path").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path from ${File("").absolutePath}")
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

    /**
     * tigerduck-app-android#264: mergeFetched keeps hand-added rows as stored, so a fetch looks
     * them up first — through ClassTableTermFetch, which folds the answers onto the term's list
     * as it is after the lookups: for the term on screen the list every edit writes, told by
     * the term it is tagged with, not the picker, which moves before the list is swapped.
     */
    @Test
    fun `a fetch looks the term's hand-added courses up again, onto the list as the user left it`() {
        assertCalls("fetchData", "fetchTermCourses(semester, source, moodleByNo, countsAsUse = true)")
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

    /**
     * The background sync can take a dropped course off the term's cache before its notice
     * reaches the list on screen. The commit folds under the courses' lock onto the cache as it
     * stands, taking from the screen its edits and the adds whose save has not landed; see
     * ClassTableTermFetch.foldLatest.
     */
    @Test
    fun `a fetch folds onto the term's cache under the courses' lock, with the adds still on their way`() {
        val body = body("fetchTermCourses")
        val locked = body.indexOf("dataCache.updateCourses(semester) { current, _ ->")
        assertTrue("fetchTermCourses no longer folds under the courses' lock", locked in 0 until body.indexOf("termFetch.foldLatest("))
        assertCalls("fetchTermCourses", "cachedCourses = { current }", "unsavedAdds = { unsavedAddsIn(semester) }")
        assertFalse("fetchTermCourses saves the folded term outside the courses' lock", "dataCache.saveCourses(" in body)
        val add = body("addCourse")
        val marked = add.indexOf("unsavedAdds[unsaved] = ")
        val saved = add.indexOf("dataCache.saveCourses(updated, semester)")
        assertTrue("addCourse no longer marks its add unsaved before launching the save", marked in 0 until add.indexOf("viewModelScope.launch"))
        assertTrue("addCourse no longer lets go of its add once saved", add.indexOf("unsavedAdds.remove(unsaved)", saved) > saved)
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
        assertCalls("warmTerm", "fetchTermCourses(semester, source, moodleByNo) {", "isCurrent()")
        // The commit — the generation check, the snapshot, the fold, the save and the upload —
        // in the order the backend's DELETEs run in, and held through the upload: a reset's
        // DELETE waits for an upload under way, and an upload after it finds the term superseded.
        // The snapshot's own read order (the cache, then the file, then the list chosen and
        // folded with nothing suspending in between) is ClassTableTermFetch.foldLatest's, under
        // its coroutine tests; what is pinned here is that the commit hands it the right sources.
        val body = body("fetchTermCourses")
        val commit = body.indexOf("pendingCourseAdds.uploading {")
        val fold = body.indexOf("termFetch.foldLatest(")
        // The fold is saved by the courses' lock it is taken under.
        val save = body.indexOf("dataCache.updateCourses(semester)")
        val upload = body.indexOf("pushApiClient.uploadCourses(")
        val checks = Regex("""if \(!mayCommit\(\)\) return""").findAll(body).map { it.range.first }.toList()
        assertTrue("fetchTermCourses no longer commits under PendingCourseAdds.uploading", commit in 0 until fold)
        assertTrue("fetchTermCourses no longer checks mayCommit under the lock before the snapshot", checks.any { it in commit until fold })
        assertTrue("fetchTermCourses no longer folds onto a snapshot taken under the lock", save in commit until fold)
        assertTrue("fetchTermCourses no longer checks mayCommit before the upload", checks.any { it in fold until upload })
        assertCalls(
            "fetchTermCourses",
            "cachedCourses = { current }",
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
        assertCalls("warmTerms", "termWarm.run(")
        assertCalls("warmTerm", "fetchTermCourses(semester, source, moodleByNo)")
        val source = source().joinToString("\n")
        for (call in listOf("warmJob?.cancel()", "termWarm.reset()")) {
            assertTrue("a sign-out no longer calls $call", call in source)
        }
    }

    // --- Past terms from the transcript (iOS tigerduck-app#890) ---

    /**
     * The page's own fetch asks the score system only for a term course selection gave nothing
     * for, alongside Moodle, through its day-long cache; the warm of the other terms reuses that
     * answer. The fetch and the warm read their terms the same way.
     */
    @Test
    fun `the transcript is asked for only when course selection gave nothing, and handed to the warm`() {
        assertCalls(
            "fetchData",
            "if (selectionDef.await().isEmpty()) transcript.fetched() else transcript.cachedOnly()",
            "scoreService.fetchScoreReport(studentId, password)",
            "ServerStatusTracker.set(it, ServerKind.SCORE_SYSTEM)",
            "warmTerms(studentId, password, moodleAll, transcript)",
            "ClassTableTermSource.of(semester, selectionNos, report, servesSelectionSemester,",
        )
        assertTrue("fetchData forces the transcript past its cache", "forceRefresh" !in body("fetchData"))
        assertCalls("warmTerms", "shared = { transcript.fetched() }")
        assertCalls("warmTerm", "ClassTableTermSource.of(semester, selectionNos, report, servesSelection,")
    }

    /** Moodle's sites add no course to any term; they still decide whose assignments are asked for. */
    @Test
    fun `Moodle's sites are no term's roster`() {
        for (name in listOf("fetchData", "warmTerm")) {
            assertFalse("$name still takes Moodle's sites for a roster", "rosterOrder" in body(name))
        }
        assertCalls("fetchData", "rosterCourseNos = assignmentNos.toSet()")
        assertFalse("the warm asks for assignments", "assignmentCourseNos" in body("warmTerm"))
    }

    /**
     * Answers fetched for an account that signed out meanwhile reach no term (iOS
     * WarmTermsTests): the page's own term commits only while the account its fetch began under
     * is signed in, and so does each term of the warm, whose answers are dropped otherwise; see
     * ClassTableTermWarmTest.
     */
    @Test
    fun `answers fetched for an account that left reach no term`() {
        assertCalls("fetchData", "fetchTermCourses(semester, source, moodleByNo, countsAsUse = true) { authService.isStillSignedInAs(studentId) }")
        assertCalls("fetchData", "stillSignedIn = { authService.isStillSignedInAs(studentId) }")
        assertCalls("warmTerms", "stillSignedIn = { authService.isStillSignedInAs(studentId) }")
    }

    /**
     * A rebuilt past term's drops reach the disk before the term is saved, inside the commit
     * lock, with the hand-adds still on their way up kept out of them; the order within the
     * commit is ClassTableTermFetch.committed's, under ClassTableTermFetchTest.
     */
    @Test
    fun `a rebuilt past term records its drops, then is saved and uploaded, under the commit lock`() {
        val body = body("fetchTermCourses")
        val commit = body.indexOf("pendingCourseAdds.uploading {")
        val committed = body.indexOf("termFetch.committed(")
        val record = body.indexOf("dataCache.recordFinalRoster(semester, it.roster, alsoListed = it.dropping, generation)")
        val fold = body.indexOf("termFetch.foldLatest(")
        val save = body.indexOf("dataCache.updateCourses(semester)")
        val upload = body.indexOf("pushApiClient.uploadCourses(")
        assertTrue("fetchTermCourses no longer commits the drawn term under the lock", commit in 0 until committed)
        assertTrue("fetchTermCourses no longer records a rebuild's drops in the commit", record in committed until fold)
        assertTrue("fetchTermCourses no longer saves, then uploads, the folded term", record < save && save < fold && fold < upload)
        assertCalls(
            "fetchTermCourses",
            "waitingAdds = { pendingCourseAdds.waitingIn(semester) }",
            // A term only gaining leaves out what a rebuild committed meanwhile dropped.
            "droppedNos = { dataCache.loadSelectionDroppedNos()[semester].orEmpty().toSet() }",
            "atMost = source.lookupsAtOnce",
        )
    }

    /** Someone else's class table is looked up as shared; only the user's own terms are rebuilt. */
    @Test
    fun `someone else's class table is never rebuilt from a transcript`() {
        val shown = body("refreshShownPerson")
        for (call in listOf("ClassTableTermSource", "recordFinalRoster", "transcript", "fetchTermCourses")) {
            assertFalse("refreshShownPerson reaches $call", call in shown)
        }
        val lookup = source("src/main/java/org/ntust/app/tigerduck/data/sharing/ImportedCourseLookup.kt").joinToString("\n")
        for (call in listOf("TranscriptTerms", "recordFinalRoster", "ScoreReport", "ClassTableTermSource")) {
            assertFalse("ImportedCourseLookup reaches $call", call in lookup)
        }
    }
}
