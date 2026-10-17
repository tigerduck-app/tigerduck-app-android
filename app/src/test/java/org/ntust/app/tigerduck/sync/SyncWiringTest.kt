package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the sync's tested rules are called. The rules have their own tests;
 * what these pin is that the classes that cannot be built in a JVM test (the
 * view models, the worker, the backend clients) still call them, so taking a
 * call out fails here. Read from the source, as
 * `ClassTableViewModelInitOrderTest` does.
 */
class SyncWiringTest {

    private fun source(rel: String): List<String> {
        val path = "src/main/java/org/ntust/app/tigerduck/$rel"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$path").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path from ${File("").absolutePath}")
    }

    /** Member function [name] of [rel], up to the next member, doc comment or the class's end. */
    private fun body(rel: String, name: String): String {
        val lines = source(rel)
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("$rel has no member fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend|@\w+) )*(?:fun|val|var|init) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertCalls(rel: String, name: String, vararg calls: String) {
        val body = body(rel, name)
        for (call in calls) assertTrue("$rel $name no longer calls $call", call in body)
    }

    @Test
    fun `Home's sync and the worker hold colours and names for their re-enable checks`() {
        assertCalls(
            "ui/screen/home/HomeBackendSync.kt", "pull",
            "CourseSyncReconciler.syncsCourseColors(", "CourseSyncReconciler.syncsCourseNames(",
        )
        assertCalls("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend", "CourseSyncReconciler.syncsCourseColors(")
    }

    @Test
    fun `Home's sync records only the categories it took`() {
        assertCalls("ui/screen/home/HomeBackendSync.kt", "pull", "categoriesTaken(", "categoriesSynced(switchedOn, took, prefs)")
    }

    @Test
    fun `an answered re-enable dialog holds colours and names until applied`() {
        assertCalls(
            "ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict",
            "heldUntilAnswered(", "prefs.pendingConflictCategories = held", "prefs.pendingConflictCategories - held",
        )
    }

    @Test
    fun `an answered re-enable dialog keeps what it did not cover pending and checks it after`() {
        assertCalls(
            "ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict",
            "unanswered(prefs.pendingConflictCategories, conflict.categories)",
            "prefs.pendingConflictCategories = held + left",
            "if (left.isNotEmpty()) checkPendingConflicts()",
        )
    }

    @Test
    fun `a registration crossing the TigerSync switch registers again`() {
        assertCalls("push/PushRegistrationService.kt", "endRegistration", "leftSwitchBehind(appPreferences.cloudSyncEnabled)")
        assertCalls(
            "push/PushRegistrationService.kt", "updateCloudSyncEnabled",
            "switchPatches.send(request, enabled)", "leftSwitchBehind(appPreferences.cloudSyncEnabled)",
        )
    }

    @Test
    fun `the assignment upload goes through the ledger`() {
        assertCalls("push/PushApiClient.kt", "uploadAssignments", "assignmentUploads.sendIfDue(")
    }

    @Test
    fun `a full sync and a poll that reaches the server run an owed re-enable check`() {
        assertCalls("ui/screen/home/HomeBackendSync.kt", "pull", "reenableCheck.runIfOwed()")
        assertCalls("ui/screen/home/HomeViewModel.kt", "pollRevision", "reenableCheck.runIfOwed()")
    }

    @Test
    fun `a hand-added course's upload is kept until it lands, and every full sync sends what waits first`() {
        assertCalls("ui/screen/classtable/ClassTableViewModel.kt", "addCourse", "pendingCourseAdds.send(")
        for ((rel, name) in listOf(
            "ui/screen/home/HomeBackendSync.kt" to "pull",
            "notification/BackgroundSyncWorker.kt" to "syncOverridesFromBackend",
        )) {
            val body = body(rel, name)
            val retry = body.indexOf("pendingCourseAdds.retry()")
            val fetch = body.indexOf("syncApiClient.fetchFullSync()")
            val listed = body.indexOf("pendingCourseAdds.listed(result.serverCourses)")
            assertTrue("$rel $name no longer sends the waiting course adds before the snapshot", retry in 0 until fetch)
            assertTrue("$rel $name no longer lets go of the course adds the snapshot lists", listed > fetch)
        }
    }

    /** tigerduck-app-android#380. */
    @Test
    fun `a colour or name whose PATCH failed is kept, and every full sync sends what waits first and holds it against the snapshot`() {
        assertCalls("ui/screen/classtable/ClassTableViewModel.kt", "syncCourseOverride", "pendingCourseOverrides.send(")
        for ((rel, name) in listOf(
            "ui/screen/home/HomeBackendSync.kt" to "pull",
            "notification/BackgroundSyncWorker.kt" to "syncOverridesFromBackend",
        )) {
            val body = body(rel, name)
            val retry = body.indexOf("pendingCourseOverrides.retry()")
            val fetch = body.indexOf("syncApiClient.fetchFullSync()")
            val held = body.indexOf("pendingCourseOverrides.held(result.fetchedAtMs)")
            assertTrue("$rel $name no longer sends the waiting colours and names before the snapshot", retry in 0 until fetch)
            assertTrue("$rel $name no longer holds the waiting colours and names against the snapshot", held > fetch)
        }
    }

    /** tigerduck-app-android#380: sent directly, a reset's or a sync's colour races the outbox, and an older queued one lands over it. */
    @Test
    fun `every course colour or name PATCH goes through the outbox`() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "app/src/main/java").isDirectory && !File(root, "src/main/java").isDirectory) {
            root = root.parentFile
        }
        val main = root?.let { listOf(File(it, "app/src/main/java"), File(it, "src/main/java")).first(File::isDirectory) }
            ?: throw AssertionError("Could not locate app/src/main/java from ${File("").absolutePath}")
        val callers = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && ".patchCourseOverride(" in it.readText() }
            .map { it.name }
            .toSortedSet()
        assertEquals(setOf("PendingCourseOverrides.kt"), callers)
    }

    /** tigerduck-app-android#380: queued one by one as each is sent, a reset's older colour replaced a pick made meanwhile. */
    @Test
    fun `a reset of every colour is queued whole before the colours are saved, and sent after`() {
        val body = body("data/CourseColorStore.kt", "resetAllColors")
        val reserved = body.indexOf("val version = pendingCourseOverrides.reserveVersion()")
        val read = body.indexOf("dataCache.loadCourses()")
        val queued = body.indexOf("pendingCourseOverrides.queue(")
        val saved = body.indexOf("dataCache.saveCourses(updated)")
        val sent = body.indexOf("pendingCourseOverrides.retry()")
        assertTrue("resetAllColors no longer reserves the batch's version before reading the colours", reserved in 0 until read)
        assertTrue("resetAllColors no longer queues every colour before saving them", queued in read until saved)
        assertTrue("resetAllColors no longer sends the queued colours after saving them", sent > saved)
    }

    /** tigerduck-app-android#380: versioned when queued, a batch outbid an edit made while its snapshot was read. */
    @Test
    fun `Use Local reserves its batch's version before reading the colours and names it sends`() {
        val body = body("ui/screen/settings/SettingsViewModel.kt", "resolve")
        val reserved = body.indexOf("val version = pendingCourseOverrides.reserveVersion()")
        val read = body.indexOf("dataCache.loadCourses()")
        val queued = body.indexOf("pendingCourseOverrides.queue(")
        assertTrue("resolve no longer reserves the batch's version before reading what Use Local sends", reserved in 0 until read)
        // The read is the queue call's first argument, so it sits inside the call: what is pinned
        // is that the batch goes in at the version reserved above.
        assertTrue("resolve no longer queues what Use Local sends at the reserved version", queued > reserved && "version,\n" in body)
    }

    /** tigerduck-app-android#380: a course gone from this phone would be PATCHed, and refused, at every sync. */
    @Test
    fun `the syncs forget the waiting colours and names of the courses they prune or a reset elsewhere wipes`() {
        assertCalls("ui/screen/home/HomeBackendSync.kt", "syncCourseList", "pendingCourseOverrides.forget(")
        assertCalls("ui/screen/home/HomeBackendSync.kt", "applyCourseReset", "pendingCourseOverrides.forget(")
        assertCalls("notification/BackgroundSyncWorker.kt", "reconcileCurrentSemester", "pendingCourseOverrides.forget(")
    }

    /** tigerduck-app-android#380: the answer decides the waiting colours and names too. */
    @Test
    fun `Use Server drops the waiting colours and names of its categories, and Use Local sends its own once the hold is lifted`() {
        assertCalls("ui/screen/settings/SettingsViewModel.kt", "resolve", "pendingCourseOverrides.adoptServer(conflict.categories)")
        val body = body("ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict")
        val lifted = body.indexOf("prefs.pendingConflictCategories - held")
        val retry = body.indexOf("pendingCourseOverrides.retry()")
        assertTrue("resolveReenableConflict no longer sends the queued colours and names once the hold is lifted", retry > lifted && lifted >= 0)
    }

    @Test
    fun `the closed assignments are written only for the account that asked`() {
        val body = body("network/MoodleService.kt", "fetchAssignmentsRound")
        // The same check also guards the assignment records' write, inside it after this one.
        val guard = body.indexOf("if (tokenService.currentToken() == askedWith) {")
        val save = body.indexOf("if (closedNow != closedBefore) dataCache.saveClosedAssignments(closedNow)")
        assertTrue("the closed assignments are no longer written behind the token check", guard in 0 until save)
        assertTrue(
            "nothing else between the check and the write",
            body.substring(guard, save).count { it == '\n' } == 1,
        )
    }
}
