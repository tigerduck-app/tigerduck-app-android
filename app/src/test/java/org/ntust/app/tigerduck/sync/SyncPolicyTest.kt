package org.ntust.app.tigerduck.sync

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncCategory.ASSIGNMENTS
import org.ntust.app.tigerduck.sync.SyncCategory.COURSES
import org.ntust.app.tigerduck.sync.SyncCategory.COURSE_COLORS
import org.ntust.app.tigerduck.sync.SyncCategory.COURSE_NAMES
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [SyncPolicy] is pinned to the copies of each rule it will replace, on every combination of the
 * switches and the hold: each answer is checked against the copy run on the same preferences, the
 * backend client's gates by whether a request went out. Both flavors run this, so the flavor is
 * covered as the build gives it, and as an input of its own below.
 */
class SyncPolicyTest {

    private val backend = ScriptedBackend()
    private val context = JvmContext()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-sync-policy-test", Context.MODE_PRIVATE))
        .apply { save("access", "refresh", Long.MAX_VALUE) }
    private val tokenManager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    private val client = PushApiClient(backend.client, prefs, tokenManager)
    private val policies = SyncPolicies(prefs)
    private val signIn get() = tokenManager.session.signIns.current

    /** Every set of categories: the sixteen settings of the four switches, or of the hold. */
    private val everySet: List<Set<SyncCategory>> = (0 until 16).map { bits ->
        SyncCategory.entries.filterIndexed { i, _ -> bits shr i and 1 == 1 }.toSet()
    }

    private fun store(master: Boolean, on: Set<SyncCategory>, held: Set<SyncCategory>) {
        prefs.cloudSyncEnabled = master
        prefs.syncCourses = COURSES in on
        prefs.syncCourseColors = COURSE_COLORS in on
        prefs.syncCourseNames = COURSE_NAMES in on
        prefs.syncAssignments = ASSIGNMENTS in on
        // A category a newer build may hold is no category of this one's, for either side.
        assertTrue(prefs.school.pendingConflictCategories.set(held.map { it.stored }.toSet() + "bulletins", signIn))
    }

    /** Whether the client's call let a request out. */
    private suspend fun sent(call: suspend () -> Unit): Boolean {
        val before = backend.endpoints().size
        call()
        return backend.endpoints().size > before
    }

    @Test
    fun `every answer equals today's copy, on every combination of the switches and the hold`() = runTest {
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val roster = listOf(Course(courseNo = "CS1", courseName = "CS1"))
        var rows = 0
        for (master in listOf(false, true)) for (on in everySet) for (held in everySet) {
            store(master, on, held)
            val policy = policies.current
            val pending = prefs.school.pendingConflictCategories.get()
            val row = "master=$master on=$on held=$held"
            fun check(what: String, today: Boolean, ours: Boolean) = assertEquals("$what at $row", today, ours)

            check("keeps(ASSIGNMENTS)", UnsentMarks.recordsMarks(prefs), policy.keeps(ASSIGNMENTS))
            check("sends(ASSIGNMENTS)", UnsentMarks.sendsMarks(prefs), policy.sends(ASSIGNMENTS))
            check("keeps(COURSE_COLORS)", PendingCourseOverrides.records(prefs, color = true), policy.keeps(COURSE_COLORS))
            check("keeps(COURSE_NAMES)", PendingCourseOverrides.records(prefs, color = false), policy.keeps(COURSE_NAMES))
            check("sends(COURSE_COLORS)", PendingCourseOverrides.sends(prefs, color = true), policy.sends(COURSE_COLORS))
            check("sends(COURSE_NAMES)", PendingCourseOverrides.sends(prefs, color = false), policy.sends(COURSE_NAMES))
            check(
                "applies(COURSE_COLORS)",
                CourseSyncReconciler.syncsCourseColors(prefs.syncCourseColors, pending), policy.applies(COURSE_COLORS),
            )
            check(
                "applies(COURSE_NAMES)",
                CourseSyncReconciler.syncsCourseNames(prefs.syncCourseNames, pending), policy.applies(COURSE_NAMES),
            )
            check("applies(COURSES)", CourseSyncReconciler.syncsCourseList(prefs.syncCourses, pending), policy.applies(COURSES))
            check(
                "applies(ASSIGNMENTS)",
                AssignmentOverrideReconciler.reasonToLeaveMarks(prefs.syncAssignments, pending) == null,
                policy.applies(ASSIGNMENTS),
            )
            check("tigerSyncOn", prefs.cloudSyncEnabled, policy.tigerSyncOn)
            check(
                "sendsCourseIgnores and sends(ASSIGNMENTS)",
                CourseIgnoreSync.syncsCourseIgnores(prefs), policy.sendsCourseIgnores && policy.sends(ASSIGNMENTS),
            )
            check("sendsCourseIgnores", client.patchCourseIgnore("m1", true, null, signIn) != null, policy.sendsCourseIgnores)
            check("uploadsCourses", client.uploadCoursesAnswered(roster, "1151", signIn) != null, policy.uploadsCourses)
            check("sends(COURSES)", sent { client.deleteCourse("client:1151:CS1", signIn) }, policy.sends(COURSES))
            check("sends(COURSES) is the upload's", policy.uploadsCourses, policy.sends(COURSES))
            // No copy asks this yet: the course list's queues keep their edits whatever the switches say.
            check("keeps(COURSES)", prefs.cloudSyncEnabled && prefs.syncCourses, policy.keeps(COURSES))
            rows++
        }
        assertEquals(512, rows)
    }

    @Test
    fun `the policy takes the build's flavor, and no last writer wins yet`() {
        val policy = policies.current
        assertEquals(!BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true), policy.flavorSyncs)
        assertFalse(policy.lastWriterWins)
    }

    @Test
    fun `a build without TigerSync keeps, sends and uploads nothing, whatever its switches say`() {
        for (cloudSync in listOf(false, true)) for (lww in listOf(false, true)) for (on in everySet) for (held in everySet) {
            val policy = SyncPolicy(cloudSync, flavorSyncs = false, switchedOn = on, held = held, lastWriterWins = lww)
            val row = "cloudSync=$cloudSync lww=$lww on=$on held=$held"
            assertFalse(row, policy.tigerSyncOn)
            assertFalse(row, policy.uploadsCourses)
            assertFalse(row, policy.sendsCourseIgnores)
            for (category in SyncCategory.entries) {
                assertFalse("keeps($category) at $row", policy.keeps(category))
                assertFalse("sends($category) at $row", policy.sends(category))
                // A full sync never runs here; asked anyway, the answer is the switch's and the hold's.
                assertEquals("applies($category) at $row", category in on && category !in held, policy.applies(category))
            }
        }
    }

    @Test
    fun `under last writer wins every category is kept whatever its switches say, and nothing else changes`() {
        for (cloudSync in listOf(false, true)) for (flavor in listOf(false, true)) for (on in everySet) for (held in everySet) {
            val before = SyncPolicy(cloudSync, flavor, on, held, lastWriterWins = false)
            val after = SyncPolicy(cloudSync, flavor, on, held, lastWriterWins = true)
            val row = "cloudSync=$cloudSync flavor=$flavor on=$on held=$held"
            assertEquals(row, before.tigerSyncOn, after.tigerSyncOn)
            assertEquals(row, before.uploadsCourses, after.uploadsCourses)
            assertEquals(row, before.sendsCourseIgnores, after.sendsCourseIgnores)
            for (category in SyncCategory.entries) {
                assertEquals("keeps($category) at $row", flavor, after.keeps(category))
                assertEquals("sends($category) at $row", before.sends(category), after.sends(category))
                assertEquals("applies($category) at $row", before.applies(category), after.applies(category))
            }
        }
    }

    @Test
    fun `a policy is one reading, and a switch turned after it changes the next one only`() {
        store(master = true, on = SyncCategory.entries.toSet(), held = emptySet())
        val read = policies.current
        prefs.syncAssignments = false
        prefs.cloudSyncEnabled = false
        assertTrue(prefs.school.pendingConflictCategories.set(setOf(COURSE_COLORS.stored), signIn))

        assertEquals(read.flavorSyncs, read.sends(ASSIGNMENTS))
        assertEquals(read.flavorSyncs, read.tigerSyncOn)
        assertTrue(read.applies(COURSE_COLORS))
        val next = policies.current
        assertFalse(next.sends(ASSIGNMENTS))
        assertFalse(next.tigerSyncOn)
        assertFalse(next.applies(COURSE_COLORS))
    }
}
