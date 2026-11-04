package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import javax.inject.Inject

/**
 * A category's first sync on `/v4` (LWW D13, `tigersync-merge`, "Existing device data counts as
 * oldest"), first of the steps, before any reads what waits ([SyncPass.holds]).
 *
 * - A category this account has synced here before ([SyncPass.syncedBefore]) sends nothing it
 *   holds unstamped: its values are a copy of the server's, and the snapshot replaces them (D12).
 * - A category it never synced, which the pass applies, queues each local value as the oldest
 *   there is, stamped 0 (`queueAsOldest`, never over an edit that waits), which fills only a field
 *   the backend holds no stamp for: the marks and the ignored courses (the assignments'), the custom
 *   colors and the custom names of every term's courses, each by its Moodle id as the class table
 *   sends one, and the course list's hides (X8). The pull then keeps them as queued, and the outbox
 *   sends them.
 * - Course membership's adds go as they always went: queued, or named in the roster upload's
 *   `adds` with their time or 0 (X9). A hide is in neither, so each one this phone holds that no
 *   delete waits for is queued too, by its term. A bare one from before hides had terms is pinned
 *   to its terms first ([pinnedLegacyHides]); one no term here carries is not sent.
 *
 * Home's alone: the background's pass leaves a category never synced here to it (C2). On `/v3`
 * it does nothing, and the marks' first-sync upload stays (`AssignmentOverrideReconciler.isFirstUpload`).
 * Returns false: it takes nothing from the snapshot.
 */
class FirstSyncReconciler @Inject constructor(
    private val dataCache: DataCache,
    private val unsentMarks: UnsentMarks,
    private val courseIgnoreSync: CourseIgnoreSync,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val pendingCourseDeletes: PendingCourseDeletes,
) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean {
        if (pass.result.serverHlc == null || pass.mode != SyncMode.FOREGROUND) return false
        val signIn = pass.signIn
        for (category in SyncCategory.entries) {
            if (category in pass.syncedBefore || !pass.policy.applies(category) || !signIn.isCurrent) continue
            when (category) {
                SyncCategory.ASSIGNMENTS -> queueMarksAndIgnores(signIn)
                SyncCategory.COURSE_COLORS -> queueColors(signIn)
                SyncCategory.COURSE_NAMES -> queueNames(signIn)
                SyncCategory.COURSES -> queueHides(signIn)
            }
        }
        return false
    }

    private suspend fun queueMarksAndIgnores(signIn: SignIn) {
        for (id in dataCache.loadIgnoredAssignments()) unsentMarks.queueAsOldest(id, AssignmentOverrideReconciler.STATUS_IGNORED, signIn)
        for (id in dataCache.loadMarkedCompletedAssignments()) unsentMarks.queueAsOldest(id, AssignmentOverrideReconciler.STATUS_COMPLETED, signIn)
        for (key in dataCache.loadIgnoredCourses()) {
            val term = CourseIgnores.semesterOf(key)
            val courseNo = key.substringAfter(':')
            val course = dataCache.loadCourses(term).find { it.courseNo == courseNo }
            val moodleId = course?.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: "$term$courseNo"
            courseIgnoreSync.queueAsOldest(term, courseNo, moodleId, course?.moodleNumericCourseId, ignore = true, signIn = signIn)
        }
    }

    private suspend fun queueColors(signIn: SignIn) {
        for ((term, course) in everyTermsCourses()) {
            val hex = course.customColorHex ?: continue
            val moodleId = course.moodleIdNumber ?: continue
            pendingCourseOverrides.queueAsOldest(
                PendingCourseOverrides.Change(moodleId, colorHex = hex, semester = term, courseNo = course.courseNo), signIn,
            )
        }
    }

    private suspend fun queueNames(signIn: SignIn) {
        val names = dataCache.loadCourseCustomNames()
        for ((term, course) in everyTermsCourses()) {
            val moodleId = course.moodleIdNumber ?: continue
            for ((locale, name) in names[course.courseNo].orEmpty()) {
                pendingCourseOverrides.queueAsOldest(
                    PendingCourseOverrides.Change(moodleId, customName = name, locale = locale, semester = term, courseNo = course.courseNo),
                    signIn,
                )
            }
        }
    }

    private suspend fun queueHides(signIn: SignIn) {
        // A hide is its term and course number (CourseTombstoneKeys.key). A bare one, from before
        // hides had terms, is pinned first to each term here whose courses carry it, by the rule the
        // course list pins it by later in this pass, so it goes as that term's delete and the pull
        // keeps it; one no term here carries stays bare, and is not sent.
        for (hide in dataCache.pinnedLegacyHides(dataCache.loadEveryTermCourses().keys, signIn)) {
            if (':' !in hide) continue
            pendingCourseDeletes.queueAsOldest(hide.substringBefore(':'), hide.substringAfter(':'), signIn)
        }
    }

    // Every term's courses on this phone, each with its term.
    private suspend fun everyTermsCourses(): List<Pair<String, Course>> =
        dataCache.loadEveryTermCourses().flatMap { (term, courses) -> courses.map { term to it } }
}
