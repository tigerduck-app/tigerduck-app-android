package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Whether [at] lies inside the braces of the nearest [opener] before it: that block (its braces
     * balanced) is still open there, so an earlier block that has closed does not count.
     */
    private fun insideBlock(text: String, opener: String, at: Int): Boolean {
        val open = text.lastIndexOf(opener, at)
        if (open < 0) return false
        var depth = 0
        for (i in open + opener.length - 1 until at) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return false
        }
        return true
    }

    private fun assertCalls(rel: String, name: String, vararg calls: String) {
        val body = body(rel, name)
        for (call in calls) assertTrue("$rel $name no longer calls $call", call in body)
    }

    /**
     * A sign-in at another backend, the one the endpoint setting names since the sign-out, moved
     * nothing at the backend the unregister is owed to, so it does not end the debt there.
     */
    @Test
    fun `the sign-out's unregister asks for a session from the backend it is owed to`() {
        val source = source("push/DepartingDeviceUnregister.kt").joinToString("\n")
        assertTrue("sessionAt = authTokenManager::hasSessionAt" in source)
    }

    @Test
    fun `Home's sync and the worker hold colours and names for their re-enable checks`() {
        assertCalls(
            "sync/reconcile/OverridesReconciler.kt", "reconcile",
            "pass.policy.applies(SyncCategory.COURSE_COLORS)", "pass.policy.applies(SyncCategory.COURSE_NAMES)",
        )
        assertCalls("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend", "CourseSyncReconciler.syncsCourseColors(")
    }

    /**
     * tigerduck-app#727's Android side: a course added back is kept while its add waits, but not
     * past a course reset made on another device, which takes the hand-added courses.
     */
    @Test
    fun `Home's sync and the worker keep a course whose add still waits`() {
        assertCalls("sync/reconcile/RosterReconciler.kt", "reconcileTerm", "pendingAddNos = pendingCourseAdds.waitingIn(semester)")
        assertCalls("sync/reconcile/CourseResetReconciler.kt", "applyCourseReset", "pendingCourseAdds.forgetAll(signIn)")
        assertCalls(
            "notification/BackgroundSyncWorker.kt", "reconcileCurrentSemester",
            "CourseSyncReconciler.coursesReset(",
            "pendingAddNos = if (resetElsewhere) emptySet() else pendingCourseAdds.waitingIn(semester)",
        )
    }

    /**
     * tigerduck-app-android#461: a sync's commit leaves be a course added back or brought back since
     * it began (`DataCache.commitSyncHides`), which it knows only when the sync begins before its
     * fetch, ends however it ends, and the class table's add goes through the call that tells it.
     */
    @Test
    fun `Home's sync and the worker hold their read for the add-backs made while they run`() {
        assertCalls(
            "ui/screen/classtable/ClassTableViewModel.kt", "addCourse",
            "dataCache.addCourseBack(course.courseNo, semester, signIn)",
            "} finally {\n                dataCache.courseAddSent(course.courseNo, semester)",
        )
        for ((rel, name, calls) in listOf(
            Triple("sync/Synchronizer.kt", "sync", listOf("val courseSync = steps.beginCourseSync()", "steps.fetch(", "steps.endCourseSync(courseSync)")),
            Triple(
                "notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend",
                listOf("val courseSync = dataCache.beginCourseSync()", "syncApiClient.fetchFullSync(", "dataCache.endCourseSync(courseSync)"),
            ),
        )) {
            val body = body(rel, name)
            val (opens, fetches, closes) = calls
            val begin = body.indexOf(opens)
            val fetching = body.indexOf("courseSync.fetching()")
            val fetch = body.indexOf(fetches)
            assertTrue("$rel $name no longer begins its course sync before the fetch", begin in 0 until fetch)
            assertTrue("$rel $name no longer marks its course sync's fetch", fetching in begin until fetch)
            assertTrue("$rel $name no longer ends its course sync", "} finally {\n            $closes" in body)
        }
        // The foreground's steps are the cache's window and the snapshot's fetch.
        val steps = source("sync/ForegroundSteps.kt").joinToString("\n")
        for (call in listOf(
            "beginCourseSync(): DataCache.CourseSync = dataCache.beginCourseSync()",
            "endCourseSync(courseSync: DataCache.CourseSync) = dataCache.endCourseSync(courseSync)",
            "fetch(signIn: SignIn): BackendSyncResult = fetchSnapshot(signIn)",
        )) assertTrue("the foreground's steps no longer make $call", call in steps)
        assertTrue("the synchronizer no longer hands out the snapshot's fetch", "syncApiClient::fetchFullSync" in source("sync/Synchronizer.kt").joinToString("\n"))
        // Marked for the fetch the snapshot comes from, a retry after a relogin included.
        val worker = body("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend")
        assertTrue(
            "the worker no longer marks each attempt's fetch",
            worker.indexOf("retryingAfterRelogin(") in 0 until worker.indexOf("courseSync.fetching()"),
        )
        // The retry is the same request: the replay goes under the sign-in the run began with.
        assertCalls(
            "notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend",
            "retryingAfterRelogin(signIn, authService::attemptRelogin) { pinned ->",
            "syncApiClient.fetchFullSync(pinned)",
            // What a run its sign-out overtook says of the backend, it says for the next account.
            "if (signIn.isCurrent) prefs.setLastSyncSource(SyncSource.LOCAL)",
        )
        // A course reset elsewhere takes the hand-added courses and their adds, those under way too.
        assertCalls("sync/reconcile/CourseResetReconciler.kt", "applyCourseReset", "courseSync.supersedeAddsBack()")
        assertCalls("notification/BackgroundSyncWorker.kt", "reconcileCurrentSemester", "if (resetElsewhere) courseSync.supersedeAddsBack()")
    }

    /**
     * A background run that outlived its sign-in stops, a same-student one included: it asks the
     * sign-in after each phase and before the stamps that date the data as fetched, and never
     * compares the student ID alone.
     */
    @Test
    fun `the worker stops a run whose sign-in ended before it stamps or refreshes`() {
        val rel = "notification/BackgroundSyncWorker.kt"
        val worker = body(rel, "doWork")
        assertTrue("the worker no longer captures the sign-in", "authTokenManager.session.current" in worker)
        assertTrue("the worker compares the student ID alone again", "storedStudentId != studentId" !in worker)
        // Each stamp is written in a step a sign-out waits for, so it cannot clear it between the check and the write.
        assertTrue("the worker no longer stops on a refused stamp", "if (!prefs.school.backgroundOverridesSyncedAtMs.set(now, signIn)) return" in worker)
        for (stamp in listOf("prefs.school.backgroundCoursesSyncedAtMs.set(now, signIn)", "prefs.school.backgroundCoursesTerm.set(term.code, signIn)")) {
            val at = worker.indexOf(stamp)
            assertTrue("the worker no longer writes $stamp", at >= 0)
            assertTrue("the worker writes $stamp outside the sign-in's step", insideBlock(worker, "signIn.ifCurrent {", at))
        }
        // The courses phase ends the run before the assignments, and passes its sign-in to the fetch.
        val fetch = worker.indexOf("syncCourses(studentId, password, signIn)")
        val assignments = worker.indexOf("val assignmentsOk")
        assertTrue("the worker no longer passes its sign-in to the course fetch", fetch >= 0)
        assertTrue("the worker no longer checks right after the course fetch", "!signIn.isCurrent" in worker.substring(fetch, assignments))
        assertTrue("the worker does not check after the assignments", worker.indexOf("!signIn.isCurrent", worker.indexOf("syncAssignments()")) >= 0)
        assertTrue("the worker no longer checks before the refreshes", worker.lastIndexOf("!signIn.isCurrent", worker.indexOf("liveActivityManager.refreshAndWait()")) > worker.indexOf("syncAssignments()"))
        assertCalls(rel, "syncCourses", "termCourseSync.sync(studentId, password, courseService.currentSemesterCode(), signIn = signIn)")
    }

    @Test
    fun `Home's sync records only the categories it took`() {
        assertCalls("sync/Synchronizer.kt", "taken", "categoriesTaken(")
        assertCalls("sync/ForegroundSteps.kt", "recordTaken", "categoriesSynced(switchedOn, took, prefs)")
    }

    /** The worker cannot be built here. */
    @Test
    fun `the worker's settings pull runs on the session and arms nothing past its sign-in`() {
        val rel = "notification/BackgroundSyncWorker.kt"
        val body = body(rel, "pullSettings")
        assertTrue("the pull left the session", "authTokenManager.session.launch(signIn) {" in body)
        assertTrue("the run no longer waits for the pull", "}.join()" in body)
        assertTrue("the pull is on the application scope again", "appScope" !in source(rel).joinToString("\n"))
        assertCalls(rel, "syncOverridesFromBackend", "if (marksChanged && prefs.notifyAssignments && signIn.isCurrent) {")
    }

    /** The notifier cannot be held open here: one step with the sign-in's end, by reading it. */
    @Test
    fun `the Live Update's post lands in one step with the sign-in's end`() {
        assertCalls("liveactivity/LiveActivityManager.kt", "refreshLocked", "signIn.ifCurrent { notifier.apply(snapshot, quiet) }")
    }

    /** What a push it queues would do takes a running queue: nothing starts for a login that landed nothing. */
    @Test
    fun `a login cut off starts the copy only with a session`() {
        assertCalls("auth/AuthService.kt", "login", "if (authTokenManager.isLoggedIn) notificationSettingsSync.pushIfUnconfirmed()")
    }

    /** What stops them is RunRefreshesTest's; the worker cannot be built here. */
    @Test
    fun `the worker's refreshes stop with its sign-in`() {
        assertCalls("notification/BackgroundSyncWorker.kt", "doWork", "refreshAfterRun(signIn,")
    }

    @Test
    fun `an answered re-enable dialog holds colours and names until applied`() {
        assertCalls(
            "ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict",
            "heldUntilAnswered(", "prefs.school.pendingConflictCategories.set(held", "prefs.school.pendingConflictCategories.get() - held",
        )
    }

    @Test
    fun `an answered re-enable dialog keeps what it did not cover pending and checks it after`() {
        assertCalls(
            "ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict",
            "unanswered(prefs.school.pendingConflictCategories.get(), conflict.categories)",
            "prefs.school.pendingConflictCategories.set(held + left, signIn)",
            "if (left.isNotEmpty()) checkPendingConflicts()",
        )
    }

    @Test
    fun `a registration crossing the TigerSync switch registers again`() {
        assertCalls("push/PushRegistrationService.kt", "endRegistration", "leftSwitchBehind(appPreferences.cloudSyncEnabled)")
        assertCalls(
            "push/PushRegistrationService.kt", "updateCloudSyncEnabled",
            "switchPatches.send(request, enabled, signIn)", "leftSwitchBehind(appPreferences.cloudSyncEnabled)",
        )
    }

    @Test
    fun `the assignment upload goes through the ledger`() {
        assertCalls("push/PushApiClient.kt", "uploadAssignments", "assignmentUploads.sendIfDue(")
        // The fingerprint and its time in one step a sign-out waits for: a clear between the two
        // would leave half a record for the next account's upload to read.
        val upload = body("push/PushApiClient.kt", "uploadAssignments")
        assertTrue("the upload's record is no longer one step", Regex("""signIn\.ifCurrent \{\s*prefs\.school\.assignmentsUploadFingerprint\.set""").containsMatchIn(upload))
    }

    @Test
    fun `a full sync and a poll that reaches the server run an owed re-enable check`() {
        assertCalls("sync/ForegroundSteps.kt", "afterSync", "reenableCheck.runIfOwed()")
        assertCalls("ui/screen/home/HomeViewModel.kt", "pollRevision", "reenableCheck.runIfOwed()")
    }

    /**
     * A full sync that lands proves a session and a network: a notification-settings edit whose
     * push never landed goes again, and another device's change is picked up, on the application
     * scope and on Main, never awaited. Both go to scopes the JVM tests leave idle.
     */
    @Test
    fun `a full sync that lands sends the settings edit that waits and pulls the settings`() {
        assertCalls("sync/ForegroundSteps.kt", "afterSync", "notificationSettingsSync.pushIfUnconfirmed()", "pullSettings(pass.result.currentRevision)")
        assertCalls(
            "sync/ForegroundSteps.kt", "pullSettings",
            "appScope.launch(Dispatchers.Main) {", "notificationSettingsSync.pullNow(knownRevision = knownRevision)",
        )
    }

    @Test
    fun `a hand-added course's upload is kept until it lands, and every full sync sends what waits first`() {
        assertCalls("ui/screen/classtable/ClassTableViewModel.kt", "addCourse", "pendingCourseAdds.send(")
        assertCalls("sync/ForegroundSteps.kt", "retryUnsent", "pendingCourseAdds.retry(signIn)")
        assertCalls("sync/ForegroundSteps.kt", "listed", "pendingCourseAdds.listed(result.serverCourses, signIn)")
        // Home's order is SynchronizerOrderTest's.
        for ((rel, name, calls) in listOf(
            Triple("sync/Synchronizer.kt", "sync", listOf("steps.retryUnsent(signIn)", "steps.fetch(", "steps.listed(result, signIn)")),
            Triple(
                "notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend",
                listOf("pendingCourseAdds.retry(signIn)", "syncApiClient.fetchFullSync(", "pendingCourseAdds.listed(result.serverCourses, signIn)"),
            ),
        )) {
            val body = body(rel, name)
            val (sends, fetches, lists) = calls
            val retry = body.indexOf(sends)
            val fetch = body.indexOf(fetches)
            val listed = body.indexOf(lists)
            assertTrue("$rel $name no longer sends the waiting course adds before the snapshot", retry in 0 until fetch)
            assertTrue("$rel $name no longer lets go of the course adds the snapshot lists", listed > fetch)
        }
    }

    /**
     * A refused session gets one relogin, and the whole sync runs again, from where the failed one
     * has reported itself, its course window still open: the Synchronizer runs Home's failure step
     * there (SynchronizerOrderTest). A relogin that still gets a 401 gives up.
     */
    @Test
    fun `Home's sync signs in again once on a refused session and runs again`() {
        val pull = body("ui/screen/home/HomeBackendSync.kt", "pull")
        assertTrue("Home's pull is no longer the synchronizer's foreground sync", "synchronizer.sync(SyncMode.FOREGROUND, signIn, state) { e ->" in pull)
        assertTrue(
            "Home's pull no longer signs in again once on a refused session",
            Regex("""if \(!retried && e\.isSessionRefused\(\)\) \{[^}]*if \(authService\.attemptRelogin\(signIn\)\) \{\s*pull\(state, signIn, retried = true\)""")
                .containsMatchIn(pull),
        )
    }

    /** tigerduck-app-android#120: a revision move ran a full sync, whatever moved it. */
    @Test
    fun `Home's revision poll reads the change feed and sends what waits before moving the watermark`() {
        assertCalls(
            "ui/screen/home/HomeViewModel.kt", "pollRevision",
            "fetchChanges = syncApiClient::fetchChanges", "retryUnsent = backendSync::sendWhatWaits",
            "setLastKnownRevision = syncState.lastKnownRevision::set",
        )
        assertCalls("ui/screen/home/HomeBackendSync.kt", "retryUnsent", "synchronizer.retryUnsent(signIn)")
        assertCalls("sync/Synchronizer.kt", "retryUnsent", "steps.retryUnsent(signIn)")
        assertCalls(
            "sync/ForegroundSteps.kt", "retryUnsent",
            "courseIgnoreSync.retryPending(signIn)", "unsentMarks.retry(signIn)", "pendingCourseAdds.retry(signIn)",
            "pendingCourseDeletes.retry(signIn)", "pendingCourseOverrides.retry(signIn)", "pendingHolidayOverrides.retry(signIn)",
        )
        // And the notification-settings repair a full sync makes once it lands.
        assertCalls("ui/screen/home/HomeBackendSync.kt", "sendWhatWaits", "retryUnsent(signIn)", "notificationSettingsSync.pushIfUnconfirmed()")
    }

    /** tigerduck-app-android#120: the departing account's watermark let the next one's feed read skip its own data. */
    @Test
    fun `the watermark is the signed-in account's alone`() {
        // A SignInBound reads empty once its sign-in has ended, whoever saw the sign-out or not:
        // the next account's feed read from the watermark would skip that account's own changes,
        // and from 0 the poll runs a full sync until one lands for the account that signs in.
        val state = source("ui/screen/home/HomeBackendSync.kt").joinToString("\n")
        assertTrue("the watermark no longer goes with its sign-in", "val lastKnownRevision = org.ntust.app.tigerduck.shared.account.SignInBound(0L)" in state)
        assertTrue(
            "Home's sync no longer sets the watermark under the sign-in it fetched with",
            "pass.home.lastKnownRevision.set(pass.result.currentRevision, pass.signIn)" in body("sync/ForegroundSteps.kt", "noteRevision"),
        )
    }

    /**
     * The student ID passes a sign-out and a sign-in as the same student, so a run that outlived
     * its sign-in would mark the new session's marks as synced, which then reads an empty server
     * as the truth. Home's is driven in `HomePullAcrossSignInTest`; the worker cannot be built here.
     */
    @Test
    fun `the worker records marks taken only under the sign-in it began with`() {
        val body = body("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend")
        assertTrue(
            "the worker no longer records the marks under its sign-in",
            "prefs.school.assignmentMarksSyncedOnce.set(true, signIn)" in body,
        )
    }

    /**
     * A re-enable check and Use Local run on the application scope, past the Settings screen that
     * began them. Each stops at the sign-out, which the student ID alone would not see for the same
     * student. Read from the source: both take a screen's worth of collaborators.
     */
    @Test
    fun `the re-enable check and Use Local stop when their sign-in ends`() {
        assertCalls("ui/screen/settings/ReenableCheck.kt", "check", "val signIn = sessions.school.current", "signIn.isCurrent")
        assertCalls("ui/screen/settings/SettingsViewModel.kt", "sendLocalCourses", "isCurrent = { signIn.isCurrent }")
    }

    /** tigerduck-app-android#404. */
    @Test
    fun `a holiday choice whose PUT failed is kept, and Home's sync sends what waits first and keeps it over the snapshot`() {
        assertCalls("ui/screen/calendar/CalendarViewModel.kt", "setNotifyOnHoliday", "pendingHolidayOverrides.send(")
        assertCalls("sync/ForegroundSteps.kt", "retryUnsent", "pendingHolidayOverrides.retry(signIn)")
        // Before the fetch, and the snapshot applied after it: SynchronizerOrderTest.
        assertCalls("sync/reconcile/HolidaysReconciler.kt", "reconcile", "HolidayOverrideApply.apply(")
        assertCalls("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend", "pendingHolidayOverrides.retry(signIn)")
    }

    /** tigerduck-app-android#404: applied after the sign-out cleared them, the departing account's choices came back. */
    @Test
    fun `Home's sync and the worker apply the holiday snapshot through the one apply, under the sign-in it fetched with`() {
        for ((rel, name) in listOf("sync/reconcile/HolidaysReconciler.kt" to "reconcile", "notification/BackgroundSyncWorker.kt" to "syncOverridesFromBackend")) {
            assertCalls(rel, name, "HolidayOverrideApply.apply(", "signIn, pendingHolidayOverrides, academicCalendar")
        }
        val apply = source("data/HolidayOverrideApply.kt").joinToString("\n")
        assertTrue(
            "the holiday snapshot is no longer applied under signIn.ifCurrent",
            Regex("""signIn\.ifCurrent \{\s*pending\.merged\(""").containsMatchIn(apply),
        )
    }

    /** Calendar's toggle handler runs on Main; an apply off it can erase a toggle made after the merge's check. */
    @Test
    fun `the worker applies the holiday snapshot on the main thread`() {
        val body = body("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend")
        assertTrue(
            "the worker no longer applies the holiday snapshot inside withContext(Dispatchers.Main)",
            Regex("""withContext\(Dispatchers\.Main\) \{\s*HolidayOverrideApply\.apply\(""").containsMatchIn(body),
        )
    }

    /** Names go through the one apply, with the same re-enable hold as colours. */
    @Test
    fun `Home's sync and the worker apply custom names through the one apply, held for the re-enable check`() {
        assertCalls("sync/reconcile/OverridesReconciler.kt", "applyCourseOverrides", "CourseSyncReconciler.applyNameOverrides(")
        assertCalls("notification/BackgroundSyncWorker.kt", "applyCourseOverridesBackground", "BackgroundCourseOverrides.apply(")
        assertCalls("notification/BackgroundCourseOverrides.kt", "apply", "CourseSyncReconciler.applyNameOverrides(")
        assertCalls("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend", "CourseSyncReconciler.syncsCourseNames(")
        // The cached courses are what the widgets read.
        assertCalls("notification/BackgroundCourseOverrides.kt", "apply", "CourseNameOverrides.stamp(")
    }

    /**
     * Greptile on #615: read and saved apart, the names and the courses lost a rename or a course
     * added between the read and the save. The update functions' own tests force that
     * interleaving; these pin that the syncs go through them, held keys read inside.
     */
    @Test
    fun `the syncs apply names and colours as one update of the names and courses as they stand`() {
        val names = body("ui/screen/home/CourseSyncReconciler.kt", "applyNameOverrides")
        assertTrue(
            "the names are no longer merged inside one update, with the held keys read there",
            Regex("""dataCache\.updateCourseCustomNames\(signIn\) \{[^}]*held = held\(\)""").containsMatchIn(names),
        )
        for (call in listOf("dataCache.loadCourseCustomNames(", "dataCache.saveCourseCustomNames(")) {
            assertFalse("applyNameOverrides reads or saves the names outside the update", call in names)
        }
        // Read where the names are merged, not before the pass.
        for ((rel, name) in listOf("sync/reconcile/OverridesReconciler.kt" to "reconcile", "notification/BackgroundSyncWorker.kt" to "syncOverridesFromBackend")) {
            assertCalls(rel, name, "{ pendingCourseOverrides.held(result.fetchedAtMs) }")
        }
        assertCalls("sync/reconcile/OverridesReconciler.kt", "applyCourseOverrides", "applyNameOverrides(dataCache, overrides, syncNames, signIn, held)")

        assertCalls("notification/BackgroundCourseOverrides.kt", "apply", "dataCache.updateCoursesIfChanged(semester, signIn) { cached ->")
        for ((rel, name) in listOf(
            "notification/BackgroundCourseOverrides.kt" to "apply",
            "notification/BackgroundSyncWorker.kt" to "applyCourseOverridesBackground",
        )) {
            val body = body(rel, name)
            for (call in listOf("dataCache.loadCourses(", "dataCache.saveCourses(", "dataCache.loadCourseCustomNames(")) {
                assertFalse("$rel $name reads or saves outside the update: $call", call in body)
            }
        }
    }

    /** Saved whole from a copy read earlier, a change to the custom names undid one made meanwhile. */
    @Test
    fun `every change to the custom names goes through the one locked update`() {
        for ((rel, name) in listOf(
            "ui/screen/classtable/ClassTableViewModel.kt" to "setCustomCourseName",
            "ui/screen/classtable/ClassTableViewModel.kt" to "revertCourseName",
            "ui/screen/classtable/ClassTableTermReset.kt" to "run",
        )) {
            assertCalls(rel, name, "dataCache.updateCourseCustomNames(")
        }
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "app/src/main/java").isDirectory && !File(root, "src/main/java").isDirectory) {
            root = root.parentFile
        }
        val main = root?.let { listOf(File(it, "app/src/main/java"), File(it, "src/main/java")).first(File::isDirectory) }
            ?: throw AssertionError("Could not locate app/src/main/java from ${File("").absolutePath}")
        // Outside DataCache, only a save that replaces them all, Use Server's clear, saves them whole.
        val saves = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DataCache.kt" }
            .flatMap { file -> file.readLines().filter { ".saveCourseCustomNames(" in it }.map { "${file.name}: ${it.trim()}" } }
            .toList()
        assertEquals(listOf("SettingsViewModel.kt: dataCache.saveCourseCustomNames(emptyMap(), signIn)"), saves)
    }

    /** tigerduck-app-android#404: the switches kept the set they were built with. */
    @Test
    fun `the Calendar's holiday switches follow a sync's choices and a sign-out's clear`() {
        val source = source("ui/screen/calendar/CalendarViewModel.kt").joinToString("\n")
        val refresh = "_holidayOverrides.value = academicCalendar.optedInHolidayIds"
        val changes = source.indexOf("academicCalendar.changes.collect")
        assertTrue("Calendar no longer follows the store's changes", changes >= 0 && source.indexOf(refresh, changes) in changes until changes + 300)
        val signedOut = source.indexOf("if (!isAuthed) {")
        assertTrue("Calendar no longer redraws the switches at a sign-out", signedOut >= 0 && source.indexOf(refresh, signedOut) in signedOut until source.indexOf("} else if (signedIn)", signedOut))
    }

    /** tigerduck-app-android#404: sent directly, a PUT is lost when it fails. */
    @Test
    fun `every holiday choice PUT goes through the outbox`() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "app/src/main/java").isDirectory && !File(root, "src/main/java").isDirectory) {
            root = root.parentFile
        }
        val main = File(root ?: throw AssertionError("no :app module"), if (File(root, "app").isDirectory) "app/src/main/java" else "src/main/java")
        // A call or a reference, outside comments.
        val use = Regex("""(?:\.|::)putHolidayOverride\b""")
        val callers = main.walk().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { line -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && use.containsMatchIn(line) } }
            .map { it.name }.toSet()
        assertEquals(setOf("PendingHolidayOverrides.kt"), callers)
    }

    /** tigerduck-app-android#380. */
    @Test
    fun `a colour or name whose PATCH failed is kept, and every full sync sends what waits first and holds it against the snapshot`() {
        assertCalls("ui/screen/classtable/ClassTableViewModel.kt", "syncCourseOverride", "pendingCourseOverrides.send(")
        assertCalls("sync/ForegroundSteps.kt", "retryUnsent", "pendingCourseOverrides.retry(signIn)")
        // Home's: sent before the fetch, held in a step after it (SynchronizerOrderTest).
        assertCalls("sync/reconcile/OverridesReconciler.kt", "reconcile", "pendingCourseOverrides.held(result.fetchedAtMs)")
        val body = body("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend")
        val retry = body.indexOf("pendingCourseOverrides.retry(signIn)")
        val fetch = body.indexOf("syncApiClient.fetchFullSync(")
        val held = body.indexOf("pendingCourseOverrides.held(result.fetchedAtMs)")
        assertTrue("the worker no longer sends the waiting colours and names before the snapshot", retry in 0 until fetch)
        assertTrue("the worker no longer holds the waiting colours and names against the snapshot", held > fetch)
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
        val saved = body.indexOf("dataCache.saveCourses(updated, signIn)")
        val sent = body.indexOf("pendingCourseOverrides.retry(signIn)")
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
        assertTrue("resolve no longer queues what Use Local sends at the reserved version", queued > reserved && "version, signIn,\n" in body)
    }

    /** tigerduck-app-android#380: a course gone from this phone would be PATCHed, and refused, at every sync. */
    @Test
    fun `the syncs forget the waiting colours and names of the courses they prune or a reset elsewhere wipes`() {
        // The term's commit hands the courses it took out to the forget (commitReconciledTerm).
        assertCalls("sync/reconcile/RosterReconciler.kt", "reconcileTerm", "outcome.merged, outcome.handAddedNos, pendingCourseOverrides, signIn,\n")
        assertCalls("sync/reconcile/CourseResetReconciler.kt", "applyCourseReset", "pendingCourseOverrides.forget(")
        assertCalls("notification/BackgroundSyncWorker.kt", "reconcileCurrentSemester", "pendingCourseOverrides, signIn,\n")
    }

    /** tigerduck-app-android#380: the answer decides the waiting colours and names too. */
    @Test
    fun `Use Server drops the waiting colours and names of its categories, and Use Local sends its own once the hold is lifted`() {
        assertCalls("ui/screen/settings/SettingsViewModel.kt", "resolve", "pendingCourseOverrides.adoptServer(conflict.categories, signIn)")
        val body = body("ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict")
        val lifted = body.indexOf("prefs.school.pendingConflictCategories.get() - held")
        val retry = body.indexOf("pendingCourseOverrides.retry(signIn)")
        assertTrue("resolveReenableConflict no longer sends the queued colours and names once the hold is lifted", retry > lifted && lifted >= 0)
    }

    @Test
    fun `the closed assignments are written only for the account that asked`() {
        val body = body("network/MoodleService.kt", "fetchAssignmentsRound")
        // The same check also guards the assignment records' write, inside it after this one.
        val guard = body.indexOf("if (tokenService.currentToken() == askedWith) {")
        val save = body.indexOf("if (closedNow != closedBefore) dataCache.saveClosedAssignments(closedNow, signIn)")
        assertTrue("the closed assignments are no longer written behind the token check", guard in 0 until save)
        assertTrue(
            "nothing else between the check and the write",
            body.substring(guard, save).count { it == '\n' } == 1,
        )
    }

    /**
     * A full sync still out when the user signs out comes back after the wipe. Its cache writes
     * carry the sign-in it began under, so they drop rather than put the departing account's
     * courses, deletions and marks into the files the next account starts from.
     */
    @Test
    fun `Home's sync and the worker write nothing once a sign-out has wiped the data`() {
        assertCalls("sync/reconcile/RosterReconciler.kt", "reconcile", "syncCourseList(result, takesCourseList, pass.courseSync, signIn)")
        assertCalls("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend", "reconcileCurrentSemester(result, courseSync, signIn)")
    }

    /**
     * A read that copies an old install's hand-added courses to the durable folder holds no
     * sign-in. It holds the cache's lock from its read to its write instead, and the wipe takes
     * that lock first: between two locks of its own, the wipe could run wholly in the gap, and
     * the copy land after it. No test can stand in that gap: nothing suspends there.
     */
    @Test
    fun `a read's copy of a hand-added course holds the cache's lock from its read to its write`() {
        val copy = body("data/cache/DataCache.kt", "migrateManualCoursesToUserData")
        val lock = copy.indexOf("cacheMutex.withLock {")
        val read = copy.indexOf("readJson<List<Course>>(File(cacheDir")
        val write = copy.indexOf("userDataMutex.withLock { atomicWrite(")
        assertTrue("the copy no longer reads and writes under the cache's lock", lock in 0 until read && read < write)
    }

    /**
     * A screen reads the cache, which suspends, and shows what it read only while the sign-in it
     * read it under stands. The student ID would pass the same student signing back in meanwhile.
     * Signed out with nothing stored, these screens leave their lists as they are, as they did.
     * Home's is driven in HomeViewModelTest; the class table and the calendar take a screen's
     * worth of collaborators.
     */
    @Test
    fun `the class table and the calendar show a read only while its sign-in stands`() {
        val table = "ui/screen/classtable/ClassTableViewModel.kt"
        assertCalls(table, "loadAssignmentMarks", "val signIn = signInNow()", "if (!authService.authState.value) return", "signIn.ifCurrent {")
        assertCalls(table, "refreshShareTerms", "val signIn = signInNow()", "if (authService.authState.value && signIn.isCurrent) terms else emptyList()")
        assertCalls(table, "lookUpMissingRooms", "val signIn = signInNow()", "if (!authService.authState.value || !signIn.isCurrent ||")
        val tableText = source(table).joinToString("\n")
        assertTrue("the class table's reload no longer stops on an ended sign-in", "if (!signIn.isCurrent) return@collect" in tableText)
        val calendar = source("ui/screen/calendar/CalendarViewModel.kt").joinToString("\n")
        assertTrue(
            "the calendar's reload no longer asks the sign-in it read under",
            "signIn.ifCurrent { _events.value = withMoodleRows(_events.value, assignments) }" in calendar,
        )
    }

    /**
     * What a write carries is read where its work starts: a tap's at the tap, though its save
     * waits its turn on a channel or a coroutine, and a lookup's before its request goes out.
     * Read as the write lands, each would pass for the next account's.
     */
    @Test
    fun `a tap's save and a lookup's write hold the sign-in of their start`() {
        val tap = body("ui/screen/home/HomeViewModel.kt", "toggleOverride")
        assertTrue("Home's tap no longer reads its sign-in first", tap.indexOf("val signIn = signInNow()") in 0 until tap.indexOf("primaryChannel.trySend"))
        assertCalls("ui/screen/home/HomeViewModel.kt", "toggleOverride", "primaryChannel.trySend(primary.value to signIn)", "unsentMarks.send(id, status, signIn)")
        val edit = body("ui/screen/classtable/ClassTableViewModel.kt", "showEdit")
        assertTrue("an edit of the class table no longer holds the tap's sign-in", "UnsavedEdit(nextUnsavedEdit++, signInNow())" in edit)
        val lookup = body("network/CourseService.kt", "fetchLookup")
        assertTrue(
            "a lookup no longer reads its sign-in before the request",
            lookup.indexOf("val signIn = sessions.school.current") in 0 until lookup.indexOf("client.newCall(request)"),
        )
    }

    /**
     * The sign-out cancels Home's fetch, and a `runCatching` around its last upload would take
     * that cancellation for a failed upload: the fetch would go on to put the departing
     * account's courses and assignments back on the screen the sign-out just emptied.
     */
    @Test
    fun `Home's fetch lets a sign-out's cancellation through its assignment upload`() {
        val body = body("ui/screen/home/HomeViewModel.kt", "fetchData")
        assertTrue("fetchData no longer uploads the assignments", "pushApiClient.uploadAssignments(written, remoteAssignments.answer.signIn)" in body)
        assertTrue("fetchData catches the upload's cancellation", "runCatching { pushApiClient.uploadAssignments" !in body)
    }

    /**
     * tigerduck-app-android#417: Home's fetch saves the current term's list and the upload sends
     * it, so a course whose query failed has to keep the row the term has, as the class table's
     * fetch does (ClassTableTermFetchTest).
     */
    @Test
    fun `Home's fetch keeps a course's row when its query fails`() {
        assertCalls("ui/screen/home/HomeViewModel.kt", "fetchCoursesAndAssignments", "kept = cachedByNo[courseNo]")
    }

    /**
     * tigerduck-app#890: Moodle lists course sites and keeps a dropped class, so Home and the
     * background sync take the term in session from course selection, else add the transcript's
     * classes to it, else leave it as it is. The transcript is the one on disk: neither signs in
     * to the score system. The rule is TranscriptTerms.termFrom's, under ClassTableTermSourceTest.
     */
    @Test
    fun `Home and the background sync add no course Moodle alone lists, and read the transcript from its cache`() {
        for ((rel, name) in listOf(
            "ui/screen/home/HomeViewModel.kt" to "fetchCoursesAndAssignments",
            "notification/TermCourseSync.kt" to "sync",
        )) {
            val body = body(rel, name)
            for (call in listOf("TranscriptTerms.termFrom(", "dataCache.loadScoreReport(studentId)?.report", "TranscriptTerms.drawnCourses(")) {
                assertTrue("$rel $name no longer calls $call", call in body)
            }
            for (call in listOf("rosterOrder", "fetchScoreReport", "recordFinalRoster")) {
                assertFalse("$rel $name calls $call", call in body)
            }
        }
    }

    /**
     * Home and the background sync fold their answers onto the term through the class table's
     * merge, ClassTableCourseMerge.mergeFetched (Home's mergeRemote hands to it), under
     * ClassTableCourseMergeTest and HomeCourseMergeTest. A course added here keeps having no
     * Moodle ID, where the lookup's would make a past term's rebuild drop it; and a term only
     * gaining from the transcript keeps the courses saved while its lookups ran, which its
     * answers do not list (tigerduck-app#890).
     */
    @Test
    fun `Home and the background sync merge as the class table does, and a term only gaining removes nothing`() {
        assertCalls("ui/screen/home/HomeViewModel.kt", "fetchData", "onlyGains = remote.onlyGains")
        assertCalls("ui/screen/home/HomeViewModel.kt", "fetchCoursesAndAssignments", "onlyGains = term.onlyGains")
        val merge = source("ui/screen/home/HomeCourseMerge.kt").joinToString("\n")
        assertTrue("Home's merge no longer hands to the class table's", "ClassTableCourseMerge.mergeFetched(" in merge)
        assertCalls(
            "notification/TermCourseSync.kt", "sync",
            "ClassTableCourseMerge.mergeFetched(",
            "term.onlyGains ||",
            "names = dataCache.loadCourseCustomNames()",
        )
    }

    /**
     * A past term's rebuild can record its drops and save the term while a gaining fetch's lookups
     * run, so Home and the background sync commit through commitFetchedTerm, which checks the
     * drop list under the courses' lock and keeps the adds still waiting (FetchedTermCommitTest).
     * The background sync uploads the list as committed, never a course the commit left out.
     */
    @Test
    fun `Home and the background sync commit a fetch against the drop list as it stands, and the sync uploads what was committed`() {
        assertCalls(
            "ui/screen/home/HomeViewModel.kt", "fetchData",
            "courses = commitFetchedTerm(",
            "waitingAdds = { pendingCourseAdds.waitingIn(remote.semester) }",
        )
        assertCalls(
            "notification/TermCourseSync.kt", "sync",
            "val merged = commitFetchedTerm(",
            "waitingAdds = { waitingAdds(semester) }",
            "merged, semester, roster = term.roster,",
            "uploader.upload(upload.courses, semester,",
        )
        val sync = source("notification/TermCourseSync.kt").joinToString("\n")
        assertTrue("TermCourseSync no longer waits on the adds still going up", "waitingAdds = pendingCourseAdds::waitingIn" in sync)
        assertCalls("notification/BackgroundSyncWorker.kt", "syncCourses", "termCourseSync.sync(studentId, password, courseService.currentSemesterCode(), signIn = signIn)")
        for ((rel, name) in listOf("ui/screen/home/HomeViewModel.kt" to "fetchData", "notification/TermCourseSync.kt" to "sync")) {
            assertFalse("$rel $name saves a fetch past the commit", "dataCache.saveCourses(" in body(rel, name))
        }
    }

    /**
     * A fetch whose course selection answer a newer one replaced commits nothing, and the
     * background sync's sends nothing, under TermCourseSyncTest. The background sync commits,
     * builds and sends under PendingCourseAdds.uploading, as the class table's fetch does, so a
     * reset's DELETE never lands between its commit and its upload.
     */
    @Test
    fun `Home and the background sync commit an answer only while it is the term's latest, and the sync uploads under the adds lock`() {
        assertCalls(
            "ui/screen/home/HomeViewModel.kt", "fetchCoursesAndAssignments",
            "val recorded = courseNos?.let { dataCache.recordSelectionRoster(semester, it, signIn) }",
            "answer = recorded?.answer,",
        )
        assertCalls("ui/screen/home/HomeViewModel.kt", "fetchData", "remote.onlyGains, remote.answer, remote.signIn,")
        val body = body("notification/TermCourseSync.kt", "sync")
        val lock = body.indexOf("uploading {")
        val commit = body.indexOf("commitFetchedTerm(")
        val upload = body.indexOf("uploader.upload(")
        assertTrue("TermCourseSync no longer commits and uploads under the adds lock", lock in 0 until commit && commit < upload)
        assertTrue("TermCourseSync no longer commits against its answer", "term.onlyGains, answer, signIn," in body)
        val sync = source("notification/TermCourseSync.kt").joinToString("\n")
        assertTrue("TermCourseSync no longer wires the adds lock", "uploading = { block -> pendingCourseAdds.uploading(block) }" in sync)
    }

    /**
     * Home's fetch of a course selection answer DataCache refused, for a newer one kept or a wipe
     * since the fetch began, looks up and draws no courses, so its fetchData commits and uploads
     * none: its roster would take off what the newer answer lists. The background sync's stops
     * as TermCourseSyncTest drives it.
     */
    @Test
    fun `Home's fetch of a refused course selection answer draws no courses to commit`() {
        val body = body("ui/screen/home/HomeViewModel.kt", "fetchCoursesAndAssignments")
        val stop = body.indexOf("if (recorded == RecordedSelection.Refused) return@async null")
        val lookUp = body.indexOf("courseService.lookupOrFallback(")
        assertTrue("Home's fetch no longer stops on a refused answer before the lookups", stop in 0 until lookUp)
        assertTrue("Home's fetch no longer stops on a refused answer before it draws the courses", lookUp < body.indexOf("RemoteCourses("))
        assertCalls("ui/screen/home/HomeViewModel.kt", "fetchData", "if (remote != null && remote.courses.isNotEmpty()) {")
    }

    /**
     * The background sync adds the transcript's classes to the term's cached courses and looks
     * every one up: one whose query fails, or brings no rows, keeps the row the term has, where
     * Moodle's stand-in would take its times and room, as Home's fetch does (#417).
     */
    @Test
    fun `the background sync keeps a course's row when its query fails`() {
        val body = body("notification/TermCourseSync.kt", "sync")
        assertTrue("sync no longer hands the term's row to the lookup", "sources.lookUp(semester, courseNo, moodleByNo[courseNo], kept = cachedByNo[courseNo])" in body)
        val sync = source("notification/TermCourseSync.kt").joinToString("\n")
        assertTrue("the lookup is no longer CourseService.lookupOrFallback", "courseService.lookupOrFallback(semester, courseNo, moodle, kept = kept)" in sync)
        assertFalse("sync still stands Moodle in for a failed query", "CourseService.fallbackCourseFromMoodle(" in sync)
    }

    /**
     * `/v3/sync/full`'s `roster_requests` (backend 3.10.1, tigerduck-app-android#595) are answered
     * from both full syncs, Home's and the worker's, only with the course list synced, and through
     * TermCourseSync, under TermCourseSyncTest. Home's runs pinned to the sign-in its sync began under.
     *
     * Both answer as the sign-in read before the snapshot's fetch, and TermCourseSync's upload
     * goes with that sign-in's token alone: Home's runs on the application scope, and a sign-out
     * and another sign-in during the lookups would send the roster as the next account's, which
     * drops that account's courses.
     */
    @Test
    fun `Home's sync and the worker's answer the backend's roster requests`() {
        assertCalls("sync/reconcile/RosterReconciler.kt", "reconcile", "if (takesCourseList) answerRosterRequests(result.rosterRequests, signIn)")
        assertCalls(
            "sync/reconcile/RosterReconciler.kt", "answerRosterRequests",
            "appScope.launch {",
            "termCourseSync.answerRosterRequests(requests, studentId, password, signIn)",
        )
        val worker = body("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend")
        val reconcile = worker.indexOf("reconcileCurrentSemester(result, courseSync, signIn)")
        val answer = worker.indexOf("answerRosterRequests(result.rosterRequests, signIn)")
        assertTrue("the worker no longer answers roster requests after its reconcile", reconcile in 0 until answer)
        val run = body("notification/BackgroundSyncWorker.kt", "doWork")
        val pinned = run.indexOf("val signIn = authTokenManager.session.current")
        assertTrue("the worker no longer reads the sign-in before the snapshot", pinned in 0 until run.indexOf("syncOverridesFromBackend(signIn)"))
        assertCalls("notification/BackgroundSyncWorker.kt", "answerRosterRequests", "termCourseSync.answerRosterRequests(requests, studentId, password, signIn)")
        assertCalls("push/SyncApiClient.kt", "fetchFullSync", "rosterRequests = parseRosterRequests(json)")
        val sync = source("notification/TermCourseSync.kt").joinToString("\n")
        assertTrue("TermCourseSync no longer pins its upload", "pushApiClient.uploadCourses(courses, semester, signIn," in sync)
    }

    /**
     * Each full sync's reconcile reads the term's course selection answer with when it arrived,
     * against when the snapshot was fetched, and records the server drops for the fetches that
     * only gain (tigerduck-app#914, #918).
     */
    @Test
    fun `Home's sync and the worker's read the answer's time and record the server drops`() {
        for ((rel, name) in listOf(
            "sync/reconcile/RosterReconciler.kt" to "reconcileTerm",
            "notification/BackgroundSyncWorker.kt" to "reconcileCurrentSemester",
        )) {
            assertCalls(
                rel, name,
                "answer = dataCache.selectionAnswerIn(semester)",
                "fetchedAtMs = result.fetchedAtMs",
                "dataCache.recordServerDrops(semester, outcome.serverDrops, signIn)",
                // A roster from here listing one goes again unchanged; see TermCourseSyncTest.
                "pushApiClient.serverDropped(semester, outcome.serverDrops.keys)",
            )
        }
        val commit = source("ui/screen/home/HomeBackendSync.kt").joinToString("\n").substringAfter("internal suspend fun commitReconciledTerm(")
        assertTrue("the commit no longer reads the term's answer with its time", "dataCache.selectionAnswerIn(semester)" in commit)
        val fetched = source("ui/screen/home/FetchedTermCommit.kt").joinToString("\n")
        assertTrue("a fetch only gaining no longer leaves out the server drops", "dataCache.fetchDropsIn(semester)" in fetched)
    }
}
